package com.virco.agent.react;

import java.text.MessageFormat;
import java.util.List;

import org.springframework.stereotype.Service;

import com.virco.agent.config.AgentProperties;
import com.virco.agent.dialog.AgentMetrics;
import com.virco.agent.model.ChatMessage;
import com.virco.agent.model.ChatResponse;
import com.virco.agent.model.InternalToolCall;
import com.virco.agent.model.ALlmClient;
import com.virco.agent.model.LlmRouter;
import com.virco.agent.model.LlmRouter.AllProvidersUnavailableException;
import com.virco.agent.react.Event.EventSink;
import com.virco.agent.react.interceptor.AgentInterceptor;
import com.virco.agent.react.interceptor.LlmInterceptor;
import com.virco.agent.service.AgentException;
import com.virco.agent.tools.ToolRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@Service
@RequiredArgsConstructor 
public class ReActAgent {
    private final LlmRouter router;
    private final ToolRegistry tools;
    private final SessionStore sessionStore;
    private final AgentProperties agent;
    private final List<AgentInterceptor> agentInterceptors;
    private final List<LlmInterceptor> llmInterceptors;
    private final AgentMetrics metrics;

    // 构造注入略

    public String run(String sessionId, String userInput, EventSink sink) {
        List<ChatMessage> messages = sessionStore.load(sessionId);
        messages.add(ChatMessage.system(systemPrompt()));
        messages.add(ChatMessage.user(userInput));
        AgentContext ctx = new AgentContext(sessionId, userInput, messages);
        long deadline = System.currentTimeMillis() + agent.react().maxDurationMs();
        int dupCount = 0; 
        String lastSig = null;

        
        try {
            log.info(MessageFormat.format("\n===============agent [START size={0}]======================", messages.size()));
            fireAgentBefore(ctx);
            String answer = null;
            for (int step = 0; step < agent.react().maxSteps(); step++) {
                if (System.currentTimeMillis() > deadline) { 
                    answer = "[超时终止]"; 
                    log.debug("\n===============agent [超时终止]======================");
                    break; 
                }
                if (sink != null) sink.emit(new Event.StepStart(step + 1));
                ChatResponse resp = callLlm(messages, sink);
                ctx.addUsage(resp.usage());
                if (resp.hasToolCalls()) {
                    messages.add(ChatMessage.assistant(resp.content(), resp.toolCalls()));
                    log.debug("\n===============agent["+messages.size()+"] LLM answer[tools("+resp.toolCalls().size()+")]======================\n'''" + resp.content()+ "\n'''");
                    log.debug("\n===============agent ready to call tools["+resp.toolCalls().size()+"]======================");
                    for (InternalToolCall tc : resp.toolCalls()) {
                        String sig = tc.name() + ":" + tc.argumentsJson();
                        String result = "";
                        if (sig.equals(lastSig)) { 
                            dupCount++;
                        } else { 
                            dupCount = 0; 
                            lastSig = sig;
                        }
                        if (dupCount >= agent.react().duplicateCallThreshold()) {
                            result = "[检测到重复调用 ```"+sig+"```  "+dupCount+"次，请不要继续]"; 
                            log.debug(MessageFormat.format("\n===============agent [END][重复调用数{0}-size={1}]======================", dupCount, agent.react().maxSteps()));

                        }else{
                            try {
                                if (sink != null) sink.emit(new Event.ToolStart(tc.name(), tc.argumentsJson()));
                                long t0 = System.currentTimeMillis();
                                result = tools.execute(tc.name(), tc.argumentsJson());
                                if (sink != null) sink.emit(new Event.ToolDone(tc.name(), System.currentTimeMillis() - t0, abbreviate(result, 200)));
                            } catch (Exception e) {
                                result = e.getMessage();
                            }
                        }
                        messages.add(ChatMessage.tool(tc.id(), result));
                        log.debug("\n===============agent["+messages.size()+"] Tool["+tc.name()+"][id="+tc.id()+"] answer======================\n'''" + result+ "\n'''");
                    }
                    if (answer != null) break;
                } else {
                    answer = resp.content();
                    messages.add(ChatMessage.assistant(answer, null));
                    log.debug("\n===============agent[\"+messages.size()+\"] LLM answer======================\n'''"+answer+"\n'''");
                    break;
                }
            }
            if (answer == null){
                answer = "[达到最大步数]";
                log.debug(MessageFormat.format("\n===============agent [达到最大步数{0}]======================", agent.react().maxSteps()));
            }
            log.debug(MessageFormat.format("\n===============agent [END size={0}]======================", messages.size()));
            sessionStore.save(sessionId, trim(messages));
            fireAgentAfter(ctx, answer);
            return answer;
        } catch (AllProvidersUnavailableException e) {
            fireAgentError(ctx, e);
            throw new AgentException("ALL_PROVIDERS_DOWN", "模型服务暂不可用，请稍后重试", e);
        } catch (Exception e) {
            fireAgentError(ctx, e);
            throw new AgentException("AGENT_ERROR", "处理失败: " + e.getMessage(), e);
        }
    }

    private ChatResponse callLlm(List<ChatMessage> messages, EventSink sink) {
        LlmContext lc = new LlmContext();
        lc.getAttributes().put("messages", List.copyOf(messages));
        fireLlmBefore(lc);
        if (sink == null) {
            ChatResponse r = router.chat(messages, tools.schemas());
            lc.setResponse(r); 
            fireLlmAfter(lc);
            return r;
        }
        ChatResponse r = router.chatStream(messages, tools.schemas(), new ALlmClient.StreamListener() {
            @Override public void onFirstToken() { fireLlmFirstToken(lc); }
            @Override public void onDelta(String d) {
                fireLlmChunk(lc, d);
                sink.emit(new Event.Delta(d));
            }
        });
        lc.setResponse(r); 
        fireLlmAfter(lc);
        return r;
    }

    private List<ChatMessage> trim(List<ChatMessage> messages) {
        // 回合级裁剪：保留 system + 最近 N 个完整回合
        int maxTurns = agent.react().maxHistoryTurns();
        // 简化：按条数保留 system + 最近 2*maxTurns 条，并保证 tool 消息配对
        // 完整实现见 ContextTrimmer
        return ContextTrimmer.trim(messages, maxTurns);
    }

    private String systemPrompt() {
        return """
            你是一个本地助手，可以使用工具完成任务。
            规则：
            1. 需要执行命令或读写文件时必须调用对应工具，不要编造结果。
            2. 工具返回错误时，向用户说明，不要假装成功。
            3. 回答简洁准确。
            """;
    }

    // 拦截器触发方法（略，内部遍历调用）
    private void fireAgentBefore(AgentContext c) { agentInterceptors.forEach(i -> safe(() -> i.beforeAgent(c))); }
    private void fireAgentAfter(AgentContext c, String a) { c.setAnswer(a); agentInterceptors.forEach(i -> safe(() -> i.afterAgent(c))); }
    private void fireAgentError(AgentContext c, Throwable e) { agentInterceptors.forEach(i -> safe(() -> i.onAgentError(c, e))); }
    private void fireLlmBefore(LlmContext c) { llmInterceptors.forEach(i -> safe(() -> i.beforeLlm(c))); }
    private void fireLlmAfter(LlmContext c) { llmInterceptors.forEach(i -> safe(() -> i.afterLlm(c))); }
    private void fireLlmFirstToken(LlmContext c) { llmInterceptors.forEach(i -> safe(() -> i.onFirstToken(c))); }
    private void fireLlmChunk(LlmContext c, String d) { llmInterceptors.forEach(i -> safe(() -> i.onLlmChunk(c, d))); }
    private void safe(Runnable r) { try { r.run(); } catch (Exception ignored) {} }
    private static String abbreviate(String s, int n) { return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "..."); }
}
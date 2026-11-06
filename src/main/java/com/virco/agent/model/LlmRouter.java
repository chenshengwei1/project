package com.virco.agent.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.virco.agent.config.AgentProperties;
import com.virco.agent.config.ChatLlmProperties;
import com.virco.agent.dialog.AgentMetrics;
import com.virco.agent.exception.ApiException;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Component
public class LlmRouter {
    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);
    private final ModelResolver resolver;
    private final ALlmClient client;
    private final ProviderHealth health;
    private final AgentProperties agent;
    private final ChatLlmProperties chat;
    private final AgentMetrics metrics;

    // 构造注入略

    public ChatResponse chat(List<ChatMessage> messages, List<ToolSchema> tools) {
        List<LlmProvider> candidates = resolver.candidates();
        List<Throwable> errors = new ArrayList<>();
        for (LlmProvider p : candidates) {
            if (!health.available(p.name())) continue;
            int attempts = 1 + agent.retry().perProvider();
            for (int i = 0; i < attempts; i++) {
                try {
                    ChatResponse r = client.chat(p, messages, tools, temperature(), maxTokens());
                    health.success(p.name());
                    return r;
                } catch (Exception e) {
                    boolean retryable = e instanceof LlmHttpException l && l.retryable();
                    log.warn("llm.call.fail provider={} attempt={} retryable={} err={}",
                             p.name(), i, retryable, e.toString());
                    if (!retryable || i == attempts - 1) {
                        health.failure(p.name());
                        errors.add(e);
                        break;
                    }
                    sleep(agent.retry().backoffMs() * (1L << i));
                }
            }
        }
        metrics.allProvidersDown();
        throw new ApiException.AllProvidersUnavailableException(errors);
    }

    /** 流式：仅首 token 到达前允许 fallback；到达后失败直接抛出 */
    public ChatResponse chatStream(List<ChatMessage> messages, List<ToolSchema> tools,
                                   ALlmClient.StreamListener listener) {
        List<LlmProvider> candidates = resolver.candidates();
        List<Throwable> errors = new ArrayList<>();
        for (LlmProvider p : candidates) {
            
            if (!health.available(p.name())) continue;
            AtomicBoolean firstTokenArrived = new AtomicBoolean(false);
            try {
                ChatResponse r = client.chatStream(p, messages, tools, temperature(), maxTokens(),thinking(),
                    new ALlmClient.StreamListener() {
                        @Override public void onFirstToken() { firstTokenArrived.set(true); listener.onFirstToken(); }
                        @Override public void onDelta(String d) { listener.onDelta(d); }
                    });
                health.success(p.name());
                return r;
            } catch (Exception e) {
                metrics.llmFail(e.getMessage());
                health.failure(p.name());
                errors.add(e);
                if (firstTokenArrived.get()) {
                    // 已输出，不再切换，直接抛出
                    log.warn("llm.stream.abort.after.first_token provider={}", p.name());
                    throw new ApiException.StreamAbortedException(e);
                }
                log.warn("llm.stream.fallback provider={} err={}", p.name(), e.toString());
            }
        }
        metrics.allProvidersDown();
        throw new ApiException.AllProvidersUnavailableException(errors);
    }

    private double temperature() {
        Double t = chat.defaults() != null ? chat.defaults().temperature() : null;
        return t != null ? t : 0.7;
    }
    private int maxTokens() {
        Integer m = chat.defaults() != null ? chat.defaults().maxTokens() : null;
        return m != null ? m : 4096;
    }

    private boolean thinking(){
        return chat.defaults().chatTemplateKwargs().enableThinking();
    }
    private void sleep(long ms) {
        try { Thread.sleep(ms + (long)(Math.random() * ms * 0.3)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

}
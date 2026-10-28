package com.virco.agent.model;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.tomcat.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.virco.agent.config.AgentProperties;
import com.virco.agent.config.ChatLlmProperties;
import com.virco.agent.dialog.AgentMetrics;

import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

@Component
@Slf4j 
public class ALlmClient {
    private final OkHttpClient http;
    private final ObjectMapper om;
    private final OpenAiProtocolAdapter adapter;
    private final AgentProperties agent;
    private final ChatLlmProperties chat;
    private final AgentMetrics metrics;

    public ALlmClient(ObjectMapper om, OpenAiProtocolAdapter adapter,
                     AgentProperties agent, ChatLlmProperties chat, AgentMetrics metrics) {
        this.om = om; this.adapter = adapter; this.agent = agent; this.chat = chat;
        this.metrics = metrics;
        var t = agent.timeouts();
        this.http = new OkHttpClient.Builder()
            .connectTimeout(t.connectMs(), TimeUnit.MILLISECONDS)
            .readTimeout(t.readMs(), TimeUnit.MILLISECONDS)
            .callTimeout(t.callMs(), TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build();
    }

    public ChatResponse chat(LlmProvider p, List<ChatMessage> messages,
                             List<ToolSchema> tools, double temperature, int maxTokens)
            throws IOException {
        String body = adapter.buildRequestBody(p, messages, tools, temperature, maxTokens);
        long t0 = System.nanoTime();
        Request req = new Request.Builder()
            .url(p.baseUrl().replaceAll("/$", "") + "/chat/completions")
            .header("Authorization", "Bearer " + p.apiKey())
            .header("Content-Type", "application/json")
            .post(RequestBody.create(body, MediaType.get("application/json")))
            .build();
        log.debug("正在尝试大模型："+body);
        try (Response resp = http.newCall(req).execute()) {
            String text = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new LlmHttpException(resp.code(), text,
                    isRetryable(resp.code()));
            }
            ChatResponse r = adapter.parseResponse(om.readTree(text));
            long elapsed = (System.nanoTime() - t0) / 1_000_000;
            log.info("llm.call provider={} model={} elapsed={}ms finish={} toolCalls={} usage={}",
                p.name(), p.modelId(), elapsed, r.finishReason(),
                r.toolCalls().size(), r.usage());
            metrics.llmSuccess(p.name(), elapsed);
            return r;
        } catch (LlmHttpException e) {
            log.error("调用大模型失败，失败原因是", e);
            e.printStackTrace();
            metrics.llmFail(p.name());
            throw e;
        }
    }

    /** 流式：通过 eventListener 回调 delta，返回拼接后的 ChatResponse */
    public ChatResponse chatStream(LlmProvider p, List<ChatMessage> messages,
                                   List<ToolSchema> tools, double temperature, int maxTokens,
                                   StreamListener listener) throws IOException {
        String body = adapter.buildStreamBody(p, messages, tools, temperature, maxTokens);
        Request req = new Request.Builder()
            .url(p.baseUrl().replaceAll("/$", "") + "/chat/completions")
            .header("Authorization", "Bearer " + p.apiKey())
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(RequestBody.create(body, MediaType.get("application/json")))
            .build();
                                    
        log.debug("正在尝试流式大模型：{}\n"+body, p.name());
        log.debug("provider="+ p.apiKey());
        OpenAiProtocolAdapter.ToolCallAccumulator acc = new OpenAiProtocolAdapter.ToolCallAccumulator();
        StringBuilder content = new StringBuilder();
        Usage[] usage = {Usage.ZERO};
        String[] finish = {"stop"};
        AtomicBoolean first = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        Object lock = new Object();

        EventSource es = EventSources.createFactory(http).newEventSource(req, new EventSourceListener() {
            @Override
            public void onEvent(EventSource s, String id, String type, String data) {
                if ("[DONE]".equals(data)) return;
                try {
                    JsonNode n = om.readTree(data);
                    JsonNode choice = n.path("choices").path(0);
                    JsonNode delta = choice.path("delta");
                    if (delta.hasNonNull("content")) {
                        String c = delta.get("content").asText();
                        content.append(c);
                        if (first.compareAndSet(false, true)) listener.onFirstToken();
                        listener.onDelta(c);
                    }
                    if (delta.has("tool_calls")) acc.feed(delta);
                    if (choice.hasNonNull("finish_reason")) finish[0] = choice.get("finish_reason").asText();
                    if (n.has("usage")) usage[0] = adapter.parseUsage(n.get("usage"));
                } catch (Exception e) {
                    log.warn("llm.stream.parse.fail data={}", abbreviate(data, 200), e);
                }
            }
            @Override public void onClosed(EventSource s) { synchronized (lock) { lock.notifyAll(); } }
            @Override public void onFailure(EventSource s, Throwable t, Response r) {
                // 专门解包 InvocationTargetException，拿到原始异常
                Throwable realException = ExceptionUtils.unwrapInvocationTargetException(t);
                String realMessage = realException.getMessage();

                log.error(realMessage, t);
                failed.set(true);
                synchronized (lock) { lock.notifyAll(); }
            }
        });

        synchronized (lock) {
            try { lock.wait(agent.timeouts().callMs()); } catch (InterruptedException ignored) {}
        }
        es.cancel();
        if (failed.get()) throw new IOException("stream failed");
        
        return new ChatResponse(
            content.isEmpty() ? null : content.toString(),
            acc.finish(), normalizeFinish(finish[0]), usage[0]);
    }

    private String normalizeFinish(String fr) {
        return "tool_calls".equals(fr) || "function_call".equals(fr) ? "tool_calls" : fr;
    }
    static boolean isRetryable(int code) {
        return code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
    }
    static String abbreviate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public interface StreamListener {
        default void onFirstToken() {}
        void onDelta(String delta);
    }
}
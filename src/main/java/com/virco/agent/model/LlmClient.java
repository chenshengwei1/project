package com.virco.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.virco.agent.entity.ChatDTO;
import com.virco.agent.config.AppProperties;
import com.virco.agent.config.AppProperties.GenerationParams;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public interface LlmClient {

    LlmStream stream(String conversationId, String assistantMessageId, String modelId,
                     List<ChatMessage> messages, boolean deepThink, boolean webSearch,
                     String regenerateMessageId) throws Exception;

    interface LlmStream extends AutoCloseable, Iterable<Chunk> {
        @Override void close();
    }

    record ChatMessage(String role, String content) {}

    record Chunk(Type type, String content,
                 List<String> queries, List<ChatDTO.SearchResult> results,
                 int promptTokens, int completionTokens, int totalTokens) {
        public enum Type { REASONING, SEARCH, DELTA, USAGE }
    }

    /** OpenAI 兼容实现；api-key 为空时回落到 Mock。 */
    @Component
    class OpenAiCompatible implements LlmClient {

        private static final Logger log = LoggerFactory.getLogger(OpenAiCompatible.class);

        private final Map<String, AppProperties.Provider> providers;
        private final ModelRegistry modelRegistry;
        private final ObjectMapper om;
        private final int timeoutSeconds;
        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15)).build();

        public OpenAiCompatible(AppProperties props, ModelRegistry modelRegistry, ObjectMapper om) {
            this.providers = props.getLlm().getProviders();
            this.modelRegistry = modelRegistry;
            this.om = om;
            this.timeoutSeconds = props.getLlm().getTimeoutSeconds();
        }

        @Override
        public LlmStream stream(String convId, String msgId, String modelId,
                            List<ChatMessage> messages, boolean deepThink, boolean webSearch,
                            String regenerateMessageId) throws Exception {
            // 按 modelId 反查 provider
            String providerName = modelRegistry.providerOf(modelId);
            AppProperties.Provider p = providers.get(providerName);
            if (p == null) throw new IllegalStateException("provider not configured: " + providerName);
            if (p.getApiKey() == null || p.getApiKey().isBlank()) {
                log.warn("provider {} 未配置 api-key，回落 Mock 流", providerName);
                return mockStream(messages);
            }
            return openAiStream(p, modelId, messages);
        }

        private LlmStream openAiStream(AppProperties.Provider p, String modelId,
                                   List<ChatMessage> messages) throws Exception {
            ObjectNode body = om.createObjectNode();
            body.put("model", modelId);
            body.put("stream", true);

             // 应用合并后的参数
            GenerationParams params = modelRegistry.paramsFor(modelId);
            applyParams(body, params);

            ArrayNode msgs = body.putArray("messages");
            for (ChatMessage m : messages) {
                ObjectNode n = msgs.addObject();
                n.put("role", m.role());
                n.put("content", m.content());
            }
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(p.getBaseUrl().replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + p.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)))
                    .build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                String friendly = "LLM HTTP " + resp.statusCode() + ": " + truncate(err, 2000);
                String sse = errorAsSse(friendly);
                return new SseLlmStream(
                        new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), om);
            }
            return new SseLlmStream(resp.body(), om);
        }

        private String truncate(String err, int length){
            return err;
        }

        private String errorAsSse(String message) throws Exception {
            ObjectNode chunk = om.createObjectNode();
            chunk.put("object", "chat.completion.chunk");
            ArrayNode choices = chunk.putArray("choices");
            ObjectNode c = choices.addObject();
            c.put("index", 0);
            c.putObject("delta").put("content", message);
            c.putNull("finish_reason");

            // data 行内容必须是单行；JSON 序列化会把 \n 转义成 \\n，天然满足 SSE 要求
            String data = "data: " + om.writeValueAsString(chunk) + "\n\n";
            String done = "data: [DONE]\n\n";
            return data + done;
        }

        private void applyParams(ObjectNode body, GenerationParams params) {
            if (params == null) {
                body.put("stream", true);
                return;
            }
            if (params.getTemperature() != null) {
                body.put("temperature", params.getTemperature());
            }
            if (params.getTopP() != null) {
                body.put("top_p", params.getTopP());
            }
            if (params.getMaxTokens() != null) {
                body.put("max_tokens", params.getMaxTokens());
            }
            if (params.getTools() != null && !params.getTools().isEmpty()) {
                body.set("tools", om.valueToTree(params.getTools()));
            }
            if (params.getToolChoice() != null) {
                body.set("tool_choice", om.valueToTree(params.getToolChoice()));
            }
            if (params.getChatTemplateKwargs() != null && !params.getChatTemplateKwargs().isEmpty()) {
                body.set("chat_template_kwargs", om.valueToTree(params.getChatTemplateKwargs()));
            }
            if (params.getThinking() != null && !params.getThinking().isEmpty()) {
                body.set("thinking", om.valueToTree(params.getThinking()));
            }

            // stream 强制 true：/chat/completions 本身就是 SSE 端点
            if (params.getStream() != null && !params.getStream()) {
                log.warn("model={} 配置了 stream=false，但 /chat/completions 是 SSE 端点，已强制 true",
                        body.path("model").asText());
            }
            body.put("stream", true);
        }

        private LlmStream mockStream(List<ChatMessage> messages) {
            List<Chunk> chunks = List.of(
                    new Chunk(Chunk.Type.DELTA, "[Mock] 未配置 LLM api-key，这是模拟流。", null, null, 0, 0, 0),
                    new Chunk(Chunk.Type.DELTA, " 你发送了 " + messages.size() + " 条上下文消息。", null, null, 0, 0, 0),
                    new Chunk(Chunk.Type.USAGE, "", null, null, 10, 20, 30)
            );
            return new LlmStream() {
                public void close() {}
                public Iterator<Chunk> iterator() { return chunks.iterator(); }
            };
        }
    }

    /** 把 OpenAI SSE 流解析成 Chunk 的迭代器；close() 会关闭底层连接。 */
    class SseLlmStream implements LlmStream {

        private final BufferedReader reader;
        private final InputStream raw;
        private final ObjectMapper om;
        private boolean finished = false;

        SseLlmStream(InputStream in, ObjectMapper om) {
            this.raw = in;
            this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            this.om = om;
        }

        @Override
        public void close() {
            try { raw.close(); } catch (Exception ignore) {}
        }

        @Override
        public Iterator<Chunk> iterator() {
            return new Iterator<>() {
                private Chunk next;

                @Override
                public boolean hasNext() {
                    if (next != null) return true;
                    if (finished) return false;
                    try {
                        next = readNext();
                        return next != null;
                    } catch (Exception e) {
                        close();
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public Chunk next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    Chunk c = next;
                    next = null;
                    return c;
                }
            };
        }

        private Chunk readNext() throws Exception {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (line.startsWith(":")) continue; // 注释行/心跳
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    finished = true;
                    return null;
                }
                JsonNode node = om.readTree(data);
                if (node.has("usage") && !node.get("usage").isNull()) {
                    JsonNode u = node.get("usage");
                    return new Chunk(Chunk.Type.USAGE, "", null, null,
                            u.path("prompt_tokens").asInt(),
                            u.path("completion_tokens").asInt(),
                            u.path("total_tokens").asInt());
                }
                JsonNode delta = node.path("choices").path(0).path("delta");
                String content = delta.path("content").asText("");
                if (!content.isEmpty()) {
                    return new Chunk(Chunk.Type.DELTA, content, null, null, 0, 0, 0);
                }
            }
            finished = true;
            return null;
        }
    }
}
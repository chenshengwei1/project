package com.virco.agent.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j 
@Component
@RequiredArgsConstructor 
public class OpenAiProtocolAdapter {
    private final ObjectMapper om;

    public String buildRequestBody(LlmProvider p, List<ChatMessage> messages,
                                   List<ToolSchema> tools, double temperature, int maxTokens, boolean thinking) {
        ObjectNode body = om.createObjectNode();
        body.put("model", p.modelId());
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);
        body.put("thinking", thinking);
        body.set("messages", toMessages(messages));
        if (tools != null && !tools.isEmpty()) {
            ArrayNode arr = body.putArray("tools");
            for (ToolSchema s : tools) {
                ObjectNode t = arr.addObject();
                t.put("type", "function");
                ObjectNode f = t.putObject("function");
                f.put("name", s.name());
                f.put("description", s.description());
                f.set("parameters", om.valueToTree(s.parameters()));
            }
        }
        return body.toString();
    }

    public String buildStreamBody(LlmProvider p, List<ChatMessage> messages,
                                  List<ToolSchema> tools, double temperature, int maxTokens, boolean  thinking) {
        ObjectNode body = null;
        try {
            body = (ObjectNode) om.readTree(
                buildRequestBody(p, messages, tools, temperature, maxTokens, thinking));
        } catch (JsonProcessingException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            log.error(e.getMessage(), e);
        } catch (Exception e){
            e.printStackTrace();
            log.error(e.getMessage(), e);
        }

        if (body == null){
            return "";
        }

        body.put("stream", true);
        return body.toString();
    }

    private ArrayNode toMessages(List<ChatMessage> messages) {
        ArrayNode arr = om.createArrayNode();
        for (ChatMessage m : messages) {
            ObjectNode n = arr.addObject();
            n.put("role", m.role());
            if (m.content() != null) n.put("content", m.content());
            if (m.toolCallId() != null) n.put("tool_call_id", m.toolCallId());
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                ArrayNode tcs = n.putArray("tool_calls");
                for (InternalToolCall tc : m.toolCalls()) {
                    ObjectNode t = tcs.addObject();
                    t.put("id", tc.id());
                    t.put("type", "function");
                    ObjectNode f = t.putObject("function");
                    f.put("name", tc.name());
                    f.put("arguments", tc.argumentsJson());
                }
            }
        }
        return arr;
    }

    public ChatResponse parseResponse(JsonNode raw) {
        JsonNode msg = raw.path("choices").path(0).path("message");
        String content = msg.hasNonNull("content") ? msg.get("content").asText() : null;
        List<InternalToolCall> calls = new ArrayList<>();
        JsonNode tcs = msg.path("tool_calls");
        if (tcs.isArray()) {
            for (JsonNode tc : tcs) {
                JsonNode fn = tc.path("function");
                String args = fn.path("arguments").isTextual()
                    ? fn.get("arguments").asText() : fn.path("arguments").toString();
                calls.add(new InternalToolCall(tc.path("id").asText(),
                                               fn.path("name").asText(), args));
            }
        }
        String finish = normalizeFinish(
            raw.path("choices").path(0).path("finish_reason").asText(null));
        return new ChatResponse(content, calls, finish, parseUsage(raw.path("usage")));
    }

    public Usage parseUsage(JsonNode u) {
        if (u == null || u.isMissingNode() || u.isEmpty()) return Usage.ZERO;
        int p = u.path("prompt_tokens").asInt(0);
        int c = u.path("completion_tokens").asInt(0);
        return new Usage(p, c, p + c);
    }

    private String normalizeFinish(String fr) {
        if (fr == null) return "stop";
        return switch (fr) {
            case "tool_calls", "function_call" -> "tool_calls";
            case "length" -> "length";
            case "content_filter" -> "content_filter";
            default -> "stop";
        };
    }

    /** 流式 tool_calls 累积器 */
    public static class ToolCallAccumulator {
        private final Map<Integer, StringBuilder> args = new TreeMap<>();
        private final Map<Integer, String> ids = new HashMap<>();
        private final Map<Integer, String> names = new HashMap<>();

        public void feed(JsonNode delta) {
            for (JsonNode tc : delta.path("tool_calls")) {
                int idx = tc.path("index").asInt(0);
                if (tc.hasNonNull("id")) ids.put(idx, tc.get("id").asText());
                JsonNode fn = tc.path("function");
                if (fn.hasNonNull("name")) names.put(idx, fn.get("name").asText());
                if (fn.hasNonNull("arguments")) {
                    args.computeIfAbsent(idx, k -> new StringBuilder())
                        .append(fn.get("arguments").asText());
                }
            }
        }
        public List<InternalToolCall> finish() {
            return args.keySet().stream()
                .map(i -> new InternalToolCall(ids.get(i), names.get(i), args.get(i).toString()))
                .toList();
        }
        public boolean hasAny() { return !args.isEmpty() || !names.isEmpty(); }
    }
}
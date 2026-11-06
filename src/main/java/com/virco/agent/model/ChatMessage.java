package com.virco.agent.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(String role, String content,
                          List<InternalToolCall> toolCalls, String toolCallId) {
    public static ChatMessage system(String c) { return new ChatMessage("system", c, null, null); }
    public static ChatMessage user(String c)   { return new ChatMessage("user", c, null, null); }
    public static ChatMessage assistant(String c, List<InternalToolCall> tc) {
        return new ChatMessage("assistant", c, tc, null); }
    public static ChatMessage tool(String id, String c) {
        return new ChatMessage("tool", c, null, id); }
}
package com.virco.agent.model;

import java.util.List;

public record ChatResponse(String content, List<InternalToolCall> toolCalls,
                           String finishReason, Usage usage) {
    public boolean hasToolCalls() { 
        return toolCalls != null && !toolCalls.isEmpty(); 
    }
}
package com.virco.agent.react;

import java.util.HashMap;
import java.util.Map;

import com.virco.agent.model.ChatResponse;

import lombok.Data;

@Data
public class LlmContext {
    private ChatResponse response;
    private final long startAt = System.currentTimeMillis();
    private final Map<String, Object> attributes = new HashMap<>();
    // getter/setter
}
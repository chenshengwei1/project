package com.virco.agent.model;

import java.util.Map;

public record ToolSchema(String name, String description,
                         Map<String, Object> parameters) {
                            
                         }

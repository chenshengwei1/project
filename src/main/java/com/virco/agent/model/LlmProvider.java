package com.virco.agent.model;

public record LlmProvider(String name, String modelId, String baseUrl, String apiKey,
                          Integer contextWindow, String protocol) {}
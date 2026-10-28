// config/ChatLlmProperties.java —— 绑定你已有的 chat.llm
package com.virco.agent.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat.llm")
public record ChatLlmProperties(
    String baseUrl, String apiKey, String defaultModel,
    Integer timeoutSeconds, Boolean modelsFetchEnabled, Integer modelsCacheSeconds,
    Defaults defaults,
    Map<String, Provider> providers
) {
    public record Defaults(Double temperature, Double topP, Integer maxTokens) {}
    public record Provider(String baseUrl, String apiKey, List<ModelDef> models,
                           List<ModelDef> imageModels) {}
    public record ModelDef(String id, String name, String description,
                           Integer contextWindow,
                           Boolean supportsDeepThink,
                           Boolean supportsWebSearch,
                           Boolean supportsVision) {}
}
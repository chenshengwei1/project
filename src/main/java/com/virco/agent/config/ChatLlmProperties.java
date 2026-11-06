
package com.virco.agent.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.virco.agent.config.ChatLlmProperties.ModelDef;

@Configuration 
public class ChatLlmProperties {

    public final AppProperties app;
    public ChatLlmProperties(AppProperties app){
        this.app = app;
    }

    // ==================== 顶层字段 ====================

    public String baseUrl() {
        return app.getLlm().getBaseUrl();
    }

    public String apiKey() {
        return app.getLlm().getApiKey();
    }

    public String defaultModel() {
        return app.getLlm().getDefaultModel();
    }

    public Integer timeoutSeconds() {
        return app.getLlm().getTimeoutSeconds();
    }

    public Boolean modelsFetchEnabled() {
        return app.getLlm().isModelsFetchEnabled();
    }

    public Integer modelsCacheSeconds() {
        return (int)app.getLlm().getModelsCacheSeconds();
    }

    // ==================== Defaults ====================

    public Defaults defaults() {
        AppProperties.Llm llm = app.getLlm();
        return new Defaults(
                llm.getDefaults().getTemperature(),
                llm.getDefaults().getTopP(),
                llm.getDefaults().getMaxTokens(),
                new ChatTemplateKwargs(Boolean.TRUE)
        );
    }

    // ==================== Providers ====================

    public Map<String, Provider> providers() {
        return app.getLlm().getProviders().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> toProvider(e.getValue())
                ));
    }

    private Provider toProvider(AppProperties.Provider p) {
        return new Provider(
                p.getBaseUrl(),
                p.getApiKey(),
                toModelDefs(p.getModels()),
                toMediaModelDefs(p.getImageModels())
        );
    }

    private List<ModelDef> toMediaModelDefs(List<AppProperties.MediaModel> models) {
        if (models == null) {
            return List.of();
        }
        return models.stream()
                .map(m -> new ModelDef(
                        m.getId(),
                        m.getName(),
                        m.getDescription(),
                        0,
                        false,
                        false,
                        false
                ))
                .toList();
    }

    private List<ModelDef> toModelDefs(List<AppProperties.Model> models) {
        if (models == null) {
            return List.of();
        }
        return models.stream()
                .map(m -> new ModelDef(
                        m.getId(),
                        m.getName(),
                        m.getDescription(),
                        m.getContextWindow(),
                        m.isSupportsDeepThink(),
                        m.isSupportsWebSearch(),
                        m.isSupportsVision()
                ))
                .toList();
    }

    public record Defaults(Double temperature, Double topP, Integer maxTokens, ChatTemplateKwargs chatTemplateKwargs) {}
    public record Provider(String baseUrl, String apiKey, List<ModelDef> models,
                           List<ModelDef> imageModels) {}
    public record ModelDef(String id, String name, String description,
                           Integer contextWindow,
                           Boolean supportsDeepThink,
                           Boolean supportsWebSearch,
                           Boolean supportsVision) {}
    public record ChatTemplateKwargs(boolean enableThinking){}
}
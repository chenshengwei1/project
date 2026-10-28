package com.virco.agent.config;

import com.virco.agent.config.AppProperties.GenerationParams;

/** 按 defaults → provider → model 顺序合并，字段 null 表示未配置 */
public final class GenerationParamsMerger {
    private GenerationParamsMerger() {}

    public static GenerationParams merge(GenerationParams... layers) {
        GenerationParams result = new GenerationParams();
        for (GenerationParams layer : layers) {
            if (layer == null) continue;
            if (layer.getTemperature() != null) result.setTemperature(layer.getTemperature());
            if (layer.getTopP() != null) result.setTopP(layer.getTopP());
            if (layer.getMaxTokens() != null) result.setMaxTokens(layer.getMaxTokens());
            if (layer.getStream() != null) result.setStream(layer.getStream());
            if (layer.getTools() != null) result.setTools(layer.getTools());
            if (layer.getToolChoice() != null) result.setToolChoice(layer.getToolChoice());
            if (layer.getChatTemplateKwargs() != null) result.setChatTemplateKwargs(layer.getChatTemplateKwargs());
            if (layer.getThinking() != null) result.setThinking(layer.getThinking());
        }
        return result;
    }
}
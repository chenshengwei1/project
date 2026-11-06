package com.virco.agent.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.virco.agent.config.AgentProperties;
import com.virco.agent.config.ChatLlmProperties;
import com.virco.agent.config.ChatLlmProperties.ModelDef;
import com.virco.agent.config.ChatLlmProperties.Provider;

@Component
public class ModelResolver {
    private final ModelRegistry chat;
    private final AgentProperties agent;
    public ModelResolver(ModelRegistry chat, AgentProperties agent) {
        this.chat = chat; this.agent = agent;
    }

    /** 构建有序候选：primary -> backups；每个引用为 "provider:modelId" */
    public List<LlmProvider> candidates() {
        List<LlmProvider> list = new ArrayList<>();
        addRef(list, agent.primary());
        if (agent.backups() != null) agent.backups().forEach(b -> addRef(list, b));
        if (list.isEmpty()) {
            // 没配 primary 时退回 chat.llm.default-model，在 providers 里找
            addByModelId(list, chat.defaultId());
        }
        return list;
    }

    public List<ModelDef> models(){
        Map<String, ModelDef> modelMap = new HashMap<>();
        for (Provider m: this.chat.providers().values()){
            for (ModelDef md: m.models()){
                modelMap.put(md.name(), md);
            }
        }
        return List.copyOf(modelMap.values());
    }

    public List<ModelDef> models(String provider){
        return Optional.ofNullable(this.chat.providers().get(provider))
        .map(Provider::models)
        .map(List::copyOf)
        .orElseGet(List::of);
    }

    private void addRef(List<LlmProvider> list, String ref) {
        if (ref == null || !ref.contains(":")) return;
        String[] p = ref.split(":", 2);
        ChatLlmProperties.Provider prov = chat.providers().get(p[0]);
        if (prov == null) return;
        ChatLlmProperties.ModelDef md = prov.models().stream()
            .filter(m -> m.id().equals(p[1])).findFirst().orElse(null);
        if (md == null) return;
        list.add(toProvider(p[0], prov, md));
    }

    private void addByModelId(List<LlmProvider> list, String modelId) {
        if (modelId == null) return;
        chat.providers().forEach((name, prov) -> prov.models().stream()
            .filter(m -> m.id().equals(modelId)).findFirst()
            .ifPresent(md -> list.add(toProvider(name, prov, md))));
    }

    private LlmProvider toProvider(String name, ChatLlmProperties.Provider prov,
                                   ChatLlmProperties.ModelDef md) {
        return new LlmProvider(name, md.id(), prov.baseUrl(), prov.apiKey(),
                               md.contextWindow(), "openai");
    }
}

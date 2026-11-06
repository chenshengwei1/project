package com.virco.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.virco.agent.entity.ChatDTO;

import jakarta.annotation.PostConstruct;

import com.virco.agent.config.AppProperties;
import com.virco.agent.config.AppProperties.GenerationParams;
import com.virco.agent.config.ChatLlmProperties.ModelDef;
import com.virco.agent.config.ChatLlmProperties.Provider;
import com.virco.agent.config.GenerationParamsMerger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Component
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final AppProperties props;
    private final ObjectMapper om;
    private final HttpClient http;

    private volatile Snapshot snapshot;
    private volatile long snapshotAt;
    private final Object lock = new Object();

    public ModelRegistry(AppProperties props, ObjectMapper om) {
        this.props = props;
        this.om = om;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @PostConstruct 
    public void init(){
        this.refresh();
    }

    // ---------- 对外 API ----------

    public List<ChatDTO.ModelInfo> list() { return snapshot().items(); }


    public Map<String, Provider> providers() {
        return this.props.getLlm().getProviders().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> toProvider(e.getKey(), e.getValue())
                ));
    }

    private Provider toProvider(String pName, AppProperties.Provider p) {
        return new Provider(
                p.getBaseUrl(),
                p.getApiKey(),
                toModelDefs(snapshot().items().stream().filter(m -> m.provider() == pName).toList()),
                List.of()
        );
    }

    private List<ModelDef> toModelDefs(List<ChatDTO.ModelInfo> models) {
        if (models == null) {
            return List.of();
        }
        return models.stream()
                .map(m -> new ModelDef(
                        m.id(),
                        m.name(),
                        m.description(),
                        m.contextWindow(),
                        m.supportsDeepThink(),
                        m.supportsWebSearch(),
                        m.supportsVision()
                ))
                .toList();
    }

    public String defaultId() { return snapshot().defaultId(); }

    public boolean exists(String modelId) {
        return modelId != null && snapshot().byId().containsKey(modelId);
    }

    public String providerOf(String modelId) {
        String p = snapshot().modelToProvider().get(modelId);
        if (p == null) throw new IllegalArgumentException("unknown model: " + modelId);
        return p;
    }

    /** 管理接口调用的强制刷新 */
    public void refresh() {
        synchronized (lock) {
            snapshot = build();
            snapshotAt = System.currentTimeMillis();
        }
    }

    // ---------- 内部 ----------

    private Snapshot snapshot() {
        long ttl = props.getLlm().getModelsCacheSeconds() * 1000L;
        Snapshot s = snapshot;
        if (s != null && System.currentTimeMillis() - snapshotAt <= ttl) {
            return s;
        }
        synchronized (lock) {
            if (snapshot == null || System.currentTimeMillis() - snapshotAt > ttl) {
                snapshot = build();
                snapshotAt = System.currentTimeMillis();
            }
            return snapshot;
        }
    }

    private Snapshot build() {
        // 1. 静态元数据索引
        Map<String, ChatDTO.ModelInfo> staticMeta = new HashMap<>();
        Map<String, ChatDTO.MediaModelInfo> staticMetaMedia = new HashMap<>();
        props.getLlm().getProviders().forEach((pName, p) -> {
            for (AppProperties.Model m : p.getModels()) {
                staticMeta.put(m.getId(), toInfo(pName, m));
                staticMetaMedia.put(m.getId(), toMediaInfo(m));
            }
        });

        // 2. 合并动态 + 静态
        List<ChatDTO.ModelInfo> all = new ArrayList<>();
        Map<String, ChatDTO.ModelInfo> byId = new HashMap<>();
        Map<String, ChatDTO.MediaModelInfo> byId2 = new HashMap<>();
        Map<String, ChatDTO.MediaModelInfo> byId3 = new HashMap<>();
        Map<String, String> modelToProvider = new HashMap<>();
        Map<String, String> mediaModelToProvider = new HashMap<>();
        boolean fetchEnabled = props.getLlm().isModelsFetchEnabled();

        GenerationParams globalDefaults = props.getLlm().getDefaults();
        Map<String, GenerationParams> modelParams = new HashMap<>();

        props.getLlm().getProviders().forEach((pName, p) -> {
            List<String> ids = fetchEnabled ? fetchModelIds(pName, p) : List.of();
            if (ids.isEmpty()) {
                // 回退：完全用 yml 配置
                for (AppProperties.Model m : p.getModels()) {
                    if (byId.containsKey(m.getId())) {
                        log.warn("模型 id 冲突：{} 已属于 provider={}，跳过 provider={}",
                                m.getId(), modelToProvider.get(m.getId()), pName);
                        continue;
                    }
                    ChatDTO.ModelInfo info = staticMeta.get(m.getId());
                    all.add(info);
                    byId.put(info.id(), info);
                    modelToProvider.put(info.id(), pName);
                }
                for (AppProperties.MediaModel m: p.getImageModels()){
                    ChatDTO.MediaModelInfo info = staticMetaMedia.get(m.getId());
                    byId2.put(info.id(), info);
                    mediaModelToProvider.put(m.getId(), pName);
                }
                for (AppProperties.MediaModel m: p.getVideoModels()){
                    ChatDTO.MediaModelInfo info = staticMetaMedia.get(m.getId());
                    byId3.put(info.id(), info);
                    mediaModelToProvider.put(m.getId(), pName);
                }
                return;
            }
            for (String id : ids) {
                if (byId.containsKey(id)) {
                    log.warn("模型 id 冲突：{} 已属于 provider={}，跳过 provider={}",
                            id, modelToProvider.get(id), pName);
                    continue;
                }
                ChatDTO.ModelInfo info = staticMeta.getOrDefault(id,
                        new ChatDTO.ModelInfo(pName, id, id, null, null, false, false, false));
                all.add(info);
                byId.put(id, info);
                modelToProvider.put(id, pName);
            }

            for (AppProperties.Model m : p.getModels()) {
                // 每个模型预计算合并后的参数
                modelParams.put(m.getId(),
                        GenerationParamsMerger.merge(globalDefaults, p.getParams(), m.getParams()));
            }
            // 动态拉到的模型若在 yml 中无对应 Model 条目，用 provider.params 合并
            for (String dynamicId : ids/* 动态列表 */) {
                if (!modelParams.containsKey(dynamicId)) {
                    modelParams.put(dynamicId,
                            GenerationParamsMerger.merge(globalDefaults, p.getParams(), null));
                }
            }
        });

        // 3. defaultId 兜底
        String def = props.getLlm().getDefaultModel();
        if (def == null || !byId.containsKey(def)) {
            String fallback = all.isEmpty() ? null : all.get(0).id();
            log.warn("default-model={} 不在可用模型列表中，回退到 {}", def, fallback);
            def = fallback;
        }

        String defImage = props.getLlm().getDefaultImageModel();
        String defVideo = props.getLlm().getDefaultVideoModel();

        log.info("模型清单加载完成：共 {} 个，default={}", all.size(), def);
        return new Snapshot(List.copyOf(all), 
            Map.copyOf(byId), 
            Map.copyOf(modelToProvider), 
            modelParams,
            def, 
            new ArrayList<ChatDTO.MediaModelInfo>(byId2.values()), 
            new ArrayList<ChatDTO.MediaModelInfo>(byId3.values()), 
            Map.copyOf(mediaModelToProvider), 
            defImage, 
            defVideo);
    }

    private List<String> fetchModelIds(String providerName, AppProperties.Provider p) {
        if (p.getBaseUrl() == null || p.getBaseUrl().isBlank()) {
            log.debug("provider={} 未配置 base-url，跳过 /models", providerName);
            return List.of();
        }
        String url = p.getBaseUrl().replaceAll("/+$", "") + "/models";
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(props.getLlm().getModelsFetchTimeoutSeconds()))
                    .GET();
            if (p.getApiKey() != null && !p.getApiKey().isBlank()) {
                b.header("Authorization", "Bearer " + p.getApiKey());
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("provider={} 拉 /models 失败：HTTP {}，回退到 yml 静态列表",
                        providerName, resp.statusCode());
                return List.of();
            }
            List<String> ids = extractModelIds(om.readTree(resp.body()));
            log.info("provider={} 从 /models 拉到 {} 个模型", providerName, ids.size());
            return ids;
        } catch (Exception e) {
            log.warn("provider={} 拉 /models 异常：{}，回退到 yml 静态列表",
                    providerName, e.getMessage());
            return List.of();
        }
    }

    public GenerationParams paramsFor(String modelId) {
        GenerationParams p = snapshot().modelParams().get(modelId);
        if (p == null) {
            // 兜底：完全用 defaults
            return GenerationParamsMerger.merge(props.getLlm().getDefaults());
        }
        return p;
    }

    /** 兼容 OpenAI / Anthropic / 纯数组三种格式 */
    private List<String> extractModelIds(JsonNode root) {
        JsonNode arr = root.path("data");
        if (!arr.isArray()) arr = root.path("models");
        if (!arr.isArray()) arr = root;
        if (!arr.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String id = n.path("id").asText("");
            if (id.isEmpty()) id = n.path("name").asText("");
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    private ChatDTO.ModelInfo toInfo(String provider, AppProperties.Model m) {
        return new ChatDTO.ModelInfo(provider, m.getId(), m.getName(), m.getDescription(),
                m.getContextWindow(), m.isSupportsDeepThink(),
                m.isSupportsWebSearch(), m.isSupportsVision());
    }

    private ChatDTO.MediaModelInfo toMediaInfo(AppProperties.Model m){
        return new ChatDTO.MediaModelInfo(m.getId(), m.getName(), m.getDescription(), null);
    }

    private record Snapshot(
            List<ChatDTO.ModelInfo> items,
            Map<String, ChatDTO.ModelInfo> byId,
            Map<String, String> modelToProvider,
            Map<String, GenerationParams> modelParams,   // 新增
            String defaultId,
            List<ChatDTO.MediaModelInfo> imageItems,
            List<ChatDTO.MediaModelInfo> videoItems,
            Map<String, String> mediaModelToProvider,
            String defaultImageId,
            String defaultVideoId
    ) {}

    public List<ChatDTO.MediaModelInfo> listImages() { return snapshot().imageItems(); }
    public List<ChatDTO.MediaModelInfo> listVideos() { return snapshot().videoItems(); }
    public String defaultImageId() { return snapshot().defaultImageId(); }
    public String defaultVideoId() { return snapshot().defaultVideoId(); }

    public String providerOfMedia(String modelId) {
        String p = snapshot().mediaModelToProvider().get(modelId);
        if (p == null) throw new IllegalArgumentException("unknown media model: " + modelId);
        return p;
    }

    public boolean isMediaModel(String modelId) {
        return snapshot().mediaModelToProvider().containsKey(modelId);
    }
}
package com.virco.agent.config;

import lombok.Data;
import lombok.ToString;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "chat")
@Component 
public class AppProperties {

    private Auth auth = new Auth();
    private Upload upload = new Upload();
    private DataJson data = new DataJson();
    private Llm llm = new Llm();

    @Data
    public static class Auth {
        private String secret = "";
        private long tokenTtlMinutes = 1440;
        private String token = "";   // 可选：开发直通 token，配了就能绕过登录
    }

    @Data
    public static class Upload {
        private String dir = "./data/uploads";
        private String urlPrefix = "/uploads";
    }

    @Data
    public static class DataJson {
        private String dir = "./data/json";
    }

    @Data
    public static class Llm {
        private String baseUrl;
        private String apiKey = "";
        private String defaultModel = "gpt-4o-mini";
        private int timeoutSeconds = 300;

         /** 是否启用 /models 动态拉取，默认 true；设 false 则完全用 yml 静态配置 */
        private boolean modelsFetchEnabled = true;
        /** /models 结果缓存秒数 */
        private long modelsCacheSeconds = 300;
        /** /models 请求超时秒数 */
        private int modelsFetchTimeoutSeconds = 10;

        private String defaultImageModel;   // 新增
        private String defaultVideoModel;   // 新增

        /** 全局默认生成参数，可被 provider / model 覆盖 */
        private GenerationParams defaults = new GenerationParams();

        /** provider 名 → 配置；key 就是 provider id，例如 "openai"、"deepseek" */
        private Map<String, Provider> providers = new LinkedHashMap<>();
    }

    @Data
    public static class Provider {
        private String baseUrl;
        private String audioUrl;
        private String imageUrl;
        private String vedioUrl;
        @ToString.Exclude
        private String apiKey;
        /** provider 级覆盖；null 表示完全沿用 defaults */
        private GenerationParams params;
        private List<Model> models = new ArrayList<>();
        private List<MediaModel> imageModels = new ArrayList<>(); // 新增：图片
        private List<MediaModel> videoModels = new ArrayList<>(); // 新增：视频
    }

    /** 新增：图片/视频模型元数据 */
    @Data
    public static class MediaModel {
        private String id;              // 发给上游的模型名，如 "wan2.6-t2i"、"kling-video-o1"
        private String name;            // 前端展示名
        private String description;
        private String modality;        // "image" | "video"，由所属列表自动推断，可不填
    }

    @Data
    public static class Model {
        private String id;
        private String name;
        private String description;
        private Integer contextWindow;
        private boolean supportsDeepThink;
        private boolean supportsWebSearch;
        private boolean supportsVision;
        /** 该模型专属参数；null 表示沿用 provider / defaults */
        private GenerationParams params;
        private boolean supportsVideo = false;
        private boolean supportsImage = false;;
        private boolean supportsVoice = false;;
    }

    /** 生成参数；每个字段 null 表示"未配置，沿用下层" */
    @Data
    public static class GenerationParams {
        /** 0.0 ~ 2.0，越低越确定 */
        private Double temperature;
        /** 0.0 ~ 1.0，核采样阈值 */
        private Double topP;
        /** 响应最大 token 数，映射到请求体的 max_tokens */
        private Integer maxTokens;
        /**
         * 是否流式。当前 /chat/completions 为 SSE 端点，代码强制 true。
         * 配 false 只打 warn，不会改变行为；要非流式请另开同步端点。
         */
        private Boolean stream;
        /** 工具定义（OpenAI tools 格式），直接透传 */
        private List<Map<String, Object>> tools;
        /** "auto" / "none" / "required" / {type:function, function:{name:xxx}} */
        private Object toolChoice;
        /** 透传 chat_template_kwargs，如 {enable_thinking: true} */
        private Map<String, Object> chatTemplateKwargs;
        /** 透传 thinking，如 {type: enabled, budget_tokens: 8192} */
        private Map<String, Object> thinking;
    }
}
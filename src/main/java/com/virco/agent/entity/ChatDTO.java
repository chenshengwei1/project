package com.virco.agent.entity;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 本文件集中定义该 API 用到的所有 DTO，Controller 只需做参数绑定和调用 Service。
 * Controller 中建议:
 *   import static com.example.chat.api.ChatDTO.*;
 * 或者使用 ChatDTO.MeResponse 等全限定名。
 *
 * 约定:
 *  - 成功响应直接返回对应 DTO（record 由 Jackson 序列化）。
 *  - 失败响应统一由全局异常处理器返回 ErrorResponse。
 *  - SSE 事件统一实现 SseEvent，JSON 内的 type 字段用于前端分发。
 */
public final class ChatDTO {

    private ChatDTO() {
    }

    // ==================== 用户 / 模型 / 偏好 ====================

    // 加到 ChatDTO 里
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record LoginResponse(String accessToken, String userId, String name) {}

    /** GET /me 的响应 */
    public record MeResponse(
            String id,
            String name,
            String avatarUrl, // 为 null 时前端用昵称首字生成色块
            String email
    ) {
    }

    /**
     * 单个模型信息。文档仅写“结构见类型定义”，这里给出常见字段；
     * 若后端实际字段不同，直接调整本 record 即可。
     */
    public record ModelInfo(
            String id,
            String name,
            String description,
            Integer contextWindow,
            boolean supportsDeepThink,
            boolean supportsWebSearch,
            boolean supportsVision
    ) {
    }

    // ---------- 媒体模型 ----------
        public record MediaModelInfo(String id, String name, String description, String modality) {}

        public record MediaModelListResponse(List<MediaModelInfo> items, String defaultId) {}

        // ---------- 媒体生成任务 ----------
        public record CreateImageTaskRequest(
                @NotBlank String prompt,
                String modelId,                    // 不传用 default-image-model
                String size,                      // 如 "1024x1024"、"1280*1280"
                String negativePrompt,
                @Min(1) @Max(4) Integer n
        ) {}

        public record CreateVideoTaskRequest(
                @NotBlank String prompt,
                String modelId,                    // 不传用 default-video-model
                Integer duration,                  // 秒
                String aspectRatio,                // "16:9"、"9:16"、"1:1"
                String resolution,                 // "720p"、"1080p"、"4k"
                String imageUrl,                   // 图生视频时的首帧
                String negativePrompt
        ) {}

        public record MediaTask(
                String taskId,                     // 系统内部任务 ID（不是上游的）
                String upstreamTaskId,             // 上游返回的 task_id
                String type,                       // "image" | "video"
                String modelId,
                String provider,
                String status,                     // "submitted" | "processing" | "succeeded" | "failed" | "canceled"
                String prompt,
                List<MediaOutput> outputs,         // 成功时填充
                String errorCode,
                String errorMessage,
                Instant createdAt,
                Instant updatedAt
        ) {}

        public record MediaOutput(String url, String mimeType, Integer width, Integer height, Integer durationSeconds) {}

        public record MediaTaskPage(List<MediaTask> items, int page, int size, long total, boolean hasMore) {}

    /** GET /models 的响应 */
    public record ModelListResponse(
            List<ModelInfo> items,
            String defaultId
    ) {
    }

    /** GET /preferences 的响应，与 PUT 合并后的完整对象 */
    public record Preferences(
            String modelId,
            boolean deepThink,
            boolean webSearch,
            String theme // "light" | "dark"
    ) {
    }

    /** PUT /preferences 的请求，所有字段可选，只传需要修改的 */
    public record UpdatePreferencesRequest(
            String modelId,
            Boolean deepThink,
            Boolean webSearch,
            @Pattern(regexp = "light|dark", message = "theme must be light or dark")
            String theme
    ) {
    }

    // ==================== 搜索命中的高亮信息 ====================

    /**
     * 搜索命中时附带的匹配信息。
     * match.field === "title" 时前端高亮标题，否则展示 snippet 并高亮 highlights。
     */
    public record Match(
            String field,        // "title" | "content" | "all"
            String snippet,
            List<String> highlights
    ) {
    }

    // ==================== 会话 ====================

    /** 会话对象，列表里可能携带 match 字段 */
    public record Conversation(
            String id,
            String title,
            String modelId,
            long messageCount,
            Instant createdAt,
            Instant updatedAt,
            Match match
    ) {
    }

    /** GET /conversations 的响应 */
    public record ConversationPage(
            List<Conversation> items,
            int page,
            int size,
            long total,
            boolean hasMore,
            String keyword // 搜索时原样返回关键词，供前端高亮
    ) {
    }

    /** POST /conversations 的请求 */
    public record CreateConversationRequest(
            @Size(max = 50) String title,
            String modelId
    ) {
    }

    /** PATCH /conversations/:id 的请求 */
    public record RenameConversationRequest(
            @NotBlank @Size(min = 1, max = 50) String title
    ) {
    }

    // ==================== 文件 / 附件 ====================

    /**
     * 附件对象。文档中 upload 响应、消息 attachments、GET /files/:id 都用它。
     * parseStatus 仅文档类有意义：pending / parsing / success / failed。
     */



    public record Attachment(
            String id,
            String fileName,
            long size,
            String mimeType,
            String url,
            String kind,        // "image" | "document" | "other"（可调整）
            String parseStatus
    ) {
    }

    /** POST /files 的响应 */
    public record UploadFilesResponse(
            List<Attachment> items
    ) {
    }

    // ==================== 消息 ====================

    /** 单条消息，用户消息与 AI 消息共用 */
    public record Message(
            String id,
            String conversationId,
            String role,             // "user" | "assistant" | "system"
            String content,
            List<Attachment> attachments,
            Integer feedback,        // 1 赞 / -1 踩 / 0 取消 / null 未评价
            String status,           // "normal" | "streaming" | "error" | "interrupted"
            String reasoningContent, // 深度思考内容，非 deepThink 时为 null
            TokenUsage usage,        // AI 消息的 token 用量，可选
            Instant createdAt,
            Match match              // 搜索命中时可能有值
    ) {
    }

    /** GET /conversations/:id/messages 的响应 */
    public record MessagePage(
            List<Message> items,
            int page,
            int size,
            long total,
            boolean hasMore
    ) {
    }

    /** POST /conversations/:id/messages 的请求 */
    public record SendMessageRequest(
            @NotNull String content,          // 有附件时可为空字符串
            @Size(max = 10) List<String> attachmentIds
    ) {
    }

    /** PATCH /messages/:id 的请求，attachmentIds 传空数组表示清空附件 */
    public record EditMessageRequest(
            String content,
            @Size(max = 10) List<String> attachmentIds
    ) {
    }

    /** PATCH /messages/:id 的响应 */
    public record EditMessageResponse(
            Message message,
            int deletedMessageCount,
            Conversation conversation
    ) {
    }

    /** PATCH /messages/:id/feedback 的请求 */
    public record FeedbackRequest(
            @NotNull @Min(-1) @Max(1) Integer feedback
    ) {
    }

    // ==================== AI 流式对话请求 ====================

    /** POST /chat/completions 的请求 */
    public record ChatCompletionRequest(
            @NotBlank String conversationId,
            String content,
            @Size(max = 10) List<String> attachmentIds,
            @NotBlank String modelId,
            Boolean deepThink,
            Boolean webSearch,
            String regenerateMessageId
    ) {
    }

    // ==================== SSE 事件 ====================

    /**
     * SSE 事件统一接口。SseEmitter 发送时:
     *   emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
     * Jackson 序列化后事件 JSON 里会包含 type 字段，前端按 type 分发。
     */
    public interface SseEvent {
        @JsonProperty("type")
        String type();
    }

    /** meta：收到后才创建 AI 占位消息 */
    public record MetaEvent(
            String conversationId,
            String messageId,   // AI 占位消息 ID
            String modelId,
            boolean deepThink,
            boolean webSearch,
            Instant createdAt
    ) implements SseEvent {
        @Override
        public String type() {
            return "meta";
        }
    }

    /** reasoning：深度思考增量 */
    public record ReasoningEvent(
            String content
    ) implements SseEvent {
        @Override
        public String type() {
            return "reasoning";
        }
    }

    /** 单条搜索结果 */
    public record SearchResult(
            String title,
            String url,
            String snippet
    ) {
    }

    /** search：联网搜索事件 */
    public record SearchEvent(
            List<String> queries,
            List<SearchResult> results
    ) implements SseEvent {
        @Override
        public String type() {
            return "search";
        }
    }

    /** delta：正文增量 */
    public record DeltaEvent(
            String content
    ) implements SseEvent {
        @Override
        public String type() {
            return "delta";
        }
    }

    /** token 用量 */
    public record TokenUsage(
            int promptTokens,
            int completionTokens,
            int totalTokens
    ) {
    }

    /** usage：token 用量事件 */
    public record UsageEvent(
            int promptTokens,
            int completionTokens,
            int totalTokens
    ) implements SseEvent {
        @Override
        public String type() {
            return "usage";
        }
    }

    /** done：用 message 覆盖本地 AI 消息，保证与后端一致 */
    public record DoneEvent(
            Message message
    ) implements SseEvent {
        @Override
        public String type() {
            return "done";
        }
    }

    /** error：流式过程中的错误 */
    public record ErrorEvent(
            String code,
            String message,
            Map<String, Object> details
    ) implements SseEvent {
        @Override
        public String type() {
            return "error";
        }
    }

    // ==================== 统一错误响应 ====================

    /**
     * 4xx / 5xx 统一错误体:
     * {"error":{"code":"...","message":"...","details":{}}}
     * 由全局异常处理器返回，Controller 不直接构造它。
     */
    public record ErrorResponse(
            ErrorBody error
    ) {
    }

    public record ErrorBody(
            String code,
            String message,
            Map<String, Object> details
    ) {
    }
}
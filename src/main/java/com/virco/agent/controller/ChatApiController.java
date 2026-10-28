package com.virco.agent.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.virco.agent.service.ChatAppService;
import com.virco.agent.entity.ChatDTO.*;

import java.util.List;

@RestController
@Validated
@RequestMapping
public class ChatApiController {

    private final ChatAppService service;

    public ChatApiController(ChatAppService service) {
        this.service = service;
    }

    // ---------- 用户 / 模型 / 偏好 ----------
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return service.login(req);
    }

    @GetMapping("/me")
    public MeResponse me(
            @RequestHeader(value = "Authorization", required = false) String token) {
        return service.me(token);
    }

    @GetMapping("/models")
    public ModelListResponse models(
            @RequestHeader(value = "Authorization", required = false) String token) {
        return service.models(token);
    }

    @GetMapping("/preferences")
    public Preferences getPreferences(
            @RequestHeader(value = "Authorization", required = false) String token) {
        return service.getPreferences(token);
    }

    @PutMapping("/preferences")
    public Preferences updatePreferences(
            @RequestHeader(value = "Authorization", required = false) String token,
            @Valid @RequestBody UpdatePreferencesRequest request) {
        return service.updatePreferences(token, request);
    }

    // ---------- 会话 ----------

    @GetMapping("/conversations")
    public ConversationPage listConversations(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "all") String scope) {
        return service.listConversations(token, page, size, q, scope);
    }

    @PostMapping("/conversations")
    public Conversation createConversation(
            @RequestHeader(value = "Authorization", required = false) String token,
            @Valid @RequestBody CreateConversationRequest request) {
        return service.createConversation(token, request);
    }

    @PatchMapping("/conversations/{id}")
    public Conversation renameConversation(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable String id,
            @Valid @RequestBody RenameConversationRequest request) {
        return service.renameConversation(token, id, request);
    }

    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConversation(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable String id) {
        service.deleteConversation(token, id);
    }

    @DeleteMapping("/conversations")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearConversations(
            @RequestHeader(value = "Authorization", required = false) String token) {
        service.clearConversations(token);
    }

    // ---------- 消息 ----------

    @GetMapping("/conversations/{id}/messages")
    public MessagePage listMessages(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable String id,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "30") @Min(1) int size,
            @RequestParam(required = false) String before) {
        return service.listMessages(token, id, page, size, before);
    }

    @PostMapping("/conversations/{id}/messages")
    public Message sendMessage(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable String id,
            @Valid @RequestBody SendMessageRequest request) {
        return service.sendMessage(token, id, request);
    }

    @PatchMapping("/messages/{id}")
    public EditMessageResponse editMessage(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable String id,
            @Valid @RequestBody EditMessageRequest request) {
        return service.editMessage(token, id, request);
    }

    @PatchMapping("/messages/{id}/feedback")
    public Message feedback(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable String id,
            @Valid @RequestBody FeedbackRequest request) {
        return service.feedback(token, id, request);
    }

    // ---------- AI 流式回复 SSE ----------

    @PostMapping(value = "/chat/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatCompletions(
            @RequestHeader(value = "Authorization", required = false) String token,
            @Valid @RequestBody ChatCompletionRequest request) {
        return service.chatCompletions(token, request);
    }

    // ---------- 文件 ----------

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadFilesResponse uploadFiles(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestParam("file") @Size(max = 10) List<MultipartFile> files,
            @RequestParam("purpose") String purpose) {
        return service.uploadFiles(token, files, purpose);
    }

    @GetMapping("/files/{id}")
    public Attachment getFile(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable String id) {
        return service.getFile(token, id);
    }

    @DeleteMapping("/files/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFile(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable String id) {
        service.deleteFile(token, id);
    }

    // ---------- 媒体模型 ----------
    @GetMapping("/models/images")
    public MediaModelListResponse listImageModels(
            @RequestHeader(value = "Authorization", required = false) String token) {
        return service.listImageModels(token);
    }

    @GetMapping("/models/videos")
    public MediaModelListResponse listVideoModels(
            @RequestHeader(value = "Authorization", required = false) String token) {
        return service.listVideoModels(token);
    }

    // ---------- 任务提交 ----------
    @PostMapping("/images/generations")
    public MediaTask createImageTask(
            @RequestHeader(value = "Authorization", required = false) String token,
            @Valid @RequestBody CreateImageTaskRequest req) {
        return service.createImageTask(token, req);
    }

    @PostMapping("/videos/generations")
    public MediaTask createVideoTask(
            @RequestHeader(value = "Authorization", required = false) String token,
            @Valid @RequestBody CreateVideoTaskRequest req) {
        return service.createVideoTask(token, req);
    }

    // ---------- 任务查询 ----------
    @GetMapping("/media/tasks/{taskId}")
    public MediaTask getMediaTask(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable String taskId) {
        return service.getMediaTask(token, taskId);
    }

    // ---------- 任务列表（可选，便于前端“我的生成记录”） ----------
    @GetMapping("/media/tasks")
    public MediaTaskPage listMediaTasks(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String type) {   // "image" | "video"
        return service.listMediaTasks(token, page, size, type);
    }
}
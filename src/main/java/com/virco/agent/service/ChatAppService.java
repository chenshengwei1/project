package com.virco.agent.service;


import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.virco.agent.entity.ChatDTO.*;

import java.util.List;

public interface ChatAppService {

    MeResponse me(String token);

    ModelListResponse models(String token);

    Preferences getPreferences(String token);

    Preferences updatePreferences(String token, UpdatePreferencesRequest request);

    ConversationPage listConversations(String token, int page, int size, String q, String scope);

    Conversation createConversation(String token, CreateConversationRequest request);

    Conversation renameConversation(String token, String id, RenameConversationRequest request);

    void deleteConversation(String token, String id);

    void clearConversations(String token);

    MessagePage listMessages(String token, String conversationId, int page, int size, String before);

    Message sendMessage(String token, String conversationId, SendMessageRequest request);

    EditMessageResponse editMessage(String token, String messageId, EditMessageRequest request);

    Message feedback(String token, String messageId, FeedbackRequest request);

    SseEmitter chatCompletions(String token, ChatCompletionRequest request);

    UploadFilesResponse uploadFiles(String token, List<MultipartFile> files, String purpose);

    Attachment getFile(String token, String id);

    void deleteFile(String token, String id);

    LoginResponse login(LoginRequest req);

    MediaModelListResponse listImageModels(String token);
    MediaModelListResponse listVideoModels(String token);
    MediaTask createImageTask(String token, CreateImageTaskRequest req);
    MediaTask createVideoTask(String token, CreateVideoTaskRequest req);
    MediaTask getMediaTask(String token, String taskId);
    MediaTaskPage listMediaTasks(String token, int page, int size, String type);
}
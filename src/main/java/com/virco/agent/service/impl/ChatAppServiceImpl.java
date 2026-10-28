package com.virco.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.virco.agent.entity.ChatDTO;
import com.virco.agent.entity.ChatDTO.*;
import com.virco.agent.exception.ApiException;
import com.virco.agent.auth.AuthSupport;
import com.virco.agent.model.LlmClient;
import com.virco.agent.model.ModelRegistry;
import com.virco.agent.service.impl.*;
import com.virco.agent.service.impl.FileStorage;

import lombok.RequiredArgsConstructor;

import com.virco.agent.service.ChatAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor 
public class ChatAppServiceImpl implements ChatAppService {

    private static final Logger log = LoggerFactory.getLogger(ChatAppServiceImpl.class);
    private static final String DEFAULT_TITLE = "新对话";
    private static final int AUTO_TITLE_LEN = 22;
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private static final int MAX_FILE_COUNT = 10;

    private final AuthSupport auth;
    private final ConversationRepository convRepo;
    private final MessageRepository msgRepo;
    private final AttachmentRepository attRepo;
    private final PreferencesRepository prefsRepo;
    private final ModelRegistry modelRegistry;
    private final LlmClient llm;
    private final FileStorage files;
    private final ObjectMapper om;
    private final ExecutorService sseExecutor;
    private final ScheduledExecutorService heartbeat;
    private final MediaTaskRepository mediaTaskRepo;

    @Qualifier("mediaExecutor")
    private ExecutorService mediaExecutor;

    // ---------- 用户/模型/偏好 ----------

    
    @Override
    public LoginResponse login(LoginRequest req) {
        return auth.login(req);
    }

    @Override public MeResponse me(String token) {
        AuthSupport.User u = auth.requireUser(token);
        return new MeResponse(u.id(), u.name(), u.avatarUrl(), u.email());
    }

    @Override public ModelListResponse models(String token) {
        auth.requireUser(token);
        return new ModelListResponse(modelRegistry.list(), modelRegistry.defaultId());
    }

    @Override public Preferences getPreferences(String token) {
        AuthSupport.User u = auth.requireUser(token);
        return prefsRepo.findOrDefault(u.id(), modelRegistry.defaultId());
    }

    @Override public Preferences updatePreferences(String token, UpdatePreferencesRequest req) {
        AuthSupport.User u = auth.requireUser(token);
        Preferences cur = prefsRepo.findOrDefault(u.id(), modelRegistry.defaultId());
        Preferences merged = new Preferences(
                req.modelId() != null ? req.modelId() : cur.modelId(),
                req.deepThink() != null ? req.deepThink() : cur.deepThink(),
                req.webSearch() != null ? req.webSearch() : cur.webSearch(),
                req.theme() != null ? req.theme() : cur.theme());
        prefsRepo.save(u.id(), merged);
        return merged;
    }

    // ---------- 会话 ----------
    @Override public ConversationPage listConversations(String token, int page, int size, String q, String scope) {
        AuthSupport.User u = auth.requireUser(token);
        boolean searching = q != null && !q.isBlank();
        return convRepo.page(u.id(), page, size, searching ? q.trim() : null, searching ? scope : null);
    }

    @Override public Conversation createConversation(String token, CreateConversationRequest req) {
        AuthSupport.User u = auth.requireUser(token);
        String title = (req.title() == null || req.title().isBlank()) ? DEFAULT_TITLE : req.title().trim();
        String modelId = req.modelId() != null ? req.modelId() : modelRegistry.defaultId();
        if (!modelRegistry.exists(modelId)) throw ApiException.invalidParam("unknown model: " + modelId);
        return convRepo.create(u.id(), title, modelId);
    }

    @Override public Conversation renameConversation(String token, String id, RenameConversationRequest req) {
        AuthSupport.User u = auth.requireUser(token);
        requireOwnedConv(u.id(), id);
        String title = req.title().trim();
        if (title.isEmpty()) throw ApiException.invalidParam("title must not be blank");
        return convRepo.updateTitle(id, title);
    }

    @Override public void deleteConversation(String token, String id) {
        AuthSupport.User u = auth.requireUser(token);
        requireOwnedConv(u.id(), id);
        convRepo.deleteById(id);
    }

    @Override public void clearConversations(String token) {
        AuthSupport.User u = auth.requireUser(token);
        convRepo.deleteAllByOwner(u.id());
    }

    // ---------- 消息 ----------
    @Override public MessagePage listMessages(String token, String cid, int page, int size, String before) {
        AuthSupport.User u = auth.requireUser(token);
        requireOwnedConv(u.id(), cid);
        return msgRepo.pageByConversation(cid, page, size, before);
    }

    @Override public Message sendMessage(String token, String cid, SendMessageRequest req) {
        AuthSupport.User u = auth.requireUser(token);
        Conversation conv = requireOwnedConv(u.id(), cid);
        List<Attachment> attachments = requireAttachments(u.id(), req.attachmentIds());
        if ((req.content() == null || req.content().isEmpty()) && attachments.isEmpty()) {
            throw ApiException.invalidParam("content and attachments are both empty");
        }
        Message msg = msgRepo.insertUserMessage(cid, u.id(), req.content(), attachments);
        if (isDefaultTitle(conv.title()) && req.content() != null && !req.content().isBlank()) {
            convRepo.updateTitle(cid, autoTitle(req.content()));
        }
        convRepo.bumpUpdatedAt(cid, msgRepo.listAllByConversation(cid).size());
        return msg;
    }

    @Override public EditMessageResponse editMessage(String token, String messageId, EditMessageRequest req) {
        AuthSupport.User u = auth.requireUser(token);
        Message origin = msgRepo.findUserMessageOwned(messageId, u.id())
                .orElseThrow(() -> ApiException.notFound("message not found"));
        Conversation conv = requireOwnedConv(u.id(), origin.conversationId());
        List<Attachment> attachments = requireAttachments(u.id(), req.attachmentIds());
        String newContent = req.content() != null ? req.content() : origin.content();
        if ((newContent == null || newContent.isEmpty()) && attachments.isEmpty()) {
            throw ApiException.invalidParam("content and attachments are both empty");
        }
        int deleted = msgRepo.updateAndTruncateAfter(messageId, newContent, attachments);
        if (isDefaultTitle(conv.title()) && newContent != null && !newContent.isBlank()) {
            conv = convRepo.updateTitle(conv.id(), autoTitle(newContent));
        } else {
            conv = convRepo.findById(conv.id()).orElse(conv);
        }
        Message updated = msgRepo.findById(messageId).orElseThrow();
        return new EditMessageResponse(updated, deleted, conv);
    }

    @Override 
    public Message feedback(String token, String messageId, FeedbackRequest req) {
        AuthSupport.User u = auth.requireUser(token);
        Message m = msgRepo.findById(messageId).orElseThrow(() -> ApiException.notFound("message not found"));
        requireOwnedConv(u.id(), m.conversationId());
        if (!"assistant".equals(m.role())) throw ApiException.invalidParam("only assistant messages");
        return msgRepo.updateFeedback(messageId, req.feedback());
    }

    @Override
    public MediaTask createImageTask(String token, CreateImageTaskRequest req) {
        AuthSupport.User u = auth.requireUser(token);
        String modelId = req.modelId() != null ? req.modelId() : modelRegistry.defaultImageId();
        if (modelId == null || !modelRegistry.isMediaModel(modelId)) {
            throw ApiException.invalidParam("unknown or missing image model");
        }
        String provider = modelRegistry.providerOfMedia(modelId);

        MediaTask task = mediaTaskRepo.create(u.id(), "image", modelId, provider, req.prompt());
        // mediaExecutor.submit(() -> runMediaTask(u.id(), task.taskId(), () ->
        //         mediaClient.generateImage(provider, modelId, req)));
        return task;
    }

    // ---------- SSE 流式对话 ----------
    @Override 
    public SseEmitter chatCompletions(String token, ChatCompletionRequest req) {
        AuthSupport.User u = auth.requireUser(token);
        Conversation conv = requireOwnedConv(u.id(), req.conversationId());

        boolean regenerate = req.regenerateMessageId() != null && !req.regenerateMessageId().isBlank();
        if (!regenerate) {
            boolean hasContent = req.content() != null && !req.content().isBlank();
            boolean hasAttach = req.attachmentIds() != null && !req.attachmentIds().isEmpty();
            if (!hasContent && !hasAttach) throw ApiException.invalidParam("content and attachments are both empty");
        }
        if (!modelRegistry.exists(req.modelId())) throw ApiException.invalidParam("unknown model: " + req.modelId());

        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean completedNormally = new AtomicBoolean(false);
        AtomicReference<Future<?>> upstreamRef = new AtomicReference<>();
        AtomicReference<String> assistantMsgIdRef = new AtomicReference<>();
        AtomicReference<StringBuilder> bufferRef = new AtomicReference<>(new StringBuilder());

        ScheduledFuture<?> hb = heartbeat.scheduleAtFixedRate(
                () -> safeSendComment(emitter, "ping"), 15, 15, TimeUnit.SECONDS);

        Runnable onAbort = () -> {
            hb.cancel(true);
            cancelUpstream(upstreamRef);
            if (!completedNormally.get() && assistantMsgIdRef.get() != null) {
                try {
                    msgRepo.finalizeAssistantMessage(assistantMsgIdRef.get(),
                            bufferRef.get().toString(), "interrupted", null);
                } catch (Exception e) { log.warn("mark interrupted failed: {}", e.getMessage()); }
            }
        };
        emitter.onCompletion(onAbort);
        emitter.onError(t -> { log.debug("SSE error: {}", t.getMessage()); onAbort.run(); });
        emitter.onTimeout(() -> { log.debug("SSE timeout"); onAbort.run(); });

        upstreamRef.set(sseExecutor.submit(() ->
                runStream(u, conv, req, emitter, completedNormally, assistantMsgIdRef, bufferRef, regenerate)));
        return emitter;
    }

    private void runStream(AuthSupport.User u, Conversation conv, ChatCompletionRequest req,
                           SseEmitter emitter, AtomicBoolean completedNormally,
                           AtomicReference<String> assistantMsgIdRef,
                           AtomicReference<StringBuilder> bufferRef, boolean regenerate) {
        StringBuilder buffer = bufferRef.get();
        String assistantMsgId = null;
        LlmClient.LlmStream stream = null;
        try {
            if (regenerate) {
                msgRepo.updateAndTruncateAfter(req.regenerateMessageId(), "", List.of());
            }
            assistantMsgId = msgRepo.createAssistantPlaceholder(conv.id(), req.modelId(), "streaming");
            assistantMsgIdRef.set(assistantMsgId);

            send(emitter, new MetaEvent(conv.id(), assistantMsgId, req.modelId(),
                    Boolean.TRUE.equals(req.deepThink()), Boolean.TRUE.equals(req.webSearch()), Instant.now()));

            List<LlmClient.ChatMessage> messages = new ArrayList<>();
            for (Message m : msgRepo.listAllByConversation(conv.id())) {
                if ("assistant".equals(m.role()) && (m.content() == null || m.content().isEmpty())) continue;
                messages.add(new LlmClient.ChatMessage(m.role(), m.content() == null ? "" : m.content()));
            }
            if (regenerate) {
                messages.add(new LlmClient.ChatMessage("user",
                        req.content() != null ? req.content() : "请重新生成上一条回复"));
            }

            stream = llm.stream(conv.id(), assistantMsgId, req.modelId(), messages,
                    Boolean.TRUE.equals(req.deepThink()), Boolean.TRUE.equals(req.webSearch()),
                    req.regenerateMessageId());

            TokenUsage usage = null;
            for (LlmClient.Chunk chunk : stream) {
                switch (chunk.type()) {
                    case REASONING -> send(emitter, new ReasoningEvent(chunk.content()));
                    case SEARCH -> send(emitter, new SearchEvent(chunk.queries(), chunk.results()));
                    case DELTA -> { buffer.append(chunk.content()); send(emitter, new DeltaEvent(chunk.content())); }
                    case USAGE -> {
                        usage = new TokenUsage(chunk.promptTokens(), chunk.completionTokens(), chunk.totalTokens());
                        send(emitter, new UsageEvent(chunk.promptTokens(), chunk.completionTokens(), chunk.totalTokens()));
                    }
                }
            }

            Message finalMsg = msgRepo.finalizeAssistantMessage(assistantMsgId, buffer.toString(), "normal", usage);
            convRepo.bumpUpdatedAt(conv.id(), msgRepo.listAllByConversation(conv.id()).size());
            send(emitter, new DoneEvent(finalMsg));
            completedNormally.set(true);
            emitter.complete();
        } catch (IOException | IllegalStateException gone) {
            log.debug("client disconnected: {}", gone.getMessage());
        } catch (Exception e) {
            log.warn("stream failed", e);
            safeSend(emitter, new ErrorEvent("INTERNAL_ERROR",
                    e.getMessage() == null ? "internal error" : e.getMessage(), Map.of()));
            if (assistantMsgId != null) {
                try { msgRepo.finalizeAssistantMessage(assistantMsgId, buffer.toString(), "error", null); }
                catch (Exception ignore) {}
            }
            completedNormally.set(true);
            try { emitter.completeWithError(e); } catch (Exception ignore) {}
        } finally {
            if (stream != null) stream.close();
        }
    }

    // ---------- 文件 ----------
    @Override public UploadFilesResponse uploadFiles(String token, List<MultipartFile> files, String purpose) {
        AuthSupport.User u = auth.requireUser(token);
        if (!"chat".equals(purpose)) throw ApiException.invalidParam("purpose must be chat");
        if (files == null || files.isEmpty()) throw ApiException.invalidParam("file is required");
        if (files.size() > MAX_FILE_COUNT) throw ApiException.invalidParam("too many files");
        List<Attachment> result = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f.isEmpty()) continue;
            if (f.getSize() > MAX_FILE_SIZE) throw ApiException.fileTooLarge(f.getOriginalFilename());
            String mime = f.getContentType();
            if (!isSupportedMime(mime)) throw ApiException.unsupportedFileType(f.getOriginalFilename(), mime);
            FileStorage.Stored stored = files_store(u.id(), f);
            String kind = mime != null && mime.startsWith("image/") ? "image" : "document";
            String parseStatus = "image".equals(kind) ? "success" : "pending";
            result.add(attRepo.create(u.id(), f.getOriginalFilename(), f.getSize(),
                    mime, stored.url(), kind, parseStatus));
        }
        return new UploadFilesResponse(result);
    }

    @Override public Attachment getFile(String token, String id) {
        AuthSupport.User u = auth.requireUser(token);
        return attRepo.findByIdAndOwner(id, u.id()).orElseThrow(() -> ApiException.notFound("file not found"));
    }

    @Override public void deleteFile(String token, String id) {
        AuthSupport.User u = auth.requireUser(token);
        Attachment a = attRepo.findByIdAndOwner(id, u.id()).orElseThrow(() -> ApiException.notFound("file not found"));
        attRepo.deleteById(id);
        files.deleteQuietly(a.url());
    }

    // ---------- 内部 ----------
    private FileStorage.Stored files_store(String ownerId, MultipartFile file) {
        return fileStorageStore(ownerId, file);
    }
    private FileStorage.Stored fileStorageStore(String ownerId, MultipartFile f) {
        return files.store(ownerId, f);
    }
    private Conversation requireOwnedConv(String ownerId, String cid) {
        return convRepo.findByIdAndOwner(cid, ownerId).orElseThrow(() -> ApiException.notFound("conversation not found"));
    }
    private List<Attachment> requireAttachments(String ownerId, List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        if (ids.size() > MAX_FILE_COUNT) throw ApiException.invalidParam("too many attachments");
        List<Attachment> found = attRepo.findByIdsAndOwner(ids, ownerId);
        if (found.size() != ids.size()) throw ApiException.invalidParam("some attachments not found");
        return found;
    }
    private boolean isDefaultTitle(String title) {
        return title == null || title.isBlank() || DEFAULT_TITLE.equals(title);
    }
    private String autoTitle(String content) {
        String t = content.strip().replaceAll("\\s+", " ");
        if (t.isEmpty()) return DEFAULT_TITLE;
        return t.length() <= AUTO_TITLE_LEN ? t : t.substring(0, AUTO_TITLE_LEN);
    }
    private boolean isSupportedMime(String mime) {
        if (mime == null) return false;
        return mime.startsWith("image/") || mime.equals("application/pdf") || mime.equals("text/plain")
                || mime.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }
    private void send(SseEmitter emitter, SseEvent event) throws IOException {
        String json = om.writeValueAsString(event);
        synchronized (emitter) { emitter.send(SseEmitter.event().data(json)); }
    }
    private void safeSend(SseEmitter emitter, SseEvent event) {
        try { send(emitter, event); } catch (Exception e) { log.debug("send failed: {}", e.getMessage()); }
    }
    private void safeSendComment(SseEmitter emitter, String comment) {
        try { synchronized (emitter) { emitter.send(SseEmitter.event().comment(comment)); } }
        catch (Exception ignore) {}
    }
    private void cancelUpstream(AtomicReference<Future<?>> ref) {
        Future<?> f = ref.get();
        if (f != null) f.cancel(true);
    }

    @Override
    public MediaModelListResponse listImageModels(String token) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'listImageModels'");
    }

    @Override
    public MediaModelListResponse listVideoModels(String token) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'listVideoModels'");
    }

    @Override
    public MediaTask createVideoTask(String token, CreateVideoTaskRequest req) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'createVideoTask'");
    }

    @Override
    public MediaTask getMediaTask(String token, String taskId) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getMediaTask'");
    }

    @Override
    public MediaTaskPage listMediaTasks(String token, int page, int size, String type) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'listMediaTasks'");
    }

}
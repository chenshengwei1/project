package com.virco.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.virco.agent.entity.ChatDTO;
import com.virco.agent.entity.ChatDTO.MediaOutput;
import com.virco.agent.entity.ChatDTO.MediaTask;
import com.virco.agent.entity.ChatDTO.MediaTaskPage;
import com.virco.agent.config.AppProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

// ==================== 接口 ====================

interface ConversationRepository {
    ChatDTO.ConversationPage page(String ownerId, int page, int size, String keyword, String scope);
    ChatDTO.Conversation create(String ownerId, String title, String modelId);
    ChatDTO.Conversation updateTitle(String id, String title);
    void deleteById(String id);
    void deleteAllByOwner(String ownerId);
    Optional<ChatDTO.Conversation> findByIdAndOwner(String id, String ownerId);
    Optional<ChatDTO.Conversation> findById(String id);
    void bumpUpdatedAt(String id, long newMsgCount);
    int countAll();
}

interface MessageRepository {
    ChatDTO.MessagePage pageByConversation(String conversationId, int page, int size, String before);
    List<ChatDTO.Message> listAllByConversation(String conversationId);
    ChatDTO.Message insertUserMessage(String conversationId, String userId, String content,
                                      List<ChatDTO.Attachment> attachments);
    int updateAndTruncateAfter(String messageId, String content, List<ChatDTO.Attachment> attachments);
    Optional<ChatDTO.Message> findById(String messageId);
    Optional<ChatDTO.Message> findUserMessageOwned(String messageId, String ownerId);
    ChatDTO.Message updateFeedback(String messageId, Integer feedback);
    String createAssistantPlaceholder(String conversationId, String modelId, String status);
    ChatDTO.Message finalizeAssistantMessage(String messageId, String content, String status,
                                             ChatDTO.TokenUsage usage);
    void markStatus(String messageId, String status);
}

interface AttachmentRepository {
    ChatDTO.Attachment create(String ownerId, String fileName, long size, String mime,
                              String url, String kind, String parseStatus);
    Optional<ChatDTO.Attachment> findByIdAndOwner(String id, String ownerId);
    List<ChatDTO.Attachment> findByIdsAndOwner(List<String> ids, String ownerId);
    void deleteById(String id);
}

interface PreferencesRepository {
    ChatDTO.Preferences findOrDefault(String ownerId, String defaultModelId);
    void save(String ownerId, ChatDTO.Preferences prefs);
}

interface MediaTaskRepository {
    MediaTask create(String ownerId, String type, String modelId, String provider, String prompt);
    void attachUpstream(String taskId, String upstreamTaskId);
    boolean updateStatus(String taskId, String status, List<MediaOutput> outputs,
                           String errorCode, String errorMessage);
    Optional<MediaTask> findByIdAndOwner(String taskId, String ownerId);
    MediaTaskPage pageByOwner(String ownerId, int page, int size, String type);
}



// ==================== JSON 存储基座 ====================

class JsonStore {

    private final Path file;
    private final ObjectMapper om;
    private final Map<String, Object> data = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Class<?> valueType;

    JsonStore(Path file, ObjectMapper om, Class<?> valueType) {
        this.file = file;
        this.om = om;
        this.valueType = valueType;
        load();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        try {
            if (!Files.exists(file)) return;
            var type = om.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, valueType);
            Map<String, Object> loaded = om.readValue(file.toFile(), type);
            if (loaded != null) data.putAll(loaded);
        } catch (Exception e) {
            throw new RuntimeException("load json failed: " + file, e);
        }
    }

    private void save() {
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            om.writeValue(tmp.toFile(), data);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new RuntimeException("save json failed: " + file, e);
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    <T> T get(String id) { return (T) data.get(id); }

    void put(String id, Object value) { data.put(id, value); save(); }

    void remove(String id) { if (data.remove(id) != null) save(); }

    @SuppressWarnings("unchecked")
    <T> List<T> values() {
        return data.values().stream().map(v -> (T) v).toList();
    }

    void clear() { if (!data.isEmpty()) { data.clear(); save(); } }
}

// ==================== JSON 实现 ====================

@Component
class JsonConversationRepository implements ConversationRepository {
    private final JsonStore store;
    JsonConversationRepository(ObjectMapper om, AppProperties props) {
        Path file = Paths.get(props.getData().getDir(), "conversations.json");
        this.store = new JsonStore(file, om, ChatDTO.Conversation.class);
    }
    @Override public ChatDTO.ConversationPage page(String ownerId, int page, int size, String kw, String scope) {
        List<ChatDTO.Conversation> all = store.<ChatDTO.Conversation>values().stream()
                .filter(c -> true) // 单用户场景，忽略 owner
                .sorted(Comparator.comparing(ChatDTO.Conversation::updatedAt).reversed())
                .toList();
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return new ChatDTO.ConversationPage(all.subList(from, to), page, size, all.size(),
                to < all.size(), kw);
    }
    @Override public ChatDTO.Conversation create(String ownerId, String title, String modelId) {
        String id = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        ChatDTO.Conversation c = new ChatDTO.Conversation(id, title, modelId, 0, now, now, null);
        store.put(id, c);
        return c;
    }
    @Override public ChatDTO.Conversation updateTitle(String id, String title) {
        ChatDTO.Conversation c = store.get(id);
        ChatDTO.Conversation u = new ChatDTO.Conversation(c.id(), title, c.modelId(), c.messageCount(),
                c.createdAt(), Instant.now(), c.match());
        store.put(id, u);
        return u;
    }
    @Override public void deleteById(String id) { store.remove(id); }
    @Override public void deleteAllByOwner(String ownerId) { store.clear(); }
    @Override public Optional<ChatDTO.Conversation> findByIdAndOwner(String id, String ownerId) {
        return Optional.ofNullable(store.<ChatDTO.Conversation>get(id));
    }
    @Override public Optional<ChatDTO.Conversation> findById(String id) {
        return Optional.ofNullable(store.<ChatDTO.Conversation>get(id));
    }
    @Override public void bumpUpdatedAt(String id, long newMsgCount) {
        ChatDTO.Conversation c = store.get(id);
        if (c == null) return;
        store.put(id, new ChatDTO.Conversation(c.id(), c.title(), c.modelId(), newMsgCount,
                c.createdAt(), Instant.now(), c.match()));
    }
    @Override public int countAll() { return store.values().size(); }
}

@Component
class JsonMessageRepository implements MessageRepository {
    private final JsonStore store;
    JsonMessageRepository(ObjectMapper om, AppProperties props) {
        Path file = Paths.get(props.getData().getDir(), "messages.json");
        this.store = new JsonStore(file, om, ChatDTO.Message.class);
    }
    @Override public ChatDTO.MessagePage pageByConversation(String cid, int page, int size, String before) {
        List<ChatDTO.Message> all = store.<ChatDTO.Message>values().stream()
                .filter(m -> cid.equals(m.conversationId()))
                .sorted(Comparator.comparing(ChatDTO.Message::createdAt))
                .toList();
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return new ChatDTO.MessagePage(all.subList(from, to), page, size, all.size(), to < all.size());
    }
    @Override public List<ChatDTO.Message> listAllByConversation(String cid) {
        return store.<ChatDTO.Message>values().stream()
                .filter(m -> cid.equals(m.conversationId()))
                .sorted(Comparator.comparing(ChatDTO.Message::createdAt))
                .toList();
    }
    @Override public ChatDTO.Message insertUserMessage(String cid, String uid, String content,
                                                       List<ChatDTO.Attachment> attachments) {
        String id = UUID.randomUUID().toString().replace("-", "");
        ChatDTO.Message m = new ChatDTO.Message(id, cid, "user", content == null ? "" : content,
                attachments == null ? List.of() : attachments, null, "normal", null, null,
                Instant.now(), null);
        store.put(id, m);
        return m;
    }
    @Override public int updateAndTruncateAfter(String messageId, String content,
                                                List<ChatDTO.Attachment> attachments) {
        ChatDTO.Message origin = store.get(messageId);
        if (origin == null) return 0;
        int deleted = 0;
        for (ChatDTO.Message m : store.<ChatDTO.Message>values()) {
            if (m.conversationId().equals(origin.conversationId())
                    && m.createdAt().isAfter(origin.createdAt())) {
                store.remove(m.id());
                deleted++;
            }
        }
        ChatDTO.Message updated = new ChatDTO.Message(origin.id(), origin.conversationId(),
                origin.role(), content, attachments == null ? List.of() : attachments,
                origin.feedback(), origin.status(), origin.reasoningContent(), origin.usage(),
                origin.createdAt(), origin.match());
        store.put(messageId, updated);
        return deleted;
    }
    @Override public Optional<ChatDTO.Message> findById(String messageId) {
        return Optional.ofNullable(store.<ChatDTO.Message>get(messageId));
    }
    @Override public Optional<ChatDTO.Message> findUserMessageOwned(String messageId, String ownerId) {
        ChatDTO.Message m = store.get(messageId);
        return m == null || !"user".equals(m.role()) ? Optional.empty() : Optional.of(m);
    }
    @Override public ChatDTO.Message updateFeedback(String messageId, Integer feedback) {
        ChatDTO.Message m = store.get(messageId);
        if (m == null) return null;
        ChatDTO.Message u = new ChatDTO.Message(m.id(), m.conversationId(), m.role(), m.content(),
                m.attachments(), feedback, m.status(), m.reasoningContent(), m.usage(),
                m.createdAt(), m.match());
        store.put(messageId, u);
        return u;
    }
    @Override public String createAssistantPlaceholder(String cid, String modelId, String status) {
        String id = UUID.randomUUID().toString().replace("-", "");
        ChatDTO.Message m = new ChatDTO.Message(id, cid, "assistant", "", List.of(), null, status,
                null, null, Instant.now(), null);
        store.put(id, m);
        return id;
    }
    @Override public ChatDTO.Message finalizeAssistantMessage(String messageId, String content,
                                                              String status, ChatDTO.TokenUsage usage) {
        ChatDTO.Message m = store.get(messageId);
        ChatDTO.Message u = new ChatDTO.Message(messageId, m.conversationId(), "assistant",
                content, m.attachments(), m.feedback(), status, m.reasoningContent(), usage,
                m.createdAt(), m.match());
        store.put(messageId, u);
        return u;
    }
    @Override public void markStatus(String messageId, String status) {
        ChatDTO.Message m = store.get(messageId);
        if (m == null) return;
        store.put(messageId, new ChatDTO.Message(m.id(), m.conversationId(), m.role(), m.content(),
                m.attachments(), m.feedback(), status, m.reasoningContent(), m.usage(),
                m.createdAt(), m.match()));
    }
}

@Component
class JsonAttachmentRepository implements AttachmentRepository {
    private final JsonStore store;
    JsonAttachmentRepository(ObjectMapper om, AppProperties props) {
        Path file = Paths.get(props.getData().getDir(), "attachments.json");
        this.store = new JsonStore(file, om, ChatDTO.Attachment.class);
    }
    @Override public ChatDTO.Attachment create(String ownerId, String fileName, long size,
                                               String mime, String url, String kind, String parseStatus) {
        String id = UUID.randomUUID().toString().replace("-", "");
        ChatDTO.Attachment a = new ChatDTO.Attachment(id, fileName, size, mime, url, kind, parseStatus);
        store.put(id, a);
        return a;
    }
    @Override public Optional<ChatDTO.Attachment> findByIdAndOwner(String id, String ownerId) {
        return Optional.ofNullable(store.<ChatDTO.Attachment>get(id));
    }
    @Override public List<ChatDTO.Attachment> findByIdsAndOwner(List<String> ids, String ownerId) {
        List<ChatDTO.Attachment> out = new ArrayList<>();
        for (String id : ids) {
            ChatDTO.Attachment a = store.get(id);
            if (a != null) out.add(a);
        }
        return out;
    }
    @Override public void deleteById(String id) { store.remove(id); }
}

@Component
class JsonPreferencesRepository implements PreferencesRepository {
    private final JsonStore store;
    JsonPreferencesRepository(ObjectMapper om, AppProperties props) {
        Path file = Paths.get(props.getData().getDir(), "preferences.json");
        this.store = new JsonStore(file, om, ChatDTO.Preferences.class);
    }
    @Override public ChatDTO.Preferences findOrDefault(String ownerId, String defaultModelId) {
        ChatDTO.Preferences p = store.get(ownerId);
        return p != null ? p : new ChatDTO.Preferences(defaultModelId, false, false, "light");
    }
    @Override public void save(String ownerId, ChatDTO.Preferences prefs) {
        store.put(ownerId, prefs);
    }
}

@Component
class JsonMediaTaskRepository implements MediaTaskRepository {
    private final JsonStore store;
    JsonMediaTaskRepository(ObjectMapper om, AppProperties props) {
        Path file = Paths.get(props.getData().getDir(), "media-task-repository.json");
        this.store = new JsonStore(file, om, ChatDTO.MediaTask.class);
    }
    @Override
    public MediaTask create(String ownerId, String type, String modelId, String provider, String prompt) {
        ChatDTO.MediaTask created = new ChatDTO.MediaTask(
                    "taskId", 
                    null, 
                    null, 
                    modelId, provider,
                    "init",
                    prompt,
                    null,
                    null, null,
                    Instant.now(), Instant.now());
        return created;
    }
    @Override
    public void attachUpstream(String taskId, String upstreamTaskId) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'attachUpstream'");
    }
    @Override
    public boolean updateStatus(String taskId, String newStatus, List<MediaOutput> outputs, String errorCode,
            String errorMessage) {
        try {
            ChatDTO.MediaTask t = store.get(taskId);
            if (t == null) return false;
            if (isTerminal(t.status())) return false;   // 关键：不接受终态回退

            ChatDTO.MediaTask updated = new ChatDTO.MediaTask(
                    t.taskId(), t.upstreamTaskId(), t.type(), t.modelId(), t.provider(),
                    newStatus,
                    t.prompt(),
                    outputs != null ? outputs : t.outputs(),
                    errorCode, errorMessage,
                    t.createdAt(), Instant.now());
            store.put(taskId, updated);
            return true;
        } finally {
        }
    }

    private static boolean isTerminal(String s) {
        return "succeeded".equals(s) || "failed".equals(s) || "canceled".equals(s);
    }
    @Override
    public Optional<MediaTask> findByIdAndOwner(String taskId, String ownerId) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'findByIdAndOwner'");
    }
    @Override
    public MediaTaskPage pageByOwner(String ownerId, int page, int size, String type) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'pageByOwner'");
    }
}
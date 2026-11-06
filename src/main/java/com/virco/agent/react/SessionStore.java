package com.virco.agent.react;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.virco.agent.config.AgentProperties;
import com.virco.agent.config.AppProperties;
import com.virco.agent.model.ChatMessage;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j 
public class SessionStore {
    private final Map<String, SessionData> store = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAccess = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final AgentProperties agent;
    private final JsonStore<SessionData> jsonStore;
    private final Path file;
    private static final int MAX_SESSIONS = 200;

    public SessionStore(ObjectMapper om, AgentProperties agent, AppProperties props) { 
        this.agent = agent; 
        this.file = Paths.get(props.getData().getDir(), "agent-sessions.json");
        this.jsonStore = new JsonStore<>(file, om, SessionData.class);
    }

    @PostConstruct
    public void init() {
        try {
            for (SessionData d : jsonStore.values()) {
                if (d == null || d.sessionId() == null) continue;
                store.put(d.sessionId(), d);
            }
            log.info("session.load path={} count={}", file, store.size());
        } catch (Exception e) {
            // 加载失败不阻塞启动，从空开始
            log.error("session.load.fail path={} err={}, 从空存储启动", file, e.toString());
        }
    }

     @PreDestroy
    public void shutdown() {
        // 如果 JsonStore 内部有缓冲，它自己处理；这里只记录
        log.info("session.shutdown count={}", store.size());
    }

    public List<ChatMessage> load(String sid) {
        SessionData sd = store.get(sid);
        if (sd == null){
            store.put(sid, SessionData.create(sid, null, null));
            sd = store.get(sid);
        }
        lastAccess.put(sid, System.currentTimeMillis());
        return new CopyOnWriteArrayList<>(sd.messages());
    }

    public void save(String sid, List<ChatMessage> msgs) {
        List<ChatMessage> trimmed = ContextTrimmer.trim(msgs,
                agent.react().maxHistoryTurns());
        SessionData updated = store.compute(sid, (k, old) -> {
            if (old == null) {
                String name = firstUserMessage(trimmed)
                    .map(SessionNameGenerator::fromFirstUserMessage)
                    .orElse("未命名会话");
                SessionData created = SessionData.create(sid, name, trimmed);
                log.info("session.create id={} name={}", sid, name);
                return created;
            }
            return old.touch(trimmed);
        });

        jsonStore.put(sid, updated);            // ★ 持久化
        evictIfNeeded();
    }

    private void evictIfNeeded() {
        if (store.size() <= MAX_SESSIONS) return;
        List<String> evict = store.entrySet().stream()
            .sorted(Comparator.comparingLong(e -> e.getValue().lastModifiedAt()))
            .limit(store.size() - MAX_SESSIONS)
            .map(Map.Entry::getKey)
            .toList();
        for (String sid : evict) {
            store.remove(sid);
            locks.remove(sid);
        }
        log.warn("session.max.exceeded evicted={} remain={}", evict.size(), store.size());
    }
    private Optional<String> firstUserMessage(List<ChatMessage> messages) {
        return messages.stream()
            .filter(m -> "user".equals(m.role()))
            .map(ChatMessage::content)
            .filter(c -> c != null && !c.isBlank())
            .findFirst();
    }

    public ReentrantLock lock(String sid) {
        return locks.computeIfAbsent(sid, k -> new ReentrantLock());
    }

    @Scheduled(fixedDelayString = "#{@agentProperties.session.cleanupIntervalMs}")
    public void cleanup() {
        long ttl = agent.session().ttlMinutes() * 60_000L;
        long now = System.currentTimeMillis();
        lastAccess.entrySet().removeIf(e -> {
            if (now - e.getValue() > ttl) {
                store.remove(e.getKey());
                locks.remove(e.getKey());
                return true;
            }
            return false;
        });
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionData(
        String sessionId,
        String name,
        long createdAt,
        long lastModifiedAt,
        List<ChatMessage> messages
    ) {
        public SessionData {
            if (messages == null) messages = List.of();
            if (name == null || name.isBlank()) name = "未命名会话";
        }
        public static SessionData create(String sessionId, String name, List<ChatMessage> messages) {
            long now = System.currentTimeMillis();
            return new SessionData(sessionId, name, now, now, messages);
        }
        public SessionData touch(List<ChatMessage> newMessages) {
            return new SessionData(sessionId, name, createdAt,
                                   System.currentTimeMillis(), newMessages);
        }
        public SessionData rename(String newName) {
            return new SessionData(sessionId, newName, createdAt,
                                   System.currentTimeMillis(), messages);
        }
    }

    public record SessionSummary(
        String sessionId, String name,
        long createdAt, long lastModifiedAt, int messageCount
    ) {}
}

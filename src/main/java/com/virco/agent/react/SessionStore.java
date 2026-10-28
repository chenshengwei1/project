package com.virco.agent.react;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.virco.agent.config.AgentProperties;
import com.virco.agent.model.ChatMessage;

@Component
public class SessionStore {
    private final Map<String, List<ChatMessage>> store = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAccess = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final AgentProperties agent;

    public SessionStore(AgentProperties agent) { this.agent = agent; }

    public List<ChatMessage> load(String sid) {
        lastAccess.put(sid, System.currentTimeMillis());
        return store.computeIfAbsent(sid, k -> new CopyOnWriteArrayList<>());
    }

    public void save(String sid, List<ChatMessage> msgs) {
        store.put(sid, new CopyOnWriteArrayList<>(msgs));
        lastAccess.put(sid, System.currentTimeMillis());
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
}

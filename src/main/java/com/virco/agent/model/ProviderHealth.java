package com.virco.agent.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


import org.springframework.stereotype.Component;

import com.virco.agent.config.AgentProperties;

@Component
public class ProviderHealth {
    private record State(int failures, long blockedUntil) {}
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final AgentProperties agent;
    public ProviderHealth(AgentProperties agent) { this.agent = agent; }
    public boolean available(String name) {
        State s = states.get(name);
        return s == null || System.currentTimeMillis() >= s.blockedUntil();
    }
    public void success(String name) { states.remove(name); }
    public void failure(String name) {
        var r = agent.routing();
        states.compute(name, (k, s) -> {
            int f = (s == null ? 0 : s.failures()) + 1;
            long until = f >= r.failureThreshold()
                ? System.currentTimeMillis() + r.cooldownSeconds() * 1000 : 0;
            return new State(f, until);
        });
    }
}
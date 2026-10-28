package com.virco.agent.dialog;

import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.stereotype.Component;

import com.virco.agent.config.AgentProperties;

@Component
public class TraceBuffer {
    private final Deque<Map<String, Object>> buf = new ConcurrentLinkedDeque<>();
    private final int max;
    public TraceBuffer(AgentProperties p) { this.max = p.diag().traceBufferSize(); }
    public void add(Map<String, Object> r) { buf.addFirst(r); while (buf.size() > max) buf.pollLast(); }
    public List<Map<String, Object>> recent(int n) { return buf.stream().limit(n).toList(); }
}

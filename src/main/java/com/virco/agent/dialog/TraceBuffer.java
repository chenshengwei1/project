package com.virco.agent.dialog;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.virco.agent.config.AgentProperties;
import com.virco.agent.config.AppProperties;
import com.virco.agent.react.JsonStore;
import com.virco.agent.react.SessionStore.SessionData;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor 
public class TraceBuffer {
    private final Deque<Map<String, Object>> buf = new ConcurrentLinkedDeque<>();
    private final AgentProperties p;
    private final AppProperties props;
    private final ObjectMapper om;
    private JsonStore<TraceRecord> jsonStore;
    private Path file;
    private int max;
    public void add(Map<String, Object> r) { buf.addFirst(r); while (buf.size() > max) buf.pollLast(); }
    public List<Map<String, Object>> recent(int n) { return buf.stream().limit(n).toList(); }

    @PostConstruct 
    public void init(){
        this.max = p.diag().traceBufferSize();
        this.file = Paths.get(props.getData().getDir(), "agent-trace.json");
        this.jsonStore = new JsonStore<>(file, om, TraceRecord.class);
        this.jsonStore.get("TraceBuffer").buf().stream().filter(Objects::nonNull).forEach(buf::add);
    }

    @PreDestroy 
    public void destroy(){
        this.jsonStore.put("TraceBuffer", new TraceRecord("TraceBuffer", buf));
    }

    record TraceRecord(String id, Deque<Map<String, Object>> buf){
    }
}

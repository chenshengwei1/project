package com.virco.agent.controller;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.virco.agent.config.AgentProperties;
import com.virco.agent.react.Event;
import com.virco.agent.react.ReActAgent;
import com.virco.agent.react.SessionStore;

import lombok.RequiredArgsConstructor;


// web/AgentController.java
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor 
public class AgentController {
    private final ReActAgent agent;
    private final SessionStore sessions;
    private final AgentProperties props;

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatReq req) {
        String sid = req.sessionId();
        var lock = sessions.lock(sid);
        lock.lock();
        try {
            long t0 = System.currentTimeMillis();
            String answer = agent.run(sid, req.message(), null);
            return Map.of("sessionId", sid, "answer", answer,
                "elapsedMs", System.currentTimeMillis() - t0);
        } finally { lock.unlock(); }
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String sessionId, @RequestParam String message) {
        SseEmitter emitter = new SseEmitter(props.stream().sseTimeoutMs());
        AtomicBoolean closed = new AtomicBoolean(false);
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sse-" + sessionId); t.setDaemon(true); return t;
        });
        ex.submit(() -> {
            var lock = sessions.lock(sessionId);
            lock.lock();
            try {
                agent.run(sessionId, message, e -> {
                    if (closed.get()) return;
                    try { emitter.send(mapEvent(e)); }
                    catch (IOException io) { closed.set(true); throw new RuntimeException("client closed"); }
                });
                emitter.complete();
            } catch (Exception e) {
                try { emitter.send(SseEmitter.event().name("error")
                    .data(Map.of("code", "ERROR", "message", String.valueOf(e.getMessage())))); }
                catch (IOException ignored) {}
                emitter.completeWithError(e);
            } finally { lock.unlock(); ex.shutdown(); }
        });
        emitter.onCompletion(() -> { closed.set(true); ex.shutdown(); });
        emitter.onTimeout(() -> { closed.set(true); ex.shutdown(); });
        return emitter;
    }

    private SseEmitter.SseEventBuilder mapEvent(Event e) {
        if (e instanceof Event.Delta d) return SseEmitter.event().name("delta").data(Map.of("content", d.content()));
        if (e instanceof Event.StepStart s) return SseEmitter.event().name("step").data(Map.of("step", s.step()));
        if (e instanceof Event.ToolStart t) return SseEmitter.event().name("tool_start").data(Map.of("name", t.name(), "args", t.args()));
        if (e instanceof Event.ToolDone t) return SseEmitter.event().name("tool_done").data(Map.of("name", t.name(), "elapsed", t.elapsedMs(), "resultHead", t.resultHead()));
        if (e instanceof Event.Done d) return SseEmitter.event().name("done").data(Map.of("elapsed", d.elapsedMs(), "steps", d.steps()));
        if (e instanceof Event.Error er) return SseEmitter.event().name("error").data(Map.of("code", er.code(), "message", er.message()));
        return SseEmitter.event().name("unknown").data(Map.of());
    }

    public record ChatReq(String sessionId, String message) {}
}
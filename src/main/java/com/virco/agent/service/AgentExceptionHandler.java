package com.virco.agent.service;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.virco.agent.model.LlmRouter.AllProvidersUnavailableException;

@RestControllerAdvice
public class AgentExceptionHandler {
    @ExceptionHandler(AllProvidersUnavailableException.class)
    public ResponseEntity<Map<String, Object>> allDown(AllProvidersUnavailableException e) {
        return ResponseEntity.status(503).body(Map.of("code", "ALL_PROVIDERS_DOWN",
            "message", "模型服务暂不可用，请稍后重试"));
    }
    @ExceptionHandler(AgentException.class)
    public ResponseEntity<Map<String, Object>> agent(AgentException e) {
        return ResponseEntity.status(500).body(Map.of("code", e.code(), "message", e.userMessage()));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        return ResponseEntity.status(500).body(Map.of("code", "INTERNAL",
            "message", "服务异常，请稍后重试"));
    }
}

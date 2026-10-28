package com.virco.agent.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天接口控制器
 * <p>
 * 对应原 dsagent 的 web_server.py 中的路由
 * 提供 RESTful API 供前端聊天界面调用
 */
@RestController
@RequestMapping("/api")
public class CoreAgentController {

    private static final Logger log = LoggerFactory.getLogger(CoreAgentController.class);

    @GetMapping("/hello")
    public String hello() {
        return "Hello from manually created project!";
    }
    
    /**
     * 健康检查接口
     * GET /api/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        log.debug("健康检查请求");
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        status.put("service", "AI Agent");
        status.put("version", "1.0.0");
        status.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(status);
    }

        /**
     * 创建新会话
     * POST /api/sessions
     */
    @PostMapping("/sessions/new")
    public ResponseEntity<Map<String, Object>> createSession() {
        log.info("创建新会话请求");
        try {
            log.info("创建新会话");
            String sessionId = System.currentTimeMillis() + "";
            log.info("新会话创建成功 - sessionId: {}", sessionId);
            Map<String, Object> result = new HashMap<>();
            result.put("sessionId", sessionId);
            result.put("message", "新会话已创建");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("创建会话失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "创建会话失败: " + e.getMessage()));
        }
    }
}

package com.virco.agent.dialog;

import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/agent/diag")
@ConditionalOnProperty(prefix = "agent.diag", name = "expose-endpoints", havingValue = "true")
public class DiagController {
    private final TraceBuffer buffer;
    public DiagController(TraceBuffer b) { this.buffer = b; }
    @GetMapping("/recent") public List<Map<String, Object>> recent(@RequestParam(defaultValue = "20") int n) {
        return buffer.recent(n);
    }
}

package com.virco.agent.controller;


import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.virco.agent.service.RedisHealthService;

import java.util.Map;

@RestController
@RequestMapping("/api/redis")
public class RedisHealthController {

    private final RedisHealthService healthService;

    public RedisHealthController(RedisHealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return healthService.collect();
    }
}

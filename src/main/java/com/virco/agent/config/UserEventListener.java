package com.virco.agent.config;


import org.springframework.stereotype.Component;

import com.virco.agent.entity.ChatDTO.User;
import com.virco.agent.entity.ChatDTO.UserRequest;
import com.virco.agent.user.UserService;
import com.virco.agent.util.RedisCacheUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j 
@Component
@RequiredArgsConstructor 
public class UserEventListener {

    private final UserService userService;
    private final RedisCacheUtil cache;
    /** 收到消息时被调用 */
    public void onMessage(String message) {
        log.info("收到用户事件: " + message);
        String key = message;

        if (key != null && key.length() >= 2
            && key.charAt(0) == '"' && key.charAt(key.length() - 1) == '"') {
            key = key.substring(1, key.length() - 1);
        }

        // 1. 先判空，避免 String.valueOf(null) -> "null"
        Object raw = cache.get(key);
        if (raw == null) {
            log.error("缓存中不存在 key={}，可能是写缓存未成功或时序问题", key);
            return;   // 不要继续，避免 NPE
        }

        // 2. 根据实际存储类型解析，不要盲目 String.valueOf
        String json = (raw instanceof String) ? (String) raw : String.valueOf(raw);
        User user = userService.safeValueAsString(json);
        
        if (user != null) {
            log.info("用户信息: {}", user);
        } else {
            log.error("用户信息不存在");
        }

        userService.add(new UserRequest(user.getId(), user.getName(), user.getEmail(), user.getAge()));
    }
}

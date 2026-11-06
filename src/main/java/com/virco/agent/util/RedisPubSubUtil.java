package com.virco.agent.util;


import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisPubSubUtil {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisPubSubUtil(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 发布消息 */
    public Long publish(String channel, Object message) {
        return redisTemplate.convertAndSend(channel, message);
    }
}

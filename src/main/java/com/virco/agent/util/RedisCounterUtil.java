package com.virco.agent.util;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class RedisCounterUtil {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisCounterUtil(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 原子自增 */
    public Long increment(String key, long delta) {
        return redisTemplate.opsForValue().increment(key, delta);
    }

    /** 固定窗口限流：窗口内计数超过 limit 则拒绝 */
    public boolean tryAcquire(String key, int limit, long windowSeconds) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
        }
        return count != null && count <= limit;
    }

    /** 获取当前计数 */
    public Object getCount(String key) {
        return redisTemplate.opsForValue().get(key);
    }
}

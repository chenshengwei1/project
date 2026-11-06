package com.virco.agent.util;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class RedisQueueUtil {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisQueueUtil(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 入队（从左侧推入） */
    public Long push(String key, Object value) {
        return redisTemplate.opsForList().leftPush(key, value);
    }

    /** 批量入队 */
    public Long pushAll(String key, List<Object> values) {
        return redisTemplate.opsForList().leftPushAll(key, values);
    }

    /** 非阻塞出队（从右侧弹出，FIFO） */
    public Object pop(String key) {
        return redisTemplate.opsForList().rightPop(key);
    }

    /** 阻塞出队，队列为空时最多等待 timeout 秒 */
    public Object popBlocking(String key, long timeout, TimeUnit unit) {
        return redisTemplate.opsForList().rightPop(key, timeout, unit);
    }

    /** 查看队列长度 */
    public Long size(String key) {
        return redisTemplate.opsForList().size(key);
    }

    /** 查看队列内容（不弹出） */
    public List<Object> range(String key, long start, long end) {
        return redisTemplate.opsForList().range(key, start, end);
    }
}
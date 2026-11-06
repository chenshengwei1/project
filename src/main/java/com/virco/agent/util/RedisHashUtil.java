package com.virco.agent.util;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class RedisHashUtil {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisHashUtil(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 存单个字段 */
    public void put(String key, String field, Object value) {
        redisTemplate.opsForHash().put(key, field, value);
    }

    /** 批量存字段 */
    public void putAll(String key, Map<String, Object> map) {
        redisTemplate.opsForHash().putAll(key, map);
    }

    /** 取单个字段 */
    public Object get(String key, String field) {
        return redisTemplate.opsForHash().get(key, field);
    }

    /** 取全部字段 */
    public Map<Object, Object> entries(String key) {
        return redisTemplate.opsForHash().entries(key);
    }

    /** 删除字段 */
    public Long delete(String key, Object... fields) {
        return redisTemplate.opsForHash().delete(key, fields);
    }

    /** 字段是否存在 */
    public Boolean hasField(String key, String field) {
        return redisTemplate.opsForHash().hasKey(key, field);
    }

    /** 原子自增字段 */
    public Long increment(String key, String field, long delta) {
        return redisTemplate.opsForHash().increment(key, field, delta);
    }
}
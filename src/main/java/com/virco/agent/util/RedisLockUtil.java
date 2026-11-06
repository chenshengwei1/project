package com.virco.agent.util;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class RedisLockUtil {

    private final RedisTemplate<String, Object> redisTemplate;

    /** 释放锁的 Lua 脚本：只有锁的持有者才能释放 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);

    public RedisLockUtil(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试获取锁
     * @return 成功返回锁标识（用于释放），失败返回 null
     */
    public String tryLock(String lockKey, long leaseTime, TimeUnit unit) {
        String token = UUID.randomUUID().toString();
        Boolean ok = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, token, leaseTime, unit);
        return Boolean.TRUE.equals(ok) ? token : null;
    }

    /** 释放锁，必须传入获取锁时返回的 token */
    public boolean unlock(String lockKey, String token) {
        Long result = redisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(lockKey),
                token);
        return result != null && result > 0;
    }
}

package com.virco.agent.util;


import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class RedisSetUtil {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisSetUtil(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 添加成员，返回新增数量（去重自动生效） */
    public Long add(String key, Object... values) {
        return redisTemplate.opsForSet().add(key, values);
    }

    /** 判断成员是否存在（点赞是否已点） */
    public Boolean isMember(String key, Object value) {
        return redisTemplate.opsForSet().isMember(key, value);
    }

    /** 删除成员 */
    public Long remove(String key, Object... values) {
        return redisTemplate.opsForSet().remove(key, values);
    }

    /** 获取全部成员 */
    public Set<Object> members(String key) {
        return redisTemplate.opsForSet().members(key);
    }

    /** 交集（共同关注） */
    public Set<Object> intersect(String key1, String key2) {
        return redisTemplate.opsForSet().intersect(key1, key2);
    }

    /** 并集 */
    public Set<Object> union(String key1, String key2) {
        return redisTemplate.opsForSet().union(key1, key2);
    }

    /** 差集 */
    public Set<Object> difference(String key1, String key2) {
        return redisTemplate.opsForSet().difference(key1, key2);
    }

    /** 集合大小 */
    public Long size(String key) {
        return redisTemplate.opsForSet().size(key);
    }
}

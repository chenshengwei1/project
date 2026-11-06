package com.virco.agent.util;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class RedisRankUtil {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisRankUtil(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 添加或更新分数 */
    public Boolean add(String key, Object member, double score) {
        return redisTemplate.opsForZSet().add(key, member, score);
    }

    /** 原子增加分数 */
    public Double incrementScore(String key, Object member, double delta) {
        return redisTemplate.opsForZSet().incrementScore(key, member, delta);
    }

    /** 获取排名前 N（分数从高到低，带分数） */
    public Set<ZSetOperations.TypedTuple<Object>> topN(String key, long n) {
        return redisTemplate.opsForZSet().reverseRangeWithScores(key, 0, n - 1);
    }

    /** 获取某个成员的排名（从 0 开始，越高越靠前） */
    public Long rank(String key, Object member) {
        return redisTemplate.opsForZSet().reverseRank(key, member);
    }

    /** 获取某个成员的分数 */
    public Double score(String key, Object member) {
        return redisTemplate.opsForZSet().score(key, member);
    }

    /** 删除成员 */
    public Long remove(String key, Object... members) {
        return redisTemplate.opsForZSet().remove(key, members);
    }

    /** 统计分数区间内的成员数 */
    public Long count(String key, double min, double max) {
        return redisTemplate.opsForZSet().count(key, min, max);
    }
}

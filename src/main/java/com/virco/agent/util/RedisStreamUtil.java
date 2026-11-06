package com.virco.agent.util;

import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class RedisStreamUtil {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisStreamUtil(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 创建消费者组（已存在会抛异常，可忽略） */
    public void createGroup(String streamKey, String group) {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, group);
        } catch (Exception ignored) {
            // BUSYGROUP: Consumer Group name already exists
        }
    }

    /** 生产消息 */
    public RecordId add(String streamKey, Map<String, Object> message) {
        return redisTemplate.opsForStream().add(streamKey, message);
    }

    /** 消费者读取新消息（> 表示从未投递的消息） */
    public List<MapRecord<String, Object, Object>> consume(
            String streamKey, String group, String consumer, long count) {
        return redisTemplate.opsForStream().read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(count).block(Duration.ofSeconds(2)),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
    }

    /** 确认消息已处理 */
    public Long ack(String streamKey, String group, RecordId id) {
        return redisTemplate.opsForStream().acknowledge(streamKey, group, id);
    }

    /** 查看待处理（未 ACK）消息 */
    public PendingMessages pending(String streamKey, String group) {
        return redisTemplate.opsForStream()
                .pending(streamKey, Consumer.from(group, "consumer_1"));
    }
}

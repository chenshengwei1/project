package com.virco.agent.controller;

import org.springframework.web.bind.annotation.*;

import com.virco.agent.config.PubSubConfig;
import com.virco.agent.entity.ChatDTO.User;
import com.virco.agent.entity.ChatDTO.UserRequest;
import com.virco.agent.user.UserService;
import com.virco.agent.util.RedisCacheUtil;
import com.virco.agent.util.RedisCounterUtil;
import com.virco.agent.util.RedisHashUtil;
import com.virco.agent.util.RedisLockUtil;
import com.virco.agent.util.RedisPubSubUtil;
import com.virco.agent.util.RedisQueueUtil;
import com.virco.agent.util.RedisRankUtil;
import com.virco.agent.util.RedisSetUtil;
import com.virco.agent.util.RedisStreamUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/redis")
@RequiredArgsConstructor
@Slf4j 
public class RedisDemoController {

    private final RedisCacheUtil cache;
    private final RedisHashUtil hash;
    private final RedisQueueUtil queue;
    private final RedisSetUtil set;
    private final RedisRankUtil rank;
    private final RedisLockUtil lock;
    private final RedisCounterUtil counter;
    private final RedisStreamUtil stream;
    private final RedisPubSubUtil pubSub;
    private final UserService userService;


    @GetMapping("/cache")
    public Object cacheDemo() {
        cache.set("user:1", Map.of("name", "张三"), 10, TimeUnit.MINUTES);
        return cache.get("user:1");
    }

    @GetMapping("/hash")
    public Object hashDemo() {
        User user = AllChineseUserGenerator.randomUser();
        String key = "user:" + user.getId();
        hash.put(key, "name", user.getName());
        hash.put(key, "age", user.getAge());
        return hash.entries(key);
    }

    @GetMapping("/queue")
    public Object queueDemo() {
        User user1 = AllChineseUserGenerator.randomUser();
        User user2 = AllChineseUserGenerator.randomUser();
        queue.push("task:queue", user1);
        queue.push("task:queue", user2);
        return queue.pop("task:queue");
    }

    @GetMapping("/rank")
    public Object rankDemo() {
        rank.add("game:rank", "player1", 9500);
        rank.incrementScore("game:rank", "player1", 100);
        return rank.topN("game:rank", 10);
    }

    @GetMapping("/lock")
    public Object lockDemo() {
        String token = lock.tryLock("lock:order", 10, TimeUnit.SECONDS);
        if (token == null) return "获取锁失败";
        try {
            return "获取锁成功，执行业务";
        } finally {
            lock.unlock("lock:order", token);
        }
    }

    @GetMapping("/limit")
    public Object limitDemo() {
        boolean ok = counter.tryAcquire("limit:api:1", 5, 60);
        return ok ? "允许访问" : "被限流";
    }

    @GetMapping("/stream")
    public Object streamDemo() {
        stream.createGroup("order:stream", "order:group");
        stream.add("order:stream", Map.of("orderNo", "1001"));
        List<?> records = stream.consume("order:stream", "order:group", "c1", 1);
        return records;
    }

    @GetMapping("/pubsub")
    public Object pubSubDemo() {
        pubSub.publish(PubSubConfig.CHANNEL, "订单已创建:1001");
        return "已发布";
    }

    @PostMapping ("/create-users")
    public User createdUsers() {
        User user = AllChineseUserGenerator.randomUser();
        String key = "user:" + user.getId();
        log.info("创建用户: {}", user);
        cache.set(key, userService.safeValueAsString(user), 10, TimeUnit.MINUTES);

        // 可选：写完立刻回读校验，排查序列化问题
        log.debug("回读缓存: key={}, value={}", key, cache.get(key));

        pubSub.publish(PubSubConfig.USER_CHANNEL, key);

        return user;
    }

    @GetMapping ("/users")
    public List<User> listUsers(@RequestParam(defaultValue = "0") long page,
                                @RequestParam(defaultValue = "10") long size) {
        return userService.listUsers(page, size);
    }

     // 修改用户
    @PutMapping("/users/{id}")
    public User updateUser(@PathVariable String id, @RequestBody UserRequest req) {
        return userService.update(id, req);
    }

    // 删除用户
    @DeleteMapping("/users/{id}")
    public User deleteUser(@PathVariable String id) {
        return userService.delete(id);
    }
}
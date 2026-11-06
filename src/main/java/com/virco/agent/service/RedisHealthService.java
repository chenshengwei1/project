package com.virco.agent.service;


import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Service
@Slf4j 
public class RedisHealthService {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisHealthService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void verifyRedisInfoOnStartup() {
        RedisConnectionFactory factory = redisTemplate.getConnectionFactory();
        if (factory == null) {
            log.error("[Redis自检] RedisConnectionFactory 未配置，跳过检查");
            return;
        }

        RedisConnection connection = null;
        try {
            connection = factory.getConnection();

            String pong = connection.ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                log.error("[Redis自检] PING 返回异常: {}", pong);
                return;
            }
            log.info("[Redis自检] 连接正常，PING={}", pong);

            // 关键：用 execute 拿原始返回值，不走 Properties 解析
            Object rawResult = connection.execute("INFO");
            String rawInfo = normalizeInfoResult(rawResult);

            if (rawInfo == null || rawInfo.isBlank()) {
                log.error("[Redis自检] INFO 返回为空，rawResult 类型={}",
                        rawResult == null ? "null" : rawResult.getClass().getName());
                return;
            }

            String[] lines = rawInfo.split("\\r?\\n");
            log.info("[Redis自检] INFO 原始内容共 {} 行，前 20 行如下：", lines.length);
            for (int i = 0; i < Math.min(20, lines.length); i++) {
                log.info("[Redis自检] RAW[{}] {}", i, lines[i]);
            }

            Map<String, Map<String, String>> info = parseInfo(rawInfo);
            log.info("[Redis自检] 解析出 {} 个 section: {}", info.size(), info.keySet());

            Map<String, String> server = info.getOrDefault("Server", Map.of());
            if (server.isEmpty()) {
                log.error("[Redis自检] 未解析到 Server section，INFO 格式可能不符合预期");
                throw new IllegalStateException(
                        "[Redis自检] INFO 解析结果为空，请检查上方 RAW 日志");
            }

            log.info("[Redis自检] Server.redis_version = {}",
                    server.getOrDefault("redis_version", "<缺失>"));
            log.info("[Redis自检] Server.tcp_port      = {}",
                    server.getOrDefault("tcp_port", "<缺失>"));
            log.info("[Redis自检] Server.os             = {}",
                    server.getOrDefault("os", "<缺失>"));

            Map<String, String> memory = info.getOrDefault("Memory", Map.of());
            log.info("[Redis自检] Memory.used_memory_human = {}",
                    memory.getOrDefault("used_memory_human", "<缺失>"));

            Map<String, String> stats = info.getOrDefault("Stats", Map.of());
            log.info("[Redis自检] Stats.keyspace_hits   = {}",
                    stats.getOrDefault("keyspace_hits", "<缺失>"));
            log.info("[Redis自检] Stats.keyspace_misses = {}",
                    stats.getOrDefault("keyspace_misses", "<缺失>"));

            log.info("[Redis自检] 通过，Redis 信息可正常读取");

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Redis自检] 执行失败: {}: {}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    /**
     * 把 connection.execute("INFO") 的返回值统一转成字符串。
     * Lettuce 下可能是 byte[]、String，或 List<byte[]>（视解码路径而定）。
     */
    private String normalizeInfoResult(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (raw instanceof String s) {
            return s;
        }
        if (raw instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item instanceof byte[] b) {
                    sb.append(new String(b, StandardCharsets.UTF_8));
                } else {
                    sb.append(String.valueOf(item));
                }
                sb.append('\n');
            }
            return sb.toString();
        }
        // 其它类型，退化为 toString，便于日志观察
        log.warn("[Redis自检] INFO 返回了未预期的类型: {}", raw.getClass().getName());
        return String.valueOf(raw);
    }

    /** 采集一次 Redis 状态快照 */
    public Map<String, Object> collect() {
        Map<String, Object> result = new LinkedHashMap<>();
        RedisConnectionFactory factory = redisTemplate.getConnectionFactory();
        if (factory == null) {
            result.put("connected", false);
            result.put("error", "RedisConnectionFactory 未配置");
            return result;
        }

        RedisConnection connection = null;
        try {
            connection = factory.getConnection();

            // 1. 连通性
            String ping = connection.ping();
            result.put("connected", "PONG".equalsIgnoreCase(ping));
            result.put("timestamp", System.currentTimeMillis());

            // 2. INFO 信息按段落解析
            //Properties info = connection.serverCommands().info();

            // 关键：直接执行 INFO 命令，拿到原始字符串，绕开 Properties 解析
            Object rawResult = connection.execute("INFO");
            String rawInfo = normalizeInfoResult(rawResult);
            Map<String, Map<String, String>> info = parseInfo(rawInfo);

            result.put("server", pick(info, "Server", "redis_version", "redis_mode",
                    "os", "process_id", "uptime_in_seconds", "tcp_port"));
            result.put("clients", pick(info, "Clients", "connected_clients",
                    "blocked_clients", "tracking_clients"));
            result.put("memory", pick(info, "Memory", "used_memory",
                    "used_memory_human", "used_memory_peak_human",
                    "maxmemory", "maxmemory_human", "mem_fragmentation_ratio"));
            result.put("stats", pick(info, "Stats", "total_connections_received",
                    "total_commands_processed", "instantaneous_ops_per_sec",
                    "keyspace_hits", "keyspace_misses",
                    "expired_keys", "evicted_keys"));
            result.put("persistence", pick(info, "Persistence", "rdb_last_bgsave_status",
                    "aof_enabled", "aof_last_write_status", "rdb_last_save_time"));

            // 3. 命中率计算
            Map<String, String> stats = info.getOrDefault("Stats", Map.of());
            long hits = parseLong(stats.get("keyspace_hits"));
            long misses = parseLong(stats.get("keyspace_misses"));
            long total = hits + misses;
            result.put("hitRate", total == 0 ? "N/A"
                    : String.format("%.2f%%", hits * 100.0 / total));

            result.put("persistence", pick(info, "Persistence", "rdb_last_bgsave_status",
                    "aof_enabled", "aof_last_write_status", "rdb_last_save_time"));
            result.put("keyspace", extractKeyspace(info));
            result.put("dbSize", connection.serverCommands().dbSize());

        } catch (Exception e) {
            e.printStackTrace();
            result.put("connected", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
        return result;
    }


    /** 从所有分节里提取以 db 开头的键空间信息 */
    private Map<String, String> extractKeyspace(Map<String, Map<String, String>> info) {
        Map<String, String> keyspace = new LinkedHashMap<>();
        info.forEach((section, kv) -> kv.forEach((k, v) -> {
            if (k.startsWith("db")) {
                keyspace.put(k, v);
            }
        }));
        return keyspace;
    }

    /**
     * 按行解析 INFO 原始文本。
     * 格式：# Section 开头，key:value 每行一个。
     * 完全不用 java.util.Properties，避免转义问题。
     */
    private Map<String, Map<String, String>> parseInfo(String raw) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        String current = "General";
        sections.put(current, new LinkedHashMap<>());
        for (String line : raw.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                current = line.substring(1).trim();
                sections.putIfAbsent(current, new LinkedHashMap<>());
            } else {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String key = line.substring(0, idx).trim();
                    String value = line.substring(idx + 1).trim();
                    sections.get(current).put(key, value);
                }
            }
        }
        return sections;
    }

    private Map<String, String> pick(Map<String, Map<String, String>> info,
                                     String section, String... keys) {
        Map<String, String> src = info.getOrDefault(section, Map.of());
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : keys) out.put(k, src.getOrDefault(k, "-"));
        return out;
    }

    private long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }
}

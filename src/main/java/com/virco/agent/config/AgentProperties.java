package com.virco.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;


import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * AI Agent 配置属性类
 * <p>
 * 对应原 dsagent 的 .env 和 config 配置
 * 从 application.yml 的 ai.agent.* 读取配置
 */
@ConfigurationProperties(prefix = "ai.agent")
public record AgentProperties (
        String primary,
        @DefaultValue List<String> backups,
        @DefaultValue React react, 
        @DefaultValue Timeouts timeouts, 
        @DefaultValue Retry retry, 
        @DefaultValue Routing routing,
        @DefaultValue Stream stream, 
        @DefaultValue Tool tool, 
        @DefaultValue Session session, 
        @DefaultValue Diag diag, 
        @DefaultValue Log log,
        @DefaultValue DeepSeekConfig deepseek,
        @DefaultValue ToolsConfig toolcfgs,
        @DefaultValue MemoryConfig memory,
        @DefaultValue Tools tools
    ){


    @PostConstruct
    public void init() {
        Logger log = LoggerFactory.getLogger(AgentProperties.class);
        log.info("============================================================");
        log.info("  AI Agent 配置加载完成");
        log.info("  DeepSeek API URL: {}", deepseek.apiUrl());
        log.info("  DeepSeek Model: {}", deepseek.model());
        log.info("  DeepSeek Max Tokens: {}", deepseek.maxTokens());
        log.info("  DeepSeek Temperature: {}", deepseek.temperature());
        log.info("  允许的文件路径: {}", toolcfgs.effectiveAllowedPaths());
        log.info("  允许的命令: {}", toolcfgs.allowedCommands());
        log.info("  记忆功能: {}", memory.enabled() ? "已启用" : "已禁用");
        log.info("  每会话最大历史: {}", memory.maxHistoryPerSession());
        log.info("============================================================");
    }

    // ============================================================
    // DeepSeek API 配置
    // ============================================================
  
   public record DeepSeekConfig(
            /** API Key */
            @DefaultValue("sk-ac972af6d763480aa2390a48e0697fae") String apiKey,
            /** API 请求地址 */
            @DefaultValue("https://api.deepseek.com/v1/chat/completions") String apiUrl,
            /** 模型名称 */
            @DefaultValue("deepseek-chat") String model,
            /** 最大 Token 数 */
            @DefaultValue("4096") int maxTokens,
            /** 温度参数 (0-2) */
            @DefaultValue("0.7") double temperature,
            /** Top-P 采样 */
            @DefaultValue("0.95") double topP,
            /** 连接超时（毫秒） */
            @DefaultValue("30000") int connectTimeout,
            /** 读取超时（毫秒） */
            @DefaultValue("60000") int readTimeout,
            /** 写入超时（毫秒） */
            @DefaultValue("30000") int writeTimeout
    ) {}

    // ============================================================
    // 工具配置
    // ============================================================
    
    public record ToolsConfig (/** 允许操作的文件路径白名单 */
        List<String> allowedPaths,
        /** 允许执行的命令前缀 */
        @DefaultValue({"npm", "node", "git", "dir", "echo", "cat", "type"}) List<String> allowedCommands){
            // user.dir 是运行时值，不能放注解，用方法兜底
            public List<String> effectiveAllowedPaths() {
                return (allowedPaths == null || allowedPaths.isEmpty())
                        ? List.of(System.getProperty("user.dir"))
                        : allowedPaths;
            }
    }

    // ============================================================
    // 记忆配置
    // ============================================================
    
    public record MemoryConfig (
        @DefaultValue("true") boolean enabled,
        @DefaultValue("100") int maxHistoryPerSession,
        @DefaultValue("true") boolean autoCleanExpired,
        @DefaultValue("30") int sessionExpireDays){
    }

    public record React(int maxSteps, long maxDurationMs, int maxTokensPerRequest,
                        int duplicateCallThreshold, int maxHistoryTurns) {}
    public record Timeouts(long connectMs, long readMs, long callMs) {}
    public record Retry(int perProvider, long backoffMs) {}
    public record Routing(int failureThreshold, long cooldownSeconds) {}
    public record Stream(long sseTimeoutMs, long heartbeatMs, boolean cancelOnDisconnect) {}
    public record Tool(long timeoutMs,@DefaultValue("3") int resultMaxBytes, @DefaultValue("3") int poolSize,@DefaultValue("3") int poolQueueSize) {}
    public record Session(int ttlMinutes, long cleanupIntervalMs) {}
    public record Diag(int traceBufferSize, boolean exposeEndpoints) {}
    public record Log(boolean logRawResponse) {}
    public record Tools(String workspaceRoot, boolean commandEnabled, List<String> commandAllowlist, long commandTimeoutMs, long fileMaxBytes) {}
}

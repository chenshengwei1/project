package com.virco.agent.react;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.virco.agent.model.ChatMessage;
import com.virco.agent.model.Usage;

import lombok.Data;

@Data
// react/AgentContext.java
public class AgentContext {
    private final String sessionId;
    private final String userInput;
    private final List<ChatMessage> messages;
    private final long startAt = System.currentTimeMillis();

    // 本请求累计消耗（计费口径）
    private final AtomicLong totalPromptTokens = new AtomicLong();
    private final AtomicLong totalCompletionTokens = new AtomicLong();

    // 记录每次模型调用的 usage 快照，便于诊断"哪一步烧得多"
    private final List<UsageSnapshot> usageHistory = new CopyOnWriteArrayList<>();

    private String answer;
    private Throwable error;
    private final Map<String, Object> attributes = new HashMap<>();

    /** 记录一次模型调用的 usage。缺失时按 0 处理，永不抛异常。 */
    public void addUsage(Usage usage, int step) {
        if (usage == null) usage = Usage.ZERO;
        totalPromptTokens.addAndGet(usage.prompt());
        totalCompletionTokens.addAndGet(usage.completion());
        usageHistory.add(new UsageSnapshot(step, usage.prompt(), usage.completion(), usage.total()));
    }

    /** 兼容不带 step 的调用 */
    public void addUsage(Usage usage) { addUsage(usage, usageHistory.size() + 1); }

    public long getTotalTokens() {
        return totalPromptTokens.get() + totalCompletionTokens.get();
    }
    public long getTotalPromptTokens()     { return totalPromptTokens.get(); }
    public long getTotalCompletionTokens() { return totalCompletionTokens.get(); }
    public List<UsageSnapshot> getUsageHistory() { return List.copyOf(usageHistory); }

    /** 最后一次的 usage 才代表"当前上下文大小" */
    public Usage lastUsage() {
        if (usageHistory.isEmpty()) return Usage.ZERO;
        UsageSnapshot s = usageHistory.get(usageHistory.size() - 1);
        return new Usage(s.prompt(), s.completion(), s.total());
    }

    public record UsageSnapshot(int step, int prompt, int completion, int total) {}

    // getter/setter 省略
}
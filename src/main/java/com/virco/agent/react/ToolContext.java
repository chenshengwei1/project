package com.virco.agent.react;

import lombok.Data;

@Data
public class ToolContext {
    private final String name, argsJson;
    private final long startAt = System.currentTimeMillis();
    private String shortCircuitReason;   // CACHE_HIT / APPROVAL_DENIED / RATE_LIMITED / MOCK / IDEMPOTENT_DEDUP
    private Object result; 
    private Throwable error; 
    private boolean shortCircuited;
    // getter/setter + setShortCircuit

     /** 短路并给出替代结果。只有第一次调用生效，后续忽略。 */
    public void setShortCircuit(String reason, Object result) {
        if (this.shortCircuited) return;          // 只认第一个
        this.shortCircuited = true;
        this.shortCircuitReason = reason;
        this.result = result;
    }
}
package com.virco.agent.dialog;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

@Component
public class AgentMetrics {
    private final MeterRegistry reg;
    public AgentMetrics(MeterRegistry reg) { this.reg = reg; }
    public void llmSuccess(String p, long ms) {
        reg.counter("agent.llm.calls", "provider", p, "result", "ok").increment();
        reg.timer("agent.llm.latency", "provider", p).record(ms, TimeUnit.MILLISECONDS);
    }
    public void llmFail(String p) { reg.counter("agent.llm.calls", "provider", p, "result", "fail").increment(); }
    public void allProvidersDown() { reg.counter("agent.llm.all_down").increment(); }
    public void toolSuccess(String n) { reg.counter("agent.tool.calls", "tool", n, "result", "ok").increment(); }
    public void toolFail(String n) { reg.counter("agent.tool.calls", "tool", n, "result", "fail").increment(); }
}

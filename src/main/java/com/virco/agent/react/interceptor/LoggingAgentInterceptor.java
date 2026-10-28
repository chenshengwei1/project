package com.virco.agent.react.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.virco.agent.react.AgentContext;

@Component
public class LoggingAgentInterceptor implements AgentInterceptor {
    private static final Logger log = LoggerFactory.getLogger(LoggingAgentInterceptor.class);
    @Override public int order() { return 20; }
    @Override public void beforeAgent(AgentContext c) { log.info("agent.start session={} inputLen={}",
        c.getSessionId(), c.getUserInput().length()); }
    @Override public void afterAgent(AgentContext c) { log.info("agent.done session={} elapsed={}ms",
        c.getSessionId(), System.currentTimeMillis() - c.getStartAt()); }
    @Override public void onAgentError(AgentContext c, Throwable e) { log.error("agent.error session={} err={}",
        c.getSessionId(), e.toString()); }
}

// interceptors/MetricsLlmInterceptor.java —— 已在 LlmClient 内打点，此处可选
// 预留：缓存、脱敏、审计拦截器后续按需加 @Component 即可


package com.virco.agent.react.interceptor;

import com.virco.agent.react.AgentContext;

public interface AgentInterceptor {
    default int order() { return 0; }
    default void beforeAgent(AgentContext c) {}
    default void afterAgent(AgentContext c) {}
    default void onAgentError(AgentContext c, Throwable e) {}
}
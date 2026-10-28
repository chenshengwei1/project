package com.virco.agent.react.interceptor;

import com.virco.agent.react.ToolContext;

public interface ToolInterceptor {
    default int order() { return 0; }
    default void beforeTool(ToolContext c) {}
    default void afterTool(ToolContext c) {}
    default void onToolError(ToolContext c, Throwable e) {}
}
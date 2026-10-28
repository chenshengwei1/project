package com.virco.agent.react.interceptor;

import com.virco.agent.react.LlmContext;

public interface LlmInterceptor {
    default int order() { return 0; }
    default void beforeLlm(LlmContext c) {}
    default void onFirstToken(LlmContext c) {}
    default void onLlmChunk(LlmContext c, String delta) {}
    default void afterLlm(LlmContext c) {}
    default void onLlmError(LlmContext c, Throwable e) {}
}
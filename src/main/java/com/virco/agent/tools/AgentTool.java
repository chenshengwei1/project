package com.virco.agent.tools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {
    String name();
    String description();
    boolean idempotent() default true;
    long timeoutMs() default -1;   // -1 用全局
}
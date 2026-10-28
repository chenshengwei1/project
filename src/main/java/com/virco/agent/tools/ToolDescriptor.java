
package com.virco.agent.tools;

import java.lang.reflect.Method;
import java.util.Map;

public record ToolDescriptor(String name, String description, boolean idempotent,
                             long timeoutMs, Object bean, Method method,
                             Class<?>[] paramTypes, String[] paramNames,
                             Map<String, Object> schema, String[] required) {}
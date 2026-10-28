package com.virco.agent.tools;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.tomcat.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.virco.agent.config.AgentProperties;
import com.virco.agent.dialog.AgentMetrics;
import com.virco.agent.model.ToolSchema;
import com.virco.agent.react.ToolContext;
import com.virco.agent.react.interceptor.ToolInterceptor;

@Component
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private final Map<String, ToolDescriptor> tools = new LinkedHashMap<>();
    private final ObjectMapper om;
    private final AgentProperties agent;
    private final List<ToolInterceptor> toolInterceptors;
    private final ExecutorService pool;
    private final AgentMetrics metrics;

    public ToolRegistry(ObjectMapper om, AgentProperties agent,
                        List<ToolInterceptor> toolInterceptors, AgentMetrics metrics) {
        this.om = om; this.agent = agent; this.toolInterceptors = toolInterceptors;
        this.metrics = metrics;
        var t = agent.tool();
        this.pool = new ThreadPoolExecutor(t.poolSize(), t.poolSize(),
            60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(t.poolQueueSize()),
            r -> { Thread th = new Thread(r, "tool-pool"); th.setDaemon(true); return th; },
            new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scan() {
        ApplicationContext ctx = applicationContext;
        for (String beanName : ctx.getBeanDefinitionNames()) {
            Object bean;
            try { bean = ctx.getBean(beanName); } catch (Exception e) { continue; }
            Class<?> clazz = AopUtils.getTargetClass(bean);
            for (Method m : clazz.getDeclaredMethods()) {
                AgentTool at = m.getAnnotation(AgentTool.class);
                if (at == null) continue;
                register(bean, m, at);
            }
        }
        log.info("tool.registry size={} tools={}", tools.size(), tools.keySet());
    }

    @Autowired private ApplicationContext applicationContext;

    private void register(Object bean, Method m, AgentTool at) {
        Parameter[] params = m.getParameters();
        String[] names = new String[params.length];
        Class<?>[] types = new Class<?>[params.length];
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (int i = 0; i < params.length; i++) {
            String name = params[i].isNamePresent() ? params[i].getName()
                : "arg" + i;
            names[i] = name;
            types[i] = params[i].getType();
            props.put(name, schemaFor(params[i].getType()));
            required.add(name);
        }
        Map<String, Object> schema = Map.of(
            "type", "object",
            "properties", props,
            "required", required);
        tools.put(at.name(), new ToolDescriptor(at.name(), at.description(), at.idempotent(),
            at.timeoutMs(), bean, m, types, names, schema, required.toArray(new String[0])));
    }

    private Map<String, Object> schemaFor(Class<?> t) {
        if (t == String.class) return Map.of("type", "string");
        if (t == int.class || t == Integer.class || t == long.class || t == Long.class)
            return Map.of("type", "integer");
        if (t == double.class || t == Double.class || t == float.class || t == Float.class)
            return Map.of("type", "number");
        if (t == boolean.class || t == Boolean.class) return Map.of("type", "boolean");
        if (List.class.isAssignableFrom(t)) return Map.of("type", "array", "items", Map.of("type", "string"));
        return Map.of("type", "object");
    }

    public List<ToolSchema> schemas() {
        return tools.values().stream()
            .map(d -> new ToolSchema(d.name(), d.description(), d.schema()))
            .toList();
    }

    /** 执行，任何异常都转成错误文本回填（不抛出） */
    public String execute(String name, String argsJson) {
        ToolDescriptor d = tools.get(name);
        if (d == null) 
            return errorJson("UNKNOWN_TOOL", "未知工具: " + name);

        ToolContext ctx = new ToolContext(name, argsJson);
        for (ToolInterceptor i : toolInterceptors) {
            try { i.beforeTool(ctx); } catch (Exception e) { log.warn("interceptor.before.tool.fail", e); }
        }

        if (ctx.isShortCircuited()) 
            return ctx.getResult() == null ? "null" : ctx.getResult().toString();

        long timeout = d.timeoutMs() > 0 ? d.timeoutMs() : agent.tool().timeoutMs();
        Future<String> f = pool.submit(() -> invoke(d, argsJson));

        try {
            String result = f.get(timeout, TimeUnit.MILLISECONDS);
            ctx.setResult(result);
            for (int i = toolInterceptors.size() - 1; i >= 0; i--){
                final int index = i;
                safe(() -> toolInterceptors.get(index).afterTool(ctx));
            }
            metrics.toolSuccess(name);
            return truncate(result);
        } catch (TimeoutException e) {
            log.debug("name={}, argsJSON={}",name, argsJson);
            log.error("超时异常", e);
            f.cancel(true);
            String err = errorJson("TOOL_TIMEOUT", "工具执行超时(" + timeout + "ms)");
            ctx.setError(e);
            for (int i = toolInterceptors.size() - 1; i >= 0; i--){
                final int index = i;
                safe(() -> toolInterceptors.get(index).onToolError(ctx, e));
            }
            metrics.toolFail(name);
            return err;
        } catch (Exception e) {
            log.debug("name={}, argsJSON={}",name, argsJson);
            log.error("工具执行异常", e);
            Throwable cause = e.getCause() != null ? e.getCause() : e;

            // 专门解包 InvocationTargetException，拿到原始异常
            Throwable realException = ExceptionUtils.unwrapInvocationTargetException(cause);
            String realMessage = realException.getMessage();

            String err = errorJson("TOOL_ERROR", realMessage);
            ctx.setError(cause);
            for (int i = toolInterceptors.size() - 1; i >= 0; i--){
                final int index = i;
                safe(() -> toolInterceptors.get(index).onToolError(ctx, cause));
            }
            metrics.toolFail(name);
            return err;
        }
    }

    private String invoke(ToolDescriptor d, String argsJson) throws Exception {
        Object[] args = new Object[d.paramTypes().length];
        JsonNode node = om.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
        for (int i = 0; i < args.length; i++) {
            JsonNode v = node.get(d.paramNames()[i]);
            args[i] = v == null || v.isNull() ? null
                : om.treeToValue(v, d.paramTypes()[i]);
        }
        Object r = d.method().invoke(d.bean(), args);
        return r == null ? "null" : (r instanceof String s ? s : om.writeValueAsString(r));
    }

    private String truncate(String s) {
        int max = agent.tool().resultMaxBytes();
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "\n...[结果已截断]";
    }
    private String errorJson(String code, String msg) {
        return "{\"error\":\"" + code + "\",\"message\":\"" +
            (msg == null ? "" : msg.replace("\"", "'")) + "\"}";
    }
    private void safe(Runnable r) { try { r.run(); } catch (Exception ignored) {} }
}

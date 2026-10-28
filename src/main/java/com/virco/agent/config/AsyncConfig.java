package com.virco.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class AsyncConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService sseExecutor() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "sse-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(4, 32, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200), tf,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService heartbeatScheduler() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "sse-heartbeat-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return Executors.newScheduledThreadPool(2, tf);
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService mediaExecutor() {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(2, 8, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "media-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
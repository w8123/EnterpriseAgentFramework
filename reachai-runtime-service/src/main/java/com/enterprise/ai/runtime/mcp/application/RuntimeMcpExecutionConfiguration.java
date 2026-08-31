package com.enterprise.ai.runtime.mcp.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class RuntimeMcpExecutionConfiguration {

    @Bean(destroyMethod = "shutdown")
    @Qualifier("runtimeMcpExecutionExecutor")
    public ExecutorService runtimeMcpExecutionExecutor() {
        return new ThreadPoolExecutor(4, 16, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100), namedFactory("reachai-mcp-exec-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "shutdown")
    @Qualifier("runtimeMcpTimeoutScheduler")
    public ScheduledExecutorService runtimeMcpTimeoutScheduler() {
        return Executors.newScheduledThreadPool(2, namedFactory("reachai-mcp-timeout-"));
    }

    private ThreadFactory namedFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}

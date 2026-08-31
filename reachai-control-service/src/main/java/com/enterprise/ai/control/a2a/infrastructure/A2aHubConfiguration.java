package com.enterprise.ai.control.a2a.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class A2aHubConfiguration {

    @Bean
    Clock a2aHubClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecureRandom a2aHubSecureRandom() {
        return new SecureRandom();
    }

    @Bean(name = "a2aOutboxExecutor", destroyMethod = "shutdown")
    ExecutorService a2aOutboxExecutor(A2aHubProperties properties) {
        int workers = Math.max(1, Math.min(properties.getOutboxWorkers(), 32));
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(workers, task -> {
            Thread thread = new Thread(task, "a2a-outbox-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean(name = "a2aOutboundPollExecutor", destroyMethod = "shutdown")
    ExecutorService a2aOutboundPollExecutor(A2aHubProperties properties) {
        int workers = Math.max(1, Math.min(properties.getOutbound().getPollWorkers(), 32));
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(workers, task -> {
            Thread thread = new Thread(task, "a2a-outbound-poll-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }
}

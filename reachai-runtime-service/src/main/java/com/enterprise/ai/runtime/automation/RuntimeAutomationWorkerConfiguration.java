package com.enterprise.ai.runtime.automation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
@ConditionalOnProperty(name = "reachai.runtime.automation.enabled", havingValue = "true")
class RuntimeAutomationWorkerConfiguration {

    @Bean(name = "runtimeAutomationExecutor")
    ThreadPoolTaskExecutor runtimeAutomationExecutor(
            @Value("${reachai.runtime.automation.worker-concurrency:4}") int concurrency) {
        int size = Math.max(1, Math.min(32, concurrency));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("automation-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService runtimeAutomationTimeoutScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "automation-timeout");
            thread.setDaemon(true);
            return thread;
        });
    }
}

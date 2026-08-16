package com.enterprise.ai.control.context;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/** Dedicated bounded executor so passive extraction cannot exhaust request or common-pool threads. */
@Configuration
public class PersonalMemoryAsyncConfiguration {

    public static final String CANDIDATE_EXECUTOR = "personalMemoryCandidateExecutor";

    @Bean(name = CANDIDATE_EXECUTOR)
    public Executor personalMemoryCandidateExecutor(
            @Value("${reachai.context.personal-memory.candidate-executor.core-size:2}") int coreSize,
            @Value("${reachai.context.personal-memory.candidate-executor.max-size:4}") int maxSize,
            @Value("${reachai.context.personal-memory.candidate-executor.queue-capacity:500}") int queueCapacity) {
        int core = Math.max(1, coreSize);
        int max = Math.max(core, maxSize);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("ctrl-personal-memory-");
        // Candidate extraction is best effort. Under sustained overload, discard the
        // newest passive observation instead of adding latency to a completed request.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}

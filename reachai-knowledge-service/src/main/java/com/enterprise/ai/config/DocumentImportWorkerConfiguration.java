package com.enterprise.ai.config;

import com.enterprise.ai.pipeline.document.job.DocumentImportJobProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** A bounded executor prevents large documents from exhausting request threads. */
@Configuration
@RequiredArgsConstructor
public class DocumentImportWorkerConfiguration {

    private final DocumentImportJobProperties properties;

    @Bean("documentImportTaskExecutor")
    public TaskExecutor documentImportTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCorePoolSize());
        executor.setMaxPoolSize(properties.getMaxPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("knowledge-document-import-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}

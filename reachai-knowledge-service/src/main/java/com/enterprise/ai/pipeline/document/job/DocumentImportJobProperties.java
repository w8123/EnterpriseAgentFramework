package com.enterprise.ai.pipeline.document.job;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Worker settings for durable parsing and indexing jobs. */
@Data
@ConfigurationProperties(prefix = "reachai.knowledge.document-import-worker")
public class DocumentImportJobProperties {

    private boolean enabled = true;
    private long pollIntervalMs = 5_000L;
    private int dispatchBatchSize = 8;
    private int maxAttempts = 3;
    private int leaseSeconds = 1_200;
    private int corePoolSize = 2;
    private int maxPoolSize = 4;
    private int queueCapacity = 32;
    private long retryInitialDelaySeconds = 10L;
    private long retryMaxDelaySeconds = 300L;
}

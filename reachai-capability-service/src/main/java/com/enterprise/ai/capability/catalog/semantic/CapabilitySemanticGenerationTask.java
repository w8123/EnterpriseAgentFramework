package com.enterprise.ai.capability.catalog.semantic;

import lombok.Data;

import java.time.Instant;

@Data
public class CapabilitySemanticGenerationTask {

    public enum Stage {QUEUED, RUNNING, DONE, FAILED}

    private String taskId;
    private Long projectId;
    private String modelInstanceId;
    private volatile Stage stage;
    private volatile int totalSteps;
    private volatile int completedSteps;
    private volatile String currentStep;
    private volatile String errorMessage;
    private volatile int totalTokens;
    private Instant startedAt;
    private volatile Instant finishedAt;
}

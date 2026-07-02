package com.enterprise.ai.capability.catalog.semantic;

import lombok.Data;

import java.time.Instant;

@Data
public class CapabilitySemanticGenerationTask {

    public enum Stage {QUEUED, RUNNING, DONE, FAILED}

    private String taskId;
    private Long projectId;
    private String modelInstanceId;
    private Stage stage;
    private int totalSteps;
    private int completedSteps;
    private String currentStep;
    private String errorMessage;
    private int totalTokens;
    private Instant startedAt;
    private Instant finishedAt;
}

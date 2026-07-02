package com.enterprise.ai.capability.catalog.retrieval;

import lombok.Data;

import java.time.Instant;

@Data
public class CapabilityToolEmbeddingRebuildTask {

    public enum Stage {QUEUED, RUNNING, DONE, FAILED}

    private String taskId;

    private Stage stage = Stage.QUEUED;

    private int totalSteps;

    private int completedSteps;

    private int successCount;

    private int skippedCount;

    private int failedCount;

    private String currentStep;

    private String embeddingModelInstanceId;

    private String errorMessage;

    private Instant startedAt;

    private Instant finishedAt;
}

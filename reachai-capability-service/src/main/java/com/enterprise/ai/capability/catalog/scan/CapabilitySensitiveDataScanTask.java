package com.enterprise.ai.capability.catalog.scan;

import java.time.Instant;

public class CapabilitySensitiveDataScanTask {

    public enum Stage { QUEUED, RUNNING, DONE, FAILED }

    private String taskId;
    private Long projectId;
    private String modelInstanceId;
    private Stage stage;
    private int totalSteps;
    private int completedSteps;
    private int failedCount;
    private String currentStep;
    private String errorMessage;
    private int totalTokens;
    private Instant startedAt;
    private Instant finishedAt;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getModelInstanceId() {
        return modelInstanceId;
    }

    public void setModelInstanceId(String modelInstanceId) {
        this.modelInstanceId = modelInstanceId;
    }

    public Stage getStage() {
        return stage;
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public int getTotalSteps() {
        return totalSteps;
    }

    public void setTotalSteps(int totalSteps) {
        this.totalSteps = totalSteps;
    }

    public int getCompletedSteps() {
        return completedSteps;
    }

    public void setCompletedSteps(int completedSteps) {
        this.completedSteps = completedSteps;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(int failedCount) {
        this.failedCount = failedCount;
    }

    public String getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(String currentStep) {
        this.currentStep = currentStep;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }
}

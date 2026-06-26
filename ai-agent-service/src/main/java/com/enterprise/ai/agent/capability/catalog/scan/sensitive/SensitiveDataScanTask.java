package com.enterprise.ai.agent.capability.catalog.scan.sensitive;

import lombok.Data;

import java.time.Instant;

/**
 * 敏感数据批量扫描任务（内存态，供前端轮询）。
 */
@Data
public class SensitiveDataScanTask {

    public enum Stage { QUEUED, RUNNING, DONE, FAILED }

    private String taskId;
    private Long projectId;
    private String modelInstanceId;
    private Stage stage;
    private int totalSteps;
    private int completedSteps;
    /** 单条扫描失败条数（LLM 或解析失败） */
    private int failedCount;
    private String currentStep;
    private String errorMessage;
    private int totalTokens;
    private Instant startedAt;
    private Instant finishedAt;
}

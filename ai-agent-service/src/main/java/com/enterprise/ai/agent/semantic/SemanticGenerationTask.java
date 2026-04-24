package com.enterprise.ai.agent.semantic;

import lombok.Data;

import java.time.Instant;

/**
 * 语义生成批量任务状态快照（内存态，供前端轮询进度）。
 */
@Data
public class SemanticGenerationTask {

    public enum Stage { QUEUED, RUNNING, DONE, FAILED }

    private String taskId;
    private Long projectId;
    /** 批量生成使用的模型网关 Provider，可为空（走默认） */
    private String llmProvider;
    /** 批量生成使用的模型名，可为空（走 agentscope.model.name） */
    private String llmModel;
    private Stage stage;
    private int totalSteps;
    private int completedSteps;
    private String currentStep;
    private String errorMessage;
    private int totalTokens;
    private Instant startedAt;
    private Instant finishedAt;
}

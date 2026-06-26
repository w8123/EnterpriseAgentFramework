package com.enterprise.ai.agent.capability.catalog.tool.retrieval;

import lombok.Data;

import java.time.Instant;

/**
 * 工具向量索引异步重建任务快照。
 * 字段语义对齐 {@code SemanticGenerationTask}，便于前端复用进度组件。
 */
@Data
public class ToolEmbeddingRebuildTask {

    public enum Stage {QUEUED, RUNNING, DONE, FAILED}

    private String taskId;

    private Stage stage = Stage.QUEUED;

    private int totalSteps;

    private int completedSteps;

    private int successCount;

    private int skippedCount;

    private int failedCount;

    private String currentStep;

    /** 本次重建实际使用的模型实例 ID（请求指定或回落到配置） */
    private String embeddingModelInstanceId;

    private String errorMessage;

    private Instant startedAt;

    private Instant finishedAt;
}

package com.enterprise.ai.pipeline;

import lombok.Getter;

/**
 * Pipeline 专用异常 — 携带失败步骤名称与上下文信息，便于问题定位。
 */
@Getter
public class PipelineException extends RuntimeException {

    /** 失败的步骤名称 */
    private final String stepName;

    /** 失败时的 fileId（便于追踪） */
    private final String fileId;

    public PipelineException(String stepName, String fileId, String message) {
        super(String.format("[%s] fileId=%s: %s", stepName, fileId, message));
        this.stepName = stepName;
        this.fileId = fileId;
    }

    public PipelineException(String stepName, String fileId, String message, Throwable cause) {
        super(String.format("[%s] fileId=%s: %s", stepName, fileId, message), cause);
        this.stepName = stepName;
        this.fileId = fileId;
    }
}

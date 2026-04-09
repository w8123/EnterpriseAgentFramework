package com.enterprise.ai.domain.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Pipeline 执行结果 — 返回入库流水线的执行摘要。
 */
@Data
@Builder
public class PipelineResult {

    /** 文件业务ID */
    private String fileId;

    /** 知识库编码 */
    private String knowledgeBaseCode;

    /** 切分产生的 chunk 数量 */
    private int chunkCount;

    /** 向量数量 */
    private int vectorCount;

    /** 各步骤耗时 (stepName → ms) */
    private Map<String, Long> stepDurations;

    /** 执行状态: SUCCESS / FAILED / ABORTED */
    private String status;

    /** 错误信息（仅失败时有值） */
    private String errorMessage;
}

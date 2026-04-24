package com.enterprise.ai.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 知识库 chunk 策略配置请求 DTO
 */
@Data
public class KbConfigRequest {

    @Min(value = 100, message = "chunkSize 最小为 100")
    @Max(value = 4000, message = "chunkSize 最大为 4000")
    private Integer chunkSize;

    @Min(value = 0, message = "overlap 不能为负数")
    @Max(value = 1000, message = "overlap 最大为 1000")
    private Integer chunkOverlap;

    /** 切分策略: FIXED / PARAGRAPH / SEMANTIC */
    private String splitType;
}

package com.enterprise.ai.bizindex.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量推送请求（仅结构化字段，不含附件）
 */
@Data
public class BizBatchUpsertRequest {

    @NotEmpty(message = "数据列表不能为空")
    @Valid
    private List<BizUpsertRequest> items;
}

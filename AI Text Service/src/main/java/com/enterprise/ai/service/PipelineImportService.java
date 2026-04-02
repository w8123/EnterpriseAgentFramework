package com.enterprise.ai.service;

import com.enterprise.ai.domain.dto.PipelineResult;
import com.enterprise.ai.pipeline.PipelineContext;

/**
 * Pipeline 入库服务接口 — 编排知识文件的入库流水线。
 */
public interface PipelineImportService {

    /**
     * 执行入库流水线
     *
     * @param context 已填充输入参数的 Pipeline 上下文
     * @return 执行结果摘要
     */
    PipelineResult execute(PipelineContext context);
}

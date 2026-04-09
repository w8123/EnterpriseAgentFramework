package com.enterprise.ai.rag;

import com.enterprise.ai.domain.dto.RagRequest;
import com.enterprise.ai.domain.dto.RagResponse;

/**
 * RAG 服务接口 — 编排检索增强生成的完整流程。
 */
public interface RagService {

    /**
     * 执行 RAG 流程：embedding → 多库检索 → 权限过滤 → TopK合并 → Prompt构建 → LLM生成
     */
    RagResponse query(RagRequest request);
}

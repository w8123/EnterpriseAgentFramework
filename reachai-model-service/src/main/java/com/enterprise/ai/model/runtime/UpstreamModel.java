package com.enterprise.ai.model.runtime;

/**
 * 供应商 {@code GET /models} 返回的一项可用模型。
 *
 * @param id           供应商侧模型 ID，可直接作为实例 modelName
 * @param ownedBy      供应商返回的归属方，可为空
 * @param created      供应商返回的创建时间（epoch 秒），可为空
 * @param inferredType 按模型 ID 推断的类型：LLM / EMBEDDING / RERANKER / OTHER，仅用于前端筛选
 */
public record UpstreamModel(String id, String ownedBy, Long created, String inferredType) {
}

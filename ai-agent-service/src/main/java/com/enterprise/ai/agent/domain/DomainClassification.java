package com.enterprise.ai.agent.domain;

/**
 * 领域分类一项命中。
 *
 * @param domainCode 领域 code
 * @param score      匹配得分；关键词分类器为命中关键词词频累加（去重后），未来可换为模型概率
 */
public record DomainClassification(String domainCode, double score) {}

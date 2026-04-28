package com.enterprise.ai.agent.domain;

import java.util.List;

/**
 * 领域分类器接口。先由 {@link KeywordDomainClassifier} 实现，未来可替换为小模型 / Embedding 分类器。
 */
public interface DomainClassifier {

    /**
     * 给定用户问题，返回 top-K 领域命中（按 score 降序）。
     */
    List<DomainClassification> classify(String userText, int topK);
}

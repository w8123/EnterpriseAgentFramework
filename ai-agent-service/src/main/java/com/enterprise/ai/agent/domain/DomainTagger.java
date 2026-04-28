package com.enterprise.ai.agent.domain;

import com.enterprise.ai.agent.config.DomainProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 领域分类 hook：在 AgentRouter / Tool Retrieval 入口调用，把识别到的领域注入 RetrievalScope。
 * <p>分类结果空 / 异常都不阻塞主链路（软过滤）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainTagger {

    private final DomainClassifier classifier;
    private final DomainProperties properties;

    /**
     * 返回 top-K 领域 code（按 score 降序）；若开关关闭、无命中、或抛异常返回 {@code null}（外层据此判断是否过滤）。
     */
    public List<String> tag(String userText) {
        if (!properties.isEnabled()) return null;
        if (userText == null || userText.isBlank()) return null;
        try {
            List<DomainClassification> out = classifier.classify(userText, properties.getTopK());
            if (out.isEmpty()) return null;
            return out.stream().map(DomainClassification::domainCode).toList();
        } catch (Exception ex) {
            log.debug("[DomainTagger] classify 抛异常，跳过: {}", ex.toString());
            return null;
        }
    }
}

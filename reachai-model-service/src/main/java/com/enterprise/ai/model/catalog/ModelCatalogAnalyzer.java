package com.enterprise.ai.model.catalog;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class ModelCatalogAnalyzer {

    private final ModelCatalogStructuredParser structuredParser;
    private final ModelCatalogAiAnalyzer aiAnalyzer;

    ModelCatalogAnalysis analyze(ModelCatalogSourceEntity source, String content) {
        if ("STRUCTURED_API".equalsIgnoreCase(source.getSourceKind())) {
            return structuredParser.parse(source, content);
        }
        return aiAnalyzer.analyze(source, content);
    }
}

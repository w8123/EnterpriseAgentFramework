package com.enterprise.ai.model.catalog;

import com.enterprise.ai.model.service.ModelRoutingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ModelCatalogAiAnalyzerTest {

    private ModelCatalogAiAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new ModelCatalogAiAnalyzer(
                new ModelCatalogSyncProperties(),
                mock(ModelRoutingService.class),
                new ObjectMapper());
    }

    @Test
    void parsesStrictJsonAndBoundsConfidence() throws Exception {
        List<ModelCatalogCandidate> candidates = analyzer.parseCandidates("""
                {"models":[{"modelName":"gpt-5.6-sol","displayName":"GPT-5.6 Sol",
                "modelType":"llm","lifecycleStatus":"active","capabilities":{"tools":true},
                "evidenceExcerpt":"gpt-5.6-sol","confidence":1.4}]}
                """);

        assertEquals(1, candidates.size());
        assertEquals("LLM", candidates.get(0).modelType());
        assertEquals("ACTIVE", candidates.get(0).lifecycleStatus());
        assertEquals(1.0d, candidates.get(0).confidence());
        assertEquals(true, candidates.get(0).capabilities().get("tools"));
    }

    @Test
    void extractsJsonFromFenceButRejectsMissingObject() {
        assertEquals("{\"models\":[]}", analyzer.extractJson("```json\n{\"models\":[]}\n```"));
        ModelCatalogSyncException error = assertThrows(
                ModelCatalogSyncException.class,
                () -> analyzer.extractJson("not json"));
        assertEquals("MODEL_CATALOG_ANALYZER_JSON_INVALID", error.code());
    }
}

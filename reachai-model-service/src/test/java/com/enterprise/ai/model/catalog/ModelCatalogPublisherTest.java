package com.enterprise.ai.model.catalog;

import com.enterprise.ai.model.template.ModelTemplateEntity;
import com.enterprise.ai.model.template.ModelTemplateMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelCatalogPublisherTest {

    private ModelCatalogChangeMapper changeMapper;
    private ModelTemplateMapper templateMapper;
    private ModelCatalogSyncProperties properties;
    private ModelCatalogPublisher publisher;

    @BeforeEach
    void setUp() {
        changeMapper = mock(ModelCatalogChangeMapper.class);
        templateMapper = mock(ModelTemplateMapper.class);
        properties = new ModelCatalogSyncProperties();
        properties.setAutoPublishSafeFacts(true);
        properties.setAutoPublishMinConfidence(0.92d);
        when(templateMapper.updateById(any(ModelTemplateEntity.class))).thenReturn(1);
        publisher = new ModelCatalogPublisher(
                changeMapper,
                templateMapper,
                new ModelCatalogTemplateDefaults(),
                properties,
                new ObjectMapper());
    }

    @Test
    void publishesEvidencedActiveModelButLeavesRecommendationUnassessed() {
        AtomicReference<ModelTemplateEntity> inserted = new AtomicReference<>();
        when(templateMapper.findByCatalogKey("openai", "LLM", "gpt-5.6-sol"))
                .thenAnswer(invocation -> inserted.get());
        when(templateMapper.findByProviderAndModelName("openai", "gpt-5.6-sol")).thenReturn(List.of());
        doAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        }).when(templateMapper).insertIgnore(any(ModelTemplateEntity.class));

        ModelCatalogPublisher.PublishSummary summary = publisher.publish(
                source(), run(), snapshot("Official model id: gpt-5.6-sol"),
                analysis(candidate("gpt-5.6-sol", "ACTIVE", 0.99d)));

        assertEquals(1, summary.candidateCount());
        assertEquals(1, summary.publishedCount());
        assertEquals("UNASSESSED", inserted.get().getRecommendationStatus());
        assertTrue(inserted.get().getSyncManaged());
        assertTrue(inserted.get().getEnabled());
        ArgumentCaptor<ModelCatalogChangeEntity> change = ArgumentCaptor.forClass(ModelCatalogChangeEntity.class);
        verify(changeMapper).insertIgnore(change.capture());
        assertEquals("PUBLISHED", change.getValue().getPublishStatus());
    }

    @Test
    void lowConfidenceCandidateRequiresReviewAndCannotCreateTemplate() {
        when(templateMapper.findByCatalogKey(anyString(), anyString(), anyString())).thenReturn(null);
        when(templateMapper.findByProviderAndModelName(anyString(), anyString())).thenReturn(List.of());

        ModelCatalogPublisher.PublishSummary summary = publisher.publish(
                source(), run(), snapshot("Official model id: gpt-5.6-sol"),
                analysis(candidate("gpt-5.6-sol", "ACTIVE", 0.5d)));

        assertEquals(1, summary.reviewCount());
        verify(templateMapper, never()).insertIgnore(any());
        ArgumentCaptor<ModelCatalogChangeEntity> change = ArgumentCaptor.forClass(ModelCatalogChangeEntity.class);
        verify(changeMapper).insertIgnore(change.capture());
        assertEquals("REVIEW_REQUIRED", change.getValue().getPublishStatus());
    }

    @Test
    void analyzerExcerptMustBeTraceableToOfficialSnapshot() {
        when(templateMapper.findByCatalogKey(anyString(), anyString(), anyString())).thenReturn(null);
        when(templateMapper.findByProviderAndModelName(anyString(), anyString())).thenReturn(List.of());
        ModelCatalogCandidate hallucinatedEvidence = new ModelCatalogCandidate(
                "gpt-5.6-sol",
                "GPT-5.6 Sol",
                "LLM",
                "ACTIVE",
                null,
                null,
                null,
                null,
                null,
                Map.of("supportsChatCompletions", true),
                "gpt-5.6-sol supports the chat completions endpoint",
                0.99d);

        ModelCatalogPublisher.PublishSummary summary = publisher.publish(
                source(), run(), snapshot("Official model id: gpt-5.6-sol"),
                analysis(hallucinatedEvidence));

        assertEquals(1, summary.reviewCount());
        verify(templateMapper, never()).insertIgnore(any());
        ArgumentCaptor<ModelCatalogChangeEntity> change = ArgumentCaptor.forClass(ModelCatalogChangeEntity.class);
        verify(changeMapper).insertIgnore(change.capture());
        assertEquals("REJECTED", change.getValue().getValidationStatus());
        assertTrue(change.getValue().getReviewReason().contains("cannot be located"));
    }

    @Test
    void currentListCannotReactivateRetiredTemplate() {
        ModelTemplateEntity retired = new ModelTemplateEntity();
        retired.setId("tpl-retired");
        retired.setName("Retired model");
        retired.setProvider("openai");
        retired.setModelType("LLM");
        retired.setModelName("gpt-retired");
        retired.setLifecycleStatus("RETIRED");
        retired.setEnabled(false);
        retired.setCapabilitiesJson("{}");
        when(templateMapper.findByCatalogKey("openai", "LLM", "gpt-retired")).thenReturn(retired);

        ModelCatalogPublisher.PublishSummary summary = publisher.publish(
                source(), run(), snapshot("Current API list includes gpt-retired"),
                analysis(candidate("gpt-retired", "ACTIVE", 1.0d)));

        assertEquals("RETIRED", retired.getLifecycleStatus());
        assertFalse(retired.getEnabled());
        assertEquals(1, summary.candidateCount());
        verify(templateMapper).updateById(retired);
    }

    private ModelCatalogSourceEntity source() {
        ModelCatalogSourceEntity source = new ModelCatalogSourceEntity();
        source.setId(1L);
        source.setSourceKey("openai-models");
        source.setProvider("openai");
        source.setSourceUrl("https://developers.openai.com/api/docs/models/all.md");
        source.setAllowedHost("developers.openai.com");
        source.setTrustLevel("OFFICIAL");
        return source;
    }

    private ModelCatalogSyncRunEntity run() {
        ModelCatalogSyncRunEntity run = new ModelCatalogSyncRunEntity();
        run.setId(10L);
        return run;
    }

    private ModelCatalogSnapshotEntity snapshot(String content) {
        ModelCatalogSnapshotEntity snapshot = new ModelCatalogSnapshotEntity();
        snapshot.setId(20L);
        snapshot.setContentSha256("a".repeat(64));
        snapshot.setNormalizedContent(content);
        snapshot.setFetchedAt(LocalDateTime.now());
        return snapshot;
    }

    private ModelCatalogAnalysis analysis(ModelCatalogCandidate candidate) {
        return new ModelCatalogAnalysis(List.of(candidate), "{\"models\":[]}", "AI_SUCCESS");
    }

    private ModelCatalogCandidate candidate(String modelName, String lifecycle, double confidence) {
        return new ModelCatalogCandidate(
                modelName,
                "gpt-retired".equals(modelName) ? "Retired model" : modelName,
                "LLM",
                lifecycle,
                null,
                null,
                null,
                null,
                null,
                Map.of("supportsTools", true, "supportsChatCompletions", true),
                modelName,
                confidence);
    }
}

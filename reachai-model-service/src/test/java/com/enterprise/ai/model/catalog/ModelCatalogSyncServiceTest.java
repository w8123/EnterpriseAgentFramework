package com.enterprise.ai.model.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelCatalogSyncServiceTest {

    private ModelCatalogSourceMapper sourceMapper;
    private ModelCatalogSyncRunMapper runMapper;
    private ModelCatalogSnapshotMapper snapshotMapper;
    private ModelCatalogHttpFetcher fetcher;
    private ModelCatalogAnalyzer analyzer;
    private ModelCatalogPublisher publisher;
    private ModelCatalogSyncService service;

    @BeforeEach
    void setUp() {
        sourceMapper = mock(ModelCatalogSourceMapper.class);
        runMapper = mock(ModelCatalogSyncRunMapper.class);
        snapshotMapper = mock(ModelCatalogSnapshotMapper.class);
        fetcher = mock(ModelCatalogHttpFetcher.class);
        analyzer = mock(ModelCatalogAnalyzer.class);
        publisher = mock(ModelCatalogPublisher.class);
        service = new ModelCatalogSyncService(
                sourceMapper,
                runMapper,
                snapshotMapper,
                fetcher,
                analyzer,
                publisher,
                new ModelCatalogSyncProperties());
    }

    @Test
    void persistsSourceSuccessBeforeClosingDailySlot() {
        Fixture fixture = successfulFixture();
        when(sourceMapper.markSuccess(eq(1L), any(), any(), any(), anyString())).thenReturn(1);
        when(runMapper.complete(eq(10L), eq("lease-token"), eq("SUCCESS"), anyInt(), anyString(),
                eq(20L), eq("AI_SUCCESS"), eq(1), eq(1), eq(0))).thenReturn(1);

        service.process(fixture.run(), "lease-token");

        InOrder order = inOrder(sourceMapper, runMapper);
        order.verify(sourceMapper).markSuccess(eq(1L), any(), any(), any(), anyString());
        order.verify(runMapper).complete(eq(10L), eq("lease-token"), eq("SUCCESS"), anyInt(), anyString(),
                eq(20L), eq("AI_SUCCESS"), eq(1), eq(1), eq(0));
    }

    @Test
    void doesNotCloseDailySlotWhenSourceSuccessStateCannotBePersisted() {
        Fixture fixture = successfulFixture();
        when(sourceMapper.markSuccess(eq(1L), any(), any(), any(), anyString())).thenReturn(0);

        ModelCatalogSyncException error = assertThrows(
                ModelCatalogSyncException.class,
                () -> service.process(fixture.run(), "lease-token"));

        assertEquals("MODEL_CATALOG_SOURCE_STATE_PERSIST_FAILED", error.code());
        verify(runMapper, never()).complete(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
    }

    private Fixture successfulFixture() {
        ModelCatalogSourceEntity source = new ModelCatalogSourceEntity();
        source.setId(1L);
        source.setEnabled(true);
        source.setSourceUrl("https://developers.openai.com/api/docs/models/all.md");
        source.setSourceKind("OFFICIAL_PAGE");
        when(sourceMapper.selectById(1L)).thenReturn(source);

        ModelCatalogSyncRunEntity run = new ModelCatalogSyncRunEntity();
        run.setId(10L);
        run.setSourceId(1L);
        when(runMapper.markRunning(10L, "lease-token")).thenReturn(1);
        when(runMapper.recordSnapshot(eq(10L), eq("lease-token"), eq(200), anyString(), eq(20L), eq("ANALYZING")))
                .thenReturn(1);
        when(runMapper.renew(eq(10L), eq("lease-token"), any())).thenReturn(1);

        String hash = "a".repeat(64);
        when(fetcher.fetch(source)).thenReturn(new ModelCatalogHttpFetcher.FetchResult(
                200,
                false,
                "Official model id: gpt-5.6-sol",
                hash,
                31,
                "text/markdown",
                null,
                null,
                "{}",
                LocalDateTime.now()));
        doAnswer(invocation -> {
            ModelCatalogSnapshotEntity snapshot = invocation.getArgument(0);
            snapshot.setId(20L);
            return 1;
        }).when(snapshotMapper).insertOrResolve(any(ModelCatalogSnapshotEntity.class));
        when(snapshotMapper.recordAnalysis(20L, "{\"models\":[]}" )).thenReturn(1);

        ModelCatalogAnalysis analysis = new ModelCatalogAnalysis(List.of(), "{\"models\":[]}", "AI_SUCCESS");
        when(analyzer.analyze(source, "Official model id: gpt-5.6-sol")).thenReturn(analysis);
        when(publisher.publish(eq(source), eq(run), any(ModelCatalogSnapshotEntity.class), eq(analysis)))
                .thenReturn(new ModelCatalogPublisher.PublishSummary(1, 1, 0));
        return new Fixture(run);
    }

    private record Fixture(ModelCatalogSyncRunEntity run) {
    }
}

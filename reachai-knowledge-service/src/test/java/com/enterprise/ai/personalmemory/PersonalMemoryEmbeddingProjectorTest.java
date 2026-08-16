package com.enterprise.ai.personalmemory;

import com.enterprise.ai.embedding.EmbeddingService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class PersonalMemoryEmbeddingProjectorTest {

    @Test
    void claimsAndCompletesARebuildableVectorProjection() {
        Fixture fixture = fixture(12);
        KnowledgePersonalMemoryIndexEntity candidate = candidate(1L, 7L, 0);
        when(fixture.mapper.selectEmbeddingCandidates(any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(candidate));
        when(fixture.mapper.claimEmbedding(eq(1L), eq(7L), anyString(), eq(1), any(), any(),
                eq("memory-embedding"), eq(PersonalMemoryEmbeddingCodec.FORMAT))).thenReturn(1);
        when(fixture.embeddingService.embedBatch(eq("memory-embedding"), anyList()))
                .thenReturn(List.of(List.of(.25f, .75f)));
        when(fixture.mapper.completeEmbedding(eq(1L), eq(7L), anyString(), any(byte[].class),
                eq(PersonalMemoryEmbeddingCodec.FORMAT), eq(2), eq("memory-embedding"),
                anyString(), any())).thenReturn(1);

        var result = fixture.projector.runOnce();

        assertEquals(1, result.ready());
        assertEquals(0, result.failed());
        ArgumentCaptor<byte[]> encoded = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);
        verify(fixture.mapper).completeEmbedding(eq(1L), eq(7L), anyString(), encoded.capture(),
                eq(PersonalMemoryEmbeddingCodec.FORMAT), eq(2), eq("memory-embedding"),
                digest.capture(), any());
        assertEquals(8, encoded.getValue().length);
        assertEquals(64, digest.getValue().length());
        assertNotEquals("用户偏好\n回答简洁", digest.getValue());
    }

    @Test
    void providerFailureStoresOnlyAMachineCodeAndEventuallyDeadLetters() {
        Fixture fixture = fixture(2);
        KnowledgePersonalMemoryIndexEntity candidate = candidate(1L, 7L, 1);
        when(fixture.mapper.selectEmbeddingCandidates(any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(candidate));
        when(fixture.mapper.claimEmbedding(eq(1L), eq(7L), anyString(), eq(2), any(), any(),
                anyString(), anyString())).thenReturn(1);
        when(fixture.embeddingService.embedBatch(anyString(), anyList()))
                .thenThrow(new IllegalStateException("sensitive provider response"));
        when(fixture.mapper.failEmbedding(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), nullable(LocalDateTime.class), any())).thenReturn(1);

        var result = fixture.projector.runOnce();

        assertEquals(1, result.dead());
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errorCode = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> nextAttempt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(fixture.mapper).failEmbedding(eq(1L), eq(7L), anyString(), status.capture(),
                eq("memory-embedding"), eq(PersonalMemoryEmbeddingCodec.FORMAT),
                errorCode.capture(), nextAttempt.capture(), any());
        assertEquals("DEAD", status.getValue());
        assertEquals("EMBEDDING_PROVIDER_ERROR", errorCode.getValue());
        assertNull(nextAttempt.getValue());
    }

    @Test
    void staleCompletionCannotOverwriteANewerSourceVersion() {
        Fixture fixture = fixture(12);
        KnowledgePersonalMemoryIndexEntity candidate = candidate(1L, 7L, 0);
        when(fixture.mapper.selectEmbeddingCandidates(any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(candidate));
        when(fixture.mapper.claimEmbedding(eq(1L), eq(7L), anyString(), eq(1), any(), any(),
                anyString(), anyString())).thenReturn(1);
        when(fixture.embeddingService.embedBatch(anyString(), anyList()))
                .thenReturn(List.of(List.of(1f, 0f)));
        when(fixture.mapper.completeEmbedding(eq(1L), eq(7L), anyString(), any(byte[].class),
                anyString(), anyInt(), anyString(), anyString(), any())).thenReturn(0);

        var result = fixture.projector.runOnce();

        assertEquals(1, result.stale());
        verify(fixture.mapper).releaseEmbeddingClaim(eq(1L), anyString());
    }

    @Test
    void inconsistentProviderDimensionsFailTheBatchWithoutPublishingMixedVectors() {
        Fixture fixture = fixture(12);
        when(fixture.mapper.selectEmbeddingCandidates(any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(candidate(1L, 7L, 0), candidate(2L, 8L, 0)));
        when(fixture.mapper.claimEmbedding(anyLong(), anyLong(), anyString(), anyInt(), any(), any(),
                anyString(), anyString())).thenReturn(1);
        when(fixture.embeddingService.embedBatch(anyString(), anyList()))
                .thenReturn(List.of(List.of(1f, 0f), List.of(1f, 0f, 0f)));
        when(fixture.mapper.failEmbedding(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), nullable(LocalDateTime.class), any())).thenReturn(1);

        var result = fixture.projector.runOnce();

        assertEquals(2, result.retry());
        verify(fixture.mapper, never()).completeEmbedding(anyLong(), anyLong(), anyString(),
                any(byte[].class), anyString(), anyInt(), anyString(), anyString(), any());
    }

    @Test
    void neverMixesDifferentTenantsInOneProviderBatch() {
        Fixture fixture = fixture(12);
        KnowledgePersonalMemoryIndexEntity ownerA = candidate(1L, 7L, 0);
        KnowledgePersonalMemoryIndexEntity ownerB = candidate(2L, 8L, 0);
        ownerB.setTenantId("tenant-b");
        when(fixture.mapper.selectEmbeddingCandidates(any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(ownerA, ownerB));
        when(fixture.mapper.claimEmbedding(anyLong(), anyLong(), anyString(), anyInt(), any(), any(),
                anyString(), anyString())).thenReturn(1);
        when(fixture.embeddingService.embedBatch(anyString(), anyList()))
                .thenAnswer(invocation -> List.of(List.of(1f, 0f)));
        when(fixture.mapper.completeEmbedding(anyLong(), anyLong(), anyString(), any(byte[].class),
                anyString(), anyInt(), anyString(), anyString(), any())).thenReturn(1);

        var result = fixture.projector.runOnce();

        assertEquals(2, result.ready());
        ArgumentCaptor<List<String>> batches = ArgumentCaptor.forClass(List.class);
        verify(fixture.embeddingService, times(2)).embedBatch(eq("memory-embedding"), batches.capture());
        assertEquals(List.of(1, 1), batches.getAllValues().stream().map(List::size).toList());
    }

    private static Fixture fixture(int maxAttempts) {
        KnowledgePersonalMemoryIndexMapper mapper = mock(KnowledgePersonalMemoryIndexMapper.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        PersonalMemoryEmbeddingProperties properties = new PersonalMemoryEmbeddingProperties();
        properties.setMode(PersonalMemoryEmbeddingProperties.Mode.HYBRID);
        properties.setModelInstanceId("memory-embedding");
        properties.setMaxAttempts(maxAttempts);
        properties.afterPropertiesSet();
        PersonalMemoryEmbeddingProjector projector = new PersonalMemoryEmbeddingProjector(
                mapper, embeddingService, properties, new SimpleMeterRegistry());
        return new Fixture(projector, mapper, embeddingService);
    }

    private static KnowledgePersonalMemoryIndexEntity candidate(Long id, Long version, int attempts) {
        KnowledgePersonalMemoryIndexEntity entity = new KnowledgePersonalMemoryIndexEntity();
        entity.setId(id);
        entity.setMemoryId(id);
        entity.setTenantId("tenant-a");
        entity.setRuntimeUserHash("owner-a");
        entity.setSourceVersion(version);
        entity.setTitle("用户偏好");
        entity.setContent("回答简洁");
        entity.setStatus("ACTIVE");
        entity.setEmbeddingStatus("PENDING");
        entity.setEmbeddingAttempts(attempts);
        return entity;
    }

    private record Fixture(PersonalMemoryEmbeddingProjector projector,
                           KnowledgePersonalMemoryIndexMapper mapper,
                           EmbeddingService embeddingService) {
    }
}

package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class McpPublishedReferenceReaderTest {
    final McpPublicationMapper publications = mock(McpPublicationMapper.class);
    final McpPublicationRevisionMapper revisions = mock(McpPublicationRevisionMapper.class);
    final McpPublishedReferenceReader reader = new McpPublishedReferenceReader(publications, revisions, new ObjectMapper());

    @org.junit.jupiter.api.BeforeEach void entityMetadata() {
        for (Class<?> type : List.of(McpPublicationEntity.class, McpPublicationRevisionEntity.class))
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                    new com.baomidou.mybatisplus.core.MybatisConfiguration(), "mcp-reference"), type);
    }

    @Test void readsAllReachableRevisionsInOneBatchAcrossProjects() {
        when(publications.selectList(any())).thenReturn(List.of(publication(1L, 12L), publication(2L, 21L)));
        frozen(List.of(revision(11L, 1L), revision(12L, 1L), revision(21L, 2L)));
        var evidence = reader.inspect();
        assertTrue(evidence.complete());
        assertEquals(3, evidence.bindings().size());
        assertEquals("other:read", evidence.bindings().get(0).sourceRef());
        verify(revisions, times(1)).referenceMetadata(any(), anyInt());
        verify(revisions, times(1)).referenceBodies(any());
        assertThrows(UnsupportedOperationException.class, () -> evidence.bindings().clear());
    }

    @Test void missingCurrentRevisionAndMalformedSnapshotsArePartial() {
        when(publications.selectList(any())).thenReturn(List.of(publication(1L, 12L)));
        frozen(List.of(revision(11L, 1L)));
        assertFalse(reader.inspect().complete());
        var malformed = revision(12L, 1L); malformed.setToolsSnapshotJson("null");
        frozen(List.of(malformed));
        assertFalse(reader.inspect().complete());
    }

    @Test void cachedBindingsRetainHistoricalPinsButAlwaysRefreshStateNameAndFingerprint() {
        var publication = publication(1L, 12L);
        var old = revision(11L, 1L); var current = revision(12L, 1L);
        old.setToolsSnapshotJson("[{\"sourceKind\":\"WORKFLOW\",\"sourceRef\":\"w1\",\"workflowVersionId\":9}]");
        when(publications.selectList(any())).thenReturn(List.of(publication)); frozen(List.of(old, current));
        assertTrue(reader.inspect().complete());
        publication.setName("更新发布名");
        var second = reader.inspect();
        assertEquals(2, second.bindings().size());
        assertTrue(second.bindings().stream().anyMatch(value -> Long.valueOf(9).equals(value.workflowVersionId())));
        assertTrue(second.bindings().stream().allMatch(value -> "更新发布名".equals(value.publicationName())));
        verify(revisions, times(1)).referenceBodies(any());
        verify(revisions, times(2)).referenceMetadata(any(), anyInt());
        current.setToolsSnapshotJson("null");
        assertFalse(reader.inspect().complete(), "A repaired row must not reuse the old successful parse");
        verify(revisions, times(2)).referenceBodies(any());
        when(publications.selectList(any())).thenReturn(List.of());
        assertEquals(List.of(), reader.inspect().bindings());
    }

    @Test void missingBodyAndMetadataBodyRaceArePartialAndNeverCacheSuccess() {
        var row = revision(12L, 1L);
        when(publications.selectList(any())).thenReturn(List.of(publication(1L, 12L)));
        when(revisions.referenceMetadata(any(), anyInt())).thenReturn(List.of(metadata(row)));
        when(revisions.referenceBodies(any())).thenReturn(List.of());
        assertFalse(reader.inspect().complete());
        row.setToolsSnapshotJson("[]");
        when(revisions.referenceBodies(any())).thenReturn(List.of(row));
        assertFalse(reader.inspect().complete());
        verify(revisions, times(2)).referenceBodies(any());
    }

    @Test void revisionCacheIsBoundedAndEvictsOldBodies() {
        var rows = java.util.stream.LongStream.rangeClosed(1, 513).mapToObj(id -> revision(id, 1L)).toList();
        rows.forEach(row -> row.setToolsSnapshotJson("[{\"sourceKind\":\"CAPABILITY\",\"sourceRef\":\"other:read-" + row.getId() + "\"}]"));
        when(publications.selectList(any())).thenReturn(List.of(publication(1L, 513L))); frozen(rows);
        assertTrue(reader.inspect().complete());
        when(publications.selectList(any())).thenReturn(List.of(publication(1L, 1L))); frozen(List.of(rows.get(0)));
        assertTrue(reader.inspect().complete());
        verify(revisions, times(6)).referenceBodies(any());
        verify(revisions, atLeastOnce()).referenceBodies(argThat(ids -> ids.size() == 128));
        verify(revisions, never()).referenceBodies(argThat(ids -> ids.size() > 128));
    }

    @Test void identicalBodyFactsAreSharedAcrossPublicationRevisionIdentitiesWithoutCacheChurn() {
        var rows = java.util.stream.LongStream.rangeClosed(1, 600).mapToObj(id -> revision(id, id <= 300 ? 1L : 2L)).toList();
        when(publications.selectList(any())).thenReturn(List.of(publication(1L, 300L), publication(2L, 600L)));
        frozen(rows);
        var cold = reader.inspect(java.util.Set.of("other:read"), java.util.Map.of());
        var warm = reader.inspect(java.util.Set.of("other:read"), java.util.Map.of());
        assertTrue(cold.complete()); assertTrue(warm.complete()); assertEquals(600, cold.bindings().size());
        assertEquals(cold.bindings(), warm.bindings());
        assertEquals(300, cold.bindings().stream().filter(binding -> binding.publicationId() == 1L).count());
        assertEquals(300, cold.bindings().stream().filter(binding -> binding.publicationId() == 2L).count());
        assertEquals(600, cold.bindings().get(599).revisionNo());
        verify(revisions, times(1)).referenceBodies(argThat(ids -> ids.size() == 1));
        verify(revisions, times(2)).referenceMetadata(any(), anyInt());
    }

    @Test void scopeSelectsExactWorkflowOwnerAndHistoricalPinsButUnrelatedMalformedEvidenceRemainsPartial() {
        var historical = revision(11L, 1L); var current = revision(12L, 1L); var malformed = revision(21L, 2L);
        historical.setToolsSnapshotJson("[{\"sourceKind\":\"WORKFLOW\",\"sourceRef\":\"w1\",\"workflowVersionId\":9}]");
        current.setToolsSnapshotJson("[{\"sourceKind\":\"WORKFLOW\",\"sourceRef\":\"other\",\"workflowVersionId\":9},{\"sourceKind\":\"CAPABILITY\",\"sourceRef\":\"other:read\"}]");
        malformed.setToolsSnapshotJson("null");
        when(publications.selectList(any())).thenReturn(List.of(publication(1L, 12L), publication(2L, 21L)));
        frozen(List.of(historical, current, malformed));
        var evidence = reader.inspect(java.util.Set.of("orders:read"), java.util.Map.of(9L, "w1"));
        assertFalse(evidence.complete()); assertEquals(1, evidence.bindings().size());
        assertEquals("w1", evidence.bindings().get(0).sourceRef()); assertEquals(11, evidence.bindings().get(0).revisionNo());
        assertFalse(reader.inspect(java.util.Set.of(), java.util.Map.of()).complete());
        assertEquals(List.of(), reader.inspect(java.util.Set.of(), java.util.Map.of()).bindings());
    }

    @Test void nullDigestAndMismatchCannotBorrowCachedSuccessfulContent() {
        var good = revision(12L, 1L);
        when(publications.selectList(any())).thenReturn(List.of(publication(1L, 12L))); frozen(List.of(good));
        assertTrue(reader.inspect().complete());
        var metadata = metadata(good); metadata.setSnapshotFingerprint(null);
        when(revisions.referenceMetadata(any(), anyInt())).thenReturn(List.of(metadata));
        assertFalse(reader.inspect().complete());
        metadata.setSnapshotFingerprint("0".repeat(64));
        assertFalse(reader.inspect().complete());
        verify(revisions, times(3)).referenceBodies(any());
    }

    @Test void malformedReferenceTypesArePartialWhileOtherValidBindingsRemainVisible() {
        var row = revision(12L, 1L);
        row.setToolsSnapshotJson("[{\"sourceKind\":\"CAPABILITY\",\"sourceRef\":123},null,{\"sourceKind\":\"CAPABILITY\",\"sourceRef\":\"orders:read\"}]");
        when(publications.selectList(any())).thenReturn(List.of(publication(1L, 12L))); frozen(List.of(row));
        var evidence = reader.inspect(java.util.Set.of("orders:read", "123"), java.util.Map.of());
        assertFalse(evidence.complete()); assertEquals(1, evidence.bindings().size());
        assertEquals("orders:read", evidence.bindings().get(0).sourceRef());
    }

    private void frozen(List<McpPublicationRevisionEntity> rows) {
        when(revisions.referenceMetadata(any(), anyInt())).thenAnswer(call -> rows.stream().map(this::metadata).toList());
        doAnswer(call -> {
            java.util.Collection<Long> ids = call.getArgument(0);
            return rows.stream().filter(row -> ids.contains(row.getId())).toList();
        }).when(revisions).referenceBodies(any());
    }

    private McpPublicationRevisionMapper.ReferenceMetadata metadata(McpPublicationRevisionEntity value) {
        var metadata = new McpPublicationRevisionMapper.ReferenceMetadata();
        metadata.setId(value.getId()); metadata.setPublicationId(value.getPublicationId()); metadata.setRevisionNo(value.getRevisionNo());
        metadata.setPublishedAt(value.getPublishedAt()); metadata.setSnapshotFingerprint(McpPublishedReferenceReader.fingerprint(value.getToolsSnapshotJson()));
        return metadata;
    }

    private McpPublicationEntity publication(Long id, Long current) {
        var value = new McpPublicationEntity(); value.setId(id); value.setCurrentRevisionId(current); value.setName("跨项目发布"); return value;
    }

    private McpPublicationRevisionEntity revision(Long id, Long publication) {
        var value = new McpPublicationRevisionEntity(); value.setId(id); value.setPublicationId(publication); value.setRevisionNo(id.intValue());
        value.setToolsSnapshotJson("[{\"sourceKind\":\"CAPABILITY\",\"sourceRef\":\"other:read\"}]"); return value;
    }
}

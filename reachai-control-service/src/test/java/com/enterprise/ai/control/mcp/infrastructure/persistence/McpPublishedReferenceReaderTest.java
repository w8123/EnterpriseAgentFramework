package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class McpPublishedReferenceReaderTest {
    final McpPublicationMapper publications = mock(McpPublicationMapper.class);
    final McpPublicationRevisionMapper revisions = mock(McpPublicationRevisionMapper.class);
    final McpPublishedReferenceReader reader = new McpPublishedReferenceReader(publications, revisions, new ObjectMapper());

    @Test void readsAllReachableRevisionsInOneBatchAcrossProjects() {
        when(publications.selectList(any())).thenReturn(List.of(publication(1L, 12L), publication(2L, 21L)));
        when(revisions.selectList(any())).thenReturn(List.of(revision(11L, 1L), revision(12L, 1L), revision(21L, 2L)));
        var evidence = reader.inspect();
        assertTrue(evidence.complete());
        assertEquals(3, evidence.bindings().size());
        assertEquals("other:read", evidence.bindings().get(0).sourceRef());
        verify(revisions, times(1)).selectList(any());
        assertThrows(UnsupportedOperationException.class, () -> evidence.bindings().clear());
    }

    @Test void missingCurrentRevisionAndMalformedSnapshotsArePartial() {
        when(publications.selectList(any())).thenReturn(List.of(publication(1L, 12L)));
        when(revisions.selectList(any())).thenReturn(List.of(revision(11L, 1L)));
        assertFalse(reader.inspect().complete());
        var malformed = revision(12L, 1L); malformed.setToolsSnapshotJson("null");
        when(revisions.selectList(any())).thenReturn(List.of(malformed));
        assertFalse(reader.inspect().complete());
    }

    private McpPublicationEntity publication(Long id, Long current) {
        var value = new McpPublicationEntity(); value.setId(id); value.setCurrentRevisionId(current); value.setName("跨项目发布"); return value;
    }

    private McpPublicationRevisionEntity revision(Long id, Long publication) {
        var value = new McpPublicationRevisionEntity(); value.setId(id); value.setPublicationId(publication); value.setRevisionNo(id.intValue());
        value.setToolsSnapshotJson("[{\"sourceKind\":\"CAPABILITY\",\"sourceRef\":\"other:read\"}]"); return value;
    }
}

package com.enterprise.ai.capability.catalog.graph;

import com.enterprise.ai.agent.capability.catalog.graph.*;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.capability.catalog.semantic.CapabilitySemanticDocumentStore;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.capability.catalog.enrichment.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CapabilityApiGraphRegenerateServiceTest {
    final ApiGraphNodeMapper nodes = mock(ApiGraphNodeMapper.class);
    final ApiGraphEdgeMapper edges = mock(ApiGraphEdgeMapper.class);
    final ApiGraphLayoutMapper layouts = mock(ApiGraphLayoutMapper.class);
    final CatalogSubjectResolver subjects = mock(CatalogSubjectResolver.class);
    final CapabilityCatalogGroupingService grouping = mock(CapabilityCatalogGroupingService.class);
    final CapabilityApiGraphOperationsService operations = mock(CapabilityApiGraphOperationsService.class);
    final CapabilityApiGraphSnapshotService snapshots = mock(CapabilityApiGraphSnapshotService.class);
    final CapabilityApiGraphRegenerateService service = new CapabilityApiGraphRegenerateService(nodes, edges, layouts, subjects, grouping,
            mock(CapabilitySemanticDocumentStore.class), mock(ScanProjectMapper.class), operations, snapshots, new ObjectMapper());

    @Test void buildsTypedMethodApiAndCandidateWithDeclaredFieldsDtoAndAutomaticInference() {
        when(subjects.operations(7L)).thenReturn(List.of(subject("BUSINESS_METHOD", 1L), subject("HTTP_API", 2L), subject("SOURCE_OBSERVATION", 3L)));
        AtomicLong ids = new AtomicLong(); doAnswer(call -> { ((ApiGraphNodeEntity)call.getArgument(0)).setId(ids.incrementAndGet()); return 1; }).when(nodes).insert(any(ApiGraphNodeEntity.class));
        service.regenerate(7L);
        ArgumentCaptor<ApiGraphNodeEntity> saved = ArgumentCaptor.forClass(ApiGraphNodeEntity.class); verify(nodes, atLeastOnce()).insert(saved.capture());
        assertEquals(3, saved.getAllValues().stream().filter(node -> "API".equals(node.getKind())).count());
        assertTrue(saved.getAllValues().stream().anyMatch(node -> "FIELD_IN".equals(node.getKind())));
        assertTrue(saved.getAllValues().stream().anyMatch(node -> "FIELD_OUT".equals(node.getKind())));
        assertTrue(saved.getAllValues().stream().anyMatch(node -> "DTO".equals(node.getKind())));
        assertTrue(saved.getAllValues().stream().allMatch(node -> node.getNodeKey() != null && node.getNodeKey().length() == 64));
        verify(operations).inferModelRefEdges(7L);
    }

    @Test void renamingAndMovingGroupKeepOperationIdentityAndManualEdgesAndLayouts() {
        CatalogSubject subject = subject("BUSINESS_METHOD", 1L); when(subjects.operations(7L)).thenReturn(List.of(subject));
        ApiGraphNodeEntity existing = new ApiGraphNodeEntity(); existing.setId(31L); existing.setProjectId(7L); existing.setKind("API");
        existing.setNodeKey(CatalogSubjectResolver.digest("operation:BUSINESS_METHOD:" + subject.subjectKey())); existing.setLabel("old name"); existing.setActive(true);
        when(nodes.selectList(any())).thenReturn(List.of(existing));
        AtomicLong ids = new AtomicLong(100); doAnswer(call -> { ((ApiGraphNodeEntity)call.getArgument(0)).setId(ids.incrementAndGet()); return 1; }).when(nodes).insert(any(ApiGraphNodeEntity.class));
        service.rebuild(7L);
        assertEquals(31L, existing.getId()); assertEquals(subject.title(), existing.getLabel()); verify(nodes).updateById(existing);
        verify(edges, never()).delete(any()); verify(layouts, never()).delete(any());
    }

    @Test void missingFieldsDisableRelationsAndRetainEvidenceAndLayout() {
        ApiGraphNodeEntity removed = new ApiGraphNodeEntity(); removed.setId(99L); removed.setProjectId(7L); removed.setNodeKey("removed"); removed.setActive(true);
        ApiGraphEdgeEntity manual = new ApiGraphEdgeEntity(); manual.setId(5L); manual.setProjectId(7L); manual.setSourceNodeId(99L); manual.setTargetNodeId(100L); manual.setSource("manual"); manual.setEnabled(true);
        when(nodes.selectList(any())).thenReturn(List.of(removed)); when(edges.selectList(any())).thenReturn(List.of(manual));
        service.rebuild(7L);
        assertFalse(removed.getActive()); assertFalse(manual.getEnabled()); assertNotNull(manual.getRejectReason());
        verify(nodes, never()).delete(any()); verify(edges, never()).delete(any()); verify(layouts, never()).delete(any());
    }

    private CatalogSubject subject(String type, Long id) {
        return new CatalogSubject(type, id, 7L, "orders:" + type + ":" + id, "Orders", "", "hash", !"SOURCE_OBSERVATION".equals(type), "GET", "/orders", List.of(
                new ToolDefinitionParameter("buyer", "BuyerDto", "", true, "BODY", List.of(new ToolDefinitionParameter("id", "Long", "", true, "BODY"))),
                new ToolDefinitionParameter("response", "OrderDto", "", false, "RESPONSE")), List.of());
    }
}

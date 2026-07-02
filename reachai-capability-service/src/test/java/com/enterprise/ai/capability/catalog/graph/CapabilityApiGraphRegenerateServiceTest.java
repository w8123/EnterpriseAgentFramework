package com.enterprise.ai.capability.catalog.graph;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphEdgeEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphEdgeMapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphLayoutMapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphNodeEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphNodeMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityApiGraphRegenerateServiceTest {

    @Test
    void regeneratesGraphFromScanModulesAndTools() throws Exception {
        ApiGraphNodeMapper nodeMapper = mock(ApiGraphNodeMapper.class);
        ApiGraphEdgeMapper edgeMapper = mock(ApiGraphEdgeMapper.class);
        ApiGraphLayoutMapper layoutMapper = mock(ApiGraphLayoutMapper.class);
        ScanModuleMapper moduleMapper = mock(ScanModuleMapper.class);
        ScanProjectToolMapper toolMapper = mock(ScanProjectToolMapper.class);
        SemanticDocMapper semanticDocMapper = mock(SemanticDocMapper.class);
        CapabilityApiGraphOperationsService operations = mock(CapabilityApiGraphOperationsService.class);
        CapabilityApiGraphSnapshotService snapshots = mock(CapabilityApiGraphSnapshotService.class);
        when(snapshots.loadSnapshot(7L)).thenReturn(new CapabilityApiGraphSnapshotView(List.of(), List.of(), List.of()));
        AtomicLong ids = new AtomicLong(100);
        doAnswer(invocation -> {
            ApiGraphNodeEntity node = invocation.getArgument(0);
            node.setId(ids.incrementAndGet());
            return 1;
        }).when(nodeMapper).insert(any(ApiGraphNodeEntity.class));
        when(moduleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(module()));
        when(toolMapper.selectList(any(Wrapper.class))).thenReturn(List.of(tool()));
        ObjectMapper objectMapper = new ObjectMapper();
        CapabilityApiGraphRegenerateService service = new CapabilityApiGraphRegenerateService(
                nodeMapper, edgeMapper, layoutMapper, moduleMapper, toolMapper, semanticDocMapper,
                operations, snapshots, objectMapper);

        CapabilityApiGraphSnapshotView snapshot = service.regenerate(7L);

        verify(edgeMapper).delete(any(Wrapper.class));
        verify(layoutMapper).delete(any(Wrapper.class));
        verify(nodeMapper).delete(any(Wrapper.class));
        verify(operations).inferModelRefEdges(7L);
        ArgumentCaptor<ApiGraphNodeEntity> nodes = ArgumentCaptor.forClass(ApiGraphNodeEntity.class);
        verify(nodeMapper, org.mockito.Mockito.atLeast(4)).insert(nodes.capture());
        assertTrue(nodes.getAllValues().stream().anyMatch(node -> "MODULE".equals(node.getKind())));
        assertTrue(nodes.getAllValues().stream().anyMatch(node -> "API".equals(node.getKind())));
        assertTrue(nodes.getAllValues().stream().anyMatch(node -> "FIELD_IN".equals(node.getKind())));
        assertTrue(nodes.getAllValues().stream().anyMatch(node -> "DTO".equals(node.getKind())));
        ArgumentCaptor<ApiGraphEdgeEntity> edges = ArgumentCaptor.forClass(ApiGraphEdgeEntity.class);
        verify(edgeMapper, org.mockito.Mockito.atLeastOnce()).insert(edges.capture());
        assertTrue(edges.getAllValues().stream().anyMatch(edge -> "BELONGS_TO".equals(edge.getKind())));
        assertEquals(List.of(), snapshot.edges());
    }

    @Test
    void rebuildUpsertsGraphWithoutDeletingLayout() throws Exception {
        ApiGraphNodeMapper nodeMapper = mock(ApiGraphNodeMapper.class);
        ApiGraphEdgeMapper edgeMapper = mock(ApiGraphEdgeMapper.class);
        ApiGraphLayoutMapper layoutMapper = mock(ApiGraphLayoutMapper.class);
        ScanModuleMapper moduleMapper = mock(ScanModuleMapper.class);
        ScanProjectToolMapper toolMapper = mock(ScanProjectToolMapper.class);
        SemanticDocMapper semanticDocMapper = mock(SemanticDocMapper.class);
        CapabilityApiGraphOperationsService operations = mock(CapabilityApiGraphOperationsService.class);
        CapabilityApiGraphSnapshotService snapshots = mock(CapabilityApiGraphSnapshotService.class);
        when(snapshots.loadSnapshot(7L)).thenReturn(new CapabilityApiGraphSnapshotView(List.of(), List.of(), List.of()));
        ApiGraphNodeEntity existingModule = new ApiGraphNodeEntity();
        existingModule.setId(31L);
        existingModule.setProjectId(7L);
        existingModule.setKind("MODULE");
        existingModule.setRefId(9L);
        existingModule.setLabel("Order");
        existingModule.setPropsJson("{\"name\":\"old\"}");
        ApiGraphNodeEntity orphan = new ApiGraphNodeEntity();
        orphan.setId(99L);
        orphan.setProjectId(7L);
        orphan.setKind("API");
        orphan.setRefId(404L);
        orphan.setLabel("removed");
        when(nodeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existingModule, orphan));
        when(edgeMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(moduleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(module()));
        when(toolMapper.selectList(any(Wrapper.class))).thenReturn(List.of(tool()));
        AtomicLong ids = new AtomicLong(200);
        doAnswer(invocation -> {
            ApiGraphNodeEntity node = invocation.getArgument(0);
            node.setId(ids.incrementAndGet());
            return 1;
        }).when(nodeMapper).insert(any(ApiGraphNodeEntity.class));
        CapabilityApiGraphRegenerateService service = new CapabilityApiGraphRegenerateService(
                nodeMapper, edgeMapper, layoutMapper, moduleMapper, toolMapper, semanticDocMapper,
                operations, snapshots, new ObjectMapper());

        service.rebuild(7L);

        verify(layoutMapper, never()).delete(any(Wrapper.class));
        verify(nodeMapper).updateById(existingModule);
        verify(edgeMapper).delete(any(Wrapper.class));
        verify(nodeMapper).delete(any(Wrapper.class));
        verify(operations).inferModelRefEdges(7L);
    }

    private ScanModuleEntity module() {
        ScanModuleEntity entity = new ScanModuleEntity();
        entity.setId(9L);
        entity.setProjectId(7L);
        entity.setName("OrderController");
        entity.setDisplayName("Order");
        return entity;
    }

    private ScanProjectToolEntity tool() throws Exception {
        ScanProjectToolEntity entity = new ScanProjectToolEntity();
        entity.setId(11L);
        entity.setProjectId(7L);
        entity.setModuleId(9L);
        entity.setName("createOrder");
        entity.setHttpMethod("POST");
        entity.setEndpointPath("/orders");
        entity.setParametersJson(new ObjectMapper().writeValueAsString(List.of(
                new com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter(
                        "body", "CreateOrderRequest", "request", true, "BODY", List.of(
                        new com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter(
                                "buyerId", "Long", "buyer", true, "BODY"))))));
        entity.setResponseType("OrderDTO");
        entity.setGlobalToolDefinitionId(99L);
        return entity;
    }
}

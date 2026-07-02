package com.enterprise.ai.capability.catalog.graph;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphEdgeEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphEdgeMapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphLayoutEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphLayoutMapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphNodeEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphNodeMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityApiGraphOperationsServiceTest {

    @Test
    void listsCandidatesWithLegacyFilteringAndSorting() {
        ApiGraphEdgeMapper edgeMapper = mock(ApiGraphEdgeMapper.class);
        ApiGraphLayoutMapper layoutMapper = mock(ApiGraphLayoutMapper.class);
        when(edgeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                edge(1L, "REQUEST_REF", "CANDIDATE", 0.72),
                edge(2L, "RESPONSE_REF", "CANDIDATE", 0.95),
                edge(3L, "MODEL_REF", "CANDIDATE", 0.99),
                edge(4L, "REQUEST_REF", "CONFIRMED", 0.96),
                edge(5L, "REQUEST_REF", null, 0.85)));
        CapabilityApiGraphOperationsService service = service(edgeMapper, layoutMapper);

        List<CapabilityApiGraphSnapshotView.EdgeView> candidates = service.listCandidates(7L, "CANDIDATE", 0.8);

        assertEquals(List.of(2L), candidates.stream().map(CapabilityApiGraphSnapshotView.EdgeView::id).toList());
    }

    @Test
    void confirmsCandidateForProject() {
        ApiGraphEdgeMapper edgeMapper = mock(ApiGraphEdgeMapper.class);
        ApiGraphLayoutMapper layoutMapper = mock(ApiGraphLayoutMapper.class);
        ApiGraphEdgeEntity candidate = edge(2L, "REQUEST_REF", "CANDIDATE", 0.95);
        when(edgeMapper.selectById(2L)).thenReturn(candidate);
        CapabilityApiGraphOperationsService service = service(edgeMapper, layoutMapper);

        CapabilityApiGraphSnapshotView.EdgeView confirmed = service.confirmCandidate(7L, 2L,
                new CapabilityApiGraphRequests.CandidateConfirmRequest("alice"));

        assertEquals("CONFIRMED", confirmed.status());
        assertEquals("alice", confirmed.confirmedBy());
        assertEquals(true, confirmed.enabled());
        verify(edgeMapper).updateById(candidate);
    }

    @Test
    void rejectsCandidateOutsideProject() {
        ApiGraphEdgeMapper edgeMapper = mock(ApiGraphEdgeMapper.class);
        ApiGraphLayoutMapper layoutMapper = mock(ApiGraphLayoutMapper.class);
        ApiGraphEdgeEntity candidate = edge(2L, "REQUEST_REF", "CANDIDATE", 0.95);
        candidate.setProjectId(8L);
        when(edgeMapper.selectById(2L)).thenReturn(candidate);
        CapabilityApiGraphOperationsService service = service(edgeMapper, layoutMapper);

        assertThrows(IllegalArgumentException.class, () -> service.rejectCandidate(7L, 2L,
                new CapabilityApiGraphRequests.CandidateRejectRequest("no")));
    }

    @Test
    void upsertsManualEdgeByLegacyIdentity() {
        ApiGraphEdgeMapper edgeMapper = mock(ApiGraphEdgeMapper.class);
        ApiGraphLayoutMapper layoutMapper = mock(ApiGraphLayoutMapper.class);
        CapabilityApiGraphOperationsService service = service(edgeMapper, layoutMapper);

        service.upsertEdge(7L, new CapabilityApiGraphRequests.EdgeUpsertRequest(1L, 3L, "request_ref", "manual"));

        ArgumentCaptor<ApiGraphEdgeEntity> captor = ArgumentCaptor.forClass(ApiGraphEdgeEntity.class);
        verify(edgeMapper).insert(captor.capture());
        ApiGraphEdgeEntity saved = captor.getValue();
        assertEquals("REQUEST_REF", saved.getKind());
        assertEquals("manual", saved.getNote());
        assertEquals("manual", saved.getSource());
        assertEquals("CONFIRMED", saved.getStatus());
    }

    @Test
    void savesLayoutAsUpsert() {
        ApiGraphEdgeMapper edgeMapper = mock(ApiGraphEdgeMapper.class);
        ApiGraphLayoutMapper layoutMapper = mock(ApiGraphLayoutMapper.class);
        ApiGraphLayoutEntity existing = new ApiGraphLayoutEntity();
        existing.setId(9L);
        when(layoutMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        CapabilityApiGraphOperationsService service = service(edgeMapper, layoutMapper);

        service.saveLayout(7L, new CapabilityApiGraphRequests.LayoutSaveRequest(List.of(
                new CapabilityApiGraphRequests.LayoutPositionDTO(1L, null, 20.0, "{}"))));

        verify(layoutMapper).updateById(existing);
    }

    @Test
    void buildsParamHintsFromConfirmedRequestEdges() {
        ApiGraphEdgeMapper edgeMapper = mock(ApiGraphEdgeMapper.class);
        ApiGraphLayoutMapper layoutMapper = mock(ApiGraphLayoutMapper.class);
        ApiGraphNodeMapper nodeMapper = mock(ApiGraphNodeMapper.class);
        ToolDefinitionMapper toolDefinitionMapper = mock(ToolDefinitionMapper.class);
        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setId(99L);
        tool.setName("createOrder");
        when(toolDefinitionMapper.selectOne(any(Wrapper.class))).thenReturn(tool);
        when(nodeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                apiNode(10L, "createOrder", 99L),
                apiNode(11L, "queryUser", 42L),
                fieldNode(1L, 11L, "userId", "{\"paramPath\":\"result.userId\"}"),
                fieldNode(2L, 10L, "buyerId", "{\"paramPath\":\"request.buyerId\"}")));
        ApiGraphEdgeEntity hintEdge = edge(6L, "REQUEST_REF", "CONFIRMED", 0.8);
        hintEdge.setTargetNodeId(2L);
        when(edgeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(hintEdge));
        CapabilityApiGraphOperationsService service = new CapabilityApiGraphOperationsService(
                edgeMapper, layoutMapper, nodeMapper, toolDefinitionMapper, new ObjectMapper());

        List<CapabilityApiGraphParamSourceHintView> hints = service.listParamHints(7L, "createOrder");

        assertEquals(1, hints.size());
        assertEquals("request.buyerId", hints.get(0).targetPath());
        assertEquals("createOrder", hints.get(0).targetApi());
        assertEquals("result.userId", hints.get(0).sourcePath());
        assertEquals("queryUser", hints.get(0).sourceApi());
    }

    @Test
    void infersModelRefEdgesFromSharedCompositeTypes() {
        ApiGraphEdgeMapper edgeMapper = mock(ApiGraphEdgeMapper.class);
        ApiGraphLayoutMapper layoutMapper = mock(ApiGraphLayoutMapper.class);
        ApiGraphNodeMapper nodeMapper = mock(ApiGraphNodeMapper.class);
        when(nodeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                fieldNode(1L, 10L, "result", "{\"paramPath\":\"response.result\"}", "FIELD_OUT", "UserDTO"),
                fieldNode(2L, 11L, "user", "{\"paramPath\":\"request.user\"}", "FIELD_IN", "UserDTO")));
        CapabilityApiGraphOperationsService service = new CapabilityApiGraphOperationsService(
                edgeMapper, layoutMapper, nodeMapper, mock(ToolDefinitionMapper.class), new ObjectMapper());

        CapabilityApiGraphRequests.InferResultDTO result = service.inferModelRefEdges(7L);

        assertEquals(1, result.generated());
        verify(edgeMapper).delete(any(Wrapper.class));
        ArgumentCaptor<ApiGraphEdgeEntity> captor = ArgumentCaptor.forClass(ApiGraphEdgeEntity.class);
        verify(edgeMapper).insert(captor.capture());
        assertEquals("MODEL_REF", captor.getValue().getKind());
        assertEquals("dto_match", captor.getValue().getInferStrategy());
    }

    @Test
    void infersRequestResponseCandidateEdgesFromFieldSchema() {
        ApiGraphEdgeMapper edgeMapper = mock(ApiGraphEdgeMapper.class);
        ApiGraphLayoutMapper layoutMapper = mock(ApiGraphLayoutMapper.class);
        ApiGraphNodeMapper nodeMapper = mock(ApiGraphNodeMapper.class);
        when(nodeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                fieldNode(1L, 10L, "customerId", "{\"paramPath\":\"response.customerId\"}", "FIELD_OUT", "CustomerDTO"),
                fieldNode(2L, 11L, "customerId", "{\"paramPath\":\"request.customerId\"}", "FIELD_IN", "CustomerDTO")));
        CapabilityApiGraphOperationsService service = new CapabilityApiGraphOperationsService(
                edgeMapper, layoutMapper, nodeMapper, mock(ToolDefinitionMapper.class), new ObjectMapper());

        CapabilityApiGraphRequests.InferResultDTO result = service.inferRequestResponseEdges(7L);

        assertEquals(1, result.generated());
        ArgumentCaptor<ApiGraphEdgeEntity> captor = ArgumentCaptor.forClass(ApiGraphEdgeEntity.class);
        verify(edgeMapper).insert(captor.capture());
        assertEquals("REQUEST_REF", captor.getValue().getKind());
        assertEquals("CANDIDATE", captor.getValue().getStatus());
        assertEquals("schema_match", captor.getValue().getInferStrategy());
        verify(edgeMapper, atLeastOnce()).selectList(any(Wrapper.class));
    }

    private ApiGraphEdgeEntity edge(Long id, String kind, String status, Double confidence) {
        ApiGraphEdgeEntity entity = new ApiGraphEdgeEntity();
        entity.setId(id);
        entity.setProjectId(7L);
        entity.setSourceNodeId(1L);
        entity.setTargetNodeId(3L);
        entity.setKind(kind);
        entity.setSource("auto");
        entity.setConfidence(confidence);
        entity.setStatus(status);
        entity.setEnabled(true);
        return entity;
    }

    private CapabilityApiGraphOperationsService service(ApiGraphEdgeMapper edgeMapper, ApiGraphLayoutMapper layoutMapper) {
        return new CapabilityApiGraphOperationsService(
                edgeMapper,
                layoutMapper,
                mock(ApiGraphNodeMapper.class),
                mock(ToolDefinitionMapper.class),
                new ObjectMapper());
    }

    private ApiGraphNodeEntity apiNode(Long id, String label, Long toolDefinitionId) {
        ApiGraphNodeEntity entity = new ApiGraphNodeEntity();
        entity.setId(id);
        entity.setProjectId(7L);
        entity.setKind("API");
        entity.setLabel(label);
        entity.setPropsJson("{\"globalToolDefinitionId\":" + toolDefinitionId + "}");
        return entity;
    }

    private ApiGraphNodeEntity fieldNode(Long id, Long apiNodeId, String label, String propsJson) {
        return fieldNode(id, apiNodeId, label, propsJson, "FIELD_IN", null);
    }

    private ApiGraphNodeEntity fieldNode(
            Long id,
            Long apiNodeId,
            String label,
            String propsJson,
            String kind,
            String typeName) {
        ApiGraphNodeEntity entity = new ApiGraphNodeEntity();
        entity.setId(id);
        entity.setProjectId(7L);
        entity.setKind(kind);
        entity.setRefId(apiNodeId);
        entity.setLabel(label);
        entity.setTypeName(typeName);
        entity.setPropsJson(propsJson);
        return entity;
    }
}

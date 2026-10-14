package com.enterprise.ai.capability.catalog.graph;

import com.enterprise.ai.agent.capability.catalog.graph.*;
import com.enterprise.ai.capability.catalog.enrichment.CatalogSubject;
import com.enterprise.ai.capability.catalog.enrichment.CatalogSubjectResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CapabilityApiGraphOperationsServiceTest {
    final ApiGraphEdgeMapper edges = mock(ApiGraphEdgeMapper.class);
    final ApiGraphLayoutMapper layouts = mock(ApiGraphLayoutMapper.class);
    final ApiGraphNodeMapper nodes = mock(ApiGraphNodeMapper.class);
    final CatalogSubjectResolver subjects = mock(CatalogSubjectResolver.class);
    final com.enterprise.ai.capability.catalog.enrichment.CatalogOwnerFingerprintReader fingerprints = mock(com.enterprise.ai.capability.catalog.enrichment.CatalogOwnerFingerprintReader.class);
    final CapabilityApiGraphOperationsService service = new CapabilityApiGraphOperationsService(edges, layouts, nodes, subjects, new ObjectMapper(), fingerprints);

    @Test void filtersAndSortsCandidateRelationsOnly() {
        when(edges.selectList(any())).thenReturn(List.of(edge(1L, "REQUEST_REF", "CANDIDATE", .72), edge(2L, "RESPONSE_REF", "CANDIDATE", .95), edge(3L, "MODEL_REF", "CANDIDATE", .99)));
        assertEquals(List.of(2L), service.listCandidates(7L, "CANDIDATE", .8).stream().map(CapabilityApiGraphSnapshotView.EdgeView::id).toList());
    }
    @Test void confirmsAndRejectsWithProjectIsolation() {
        ApiGraphEdgeEntity candidate = edge(2L, "REQUEST_REF", "CANDIDATE", .95); when(edges.selectById(2L)).thenReturn(candidate);
        when(nodes.selectById(1L)).thenReturn(field(1L, 11L, "FIELD_OUT", "Long", "hash")); when(nodes.selectById(3L)).thenReturn(field(3L, 10L, "FIELD_IN", "Long", "hash"));
        assertEquals("CONFIRMED", service.confirmCandidate(7L, 2L, new CapabilityApiGraphRequests.CandidateConfirmRequest("alice")).status());
        assertEquals("REJECTED", service.rejectCandidate(7L, 2L, new CapabilityApiGraphRequests.CandidateRejectRequest("wrong")).status());
        assertThrows(IllegalArgumentException.class, () -> service.rejectCandidate(8L, 2L, null));
    }
    @Test void manualEdgeAndLayoutWritesRequireCurrentProjectNodes() {
        when(nodes.selectById(1L)).thenReturn(field(1L, 11L, "FIELD_OUT", "Long", "hash")); when(nodes.selectById(3L)).thenReturn(field(3L, 10L, "FIELD_IN", "Long", "hash"));
        service.upsertEdge(7L, new CapabilityApiGraphRequests.EdgeUpsertRequest(1L, 3L, "REQUEST_REF", "manual"));
        ArgumentCaptor<ApiGraphEdgeEntity> saved = ArgumentCaptor.forClass(ApiGraphEdgeEntity.class); verify(edges).insert(saved.capture());
        assertEquals("manual", saved.getValue().getSource()); assertTrue(saved.getValue().getEvidenceJson().contains("targetContractHash"));
        assertThrows(IllegalArgumentException.class, () -> service.upsertEdge(8L, new CapabilityApiGraphRequests.EdgeUpsertRequest(1L, 3L, "REQUEST_REF", null)));
        assertThrows(IllegalArgumentException.class, () -> service.saveLayout(8L, new CapabilityApiGraphRequests.LayoutSaveRequest(List.of(new CapabilityApiGraphRequests.LayoutPositionDTO(1L, 0d, 0d, null)))));
        service.saveLayout(7L, new CapabilityApiGraphRequests.LayoutSaveRequest(List.of(new CapabilityApiGraphRequests.LayoutPositionDTO(1L, 0d, 2d, null)))); verify(layouts).insert(any(ApiGraphLayoutEntity.class));
    }
    @Test void deletingAnotherProjectsEdgeIsRejected() {
        when(edges.selectById(2L)).thenReturn(edge(2L, "REQUEST_REF", "CONFIRMED", 1d));
        assertThrows(IllegalArgumentException.class, () -> service.deleteEdge(8L, 2L)); verify(edges, never()).deleteById(any(Long.class));
        assertTrue(service.deleteEdge(7L, 2L) == false); verify(edges).deleteById(2L);
    }
    @Test void modelReferencesUseFullTypeIdentityAndKeepSameNameDifferentPackagesSeparate() {
        when(nodes.selectList(any())).thenReturn(List.of(field(1L, 10L, "FIELD_OUT", "a.UserDto", "hash"), field(3L, 11L, "FIELD_IN", "b.UserDto", "hash")));
        assertEquals(0, service.inferModelRefEdges(7L).generated());
        when(nodes.selectList(any())).thenReturn(List.of(field(1L, 10L, "FIELD_OUT", "a.UserDto", "hash"), field(3L, 11L, "FIELD_IN", "a.UserDto", "hash")));
        assertEquals(1, service.inferModelRefEdges(7L).generated());
    }
    @Test void repeatedInferenceDoesNotDowngradeConfirmedOrResurrectRejectedRelations() {
        when(nodes.selectList(any())).thenReturn(List.of(field(1L, 10L, "FIELD_OUT", "UserDto", "hash"), field(3L, 11L, "FIELD_IN", "UserDto", "hash")));
        ApiGraphEdgeEntity confirmed = edge(2L, "REQUEST_REF", "CONFIRMED", .9); when(edges.selectOne(any())).thenReturn(confirmed);
        assertEquals(1, service.inferRequestResponseEdges(7L).generated()); assertEquals("CONFIRMED", confirmed.getStatus()); verify(edges, never()).updateById(any());
        ApiGraphEdgeEntity rejected = edge(2L, "REQUEST_REF", "REJECTED", .9); when(edges.selectList(any())).thenReturn(List.of(rejected));
        assertEquals(0, service.inferRequestResponseEdges(7L).generated());
    }
    @Test void typedHintsRequireConfirmedEdgesAndCurrentAcceptedOwnerContract() {
        CatalogSubject subject = subject("hash"); when(subjects.resolve(7L, "BUSINESS_METHOD", 21L)).thenReturn(subject);
        when(fingerprints.read(eq(7L), any())).thenReturn(java.util.Map.of("BUSINESS_METHOD:21", new com.enterprise.ai.capability.catalog.enrichment.CatalogOwnerFingerprintReader.Fingerprint("hash", true)));
        ApiGraphNodeEntity targetApi = api(10L, "createOrder"), sourceApi = api(11L, "queryUser");
        ApiGraphNodeEntity source = field(1L, 11L, "FIELD_OUT", "Long", "hash"), target = field(3L, 10L, "FIELD_IN", "Long", "hash");
        when(nodes.selectList(any())).thenReturn(List.of(targetApi, sourceApi, source, target));
        ApiGraphEdgeEntity edge = edge(6L, "REQUEST_REF", "CONFIRMED", .8); edge.setEvidenceJson("{\"sourceContractHash\":\"hash\",\"targetContractHash\":\"hash\"}"); when(edges.selectList(any())).thenReturn(List.of(edge));
        assertEquals(1, service.listParamHints(7L, "BUSINESS_METHOD", 21L).size());
        verify(subjects, never()).operations(any());
        when(subjects.resolve(7L, "BUSINESS_METHOD", 21L)).thenReturn(subject("changed"));
        when(fingerprints.read(eq(7L), any())).thenReturn(java.util.Map.of("BUSINESS_METHOD:21", new com.enterprise.ai.capability.catalog.enrichment.CatalogOwnerFingerprintReader.Fingerprint("changed", true)));
        assertTrue(service.listParamHints(7L, "BUSINESS_METHOD", 21L).isEmpty());
    }
    @Test void hintStateDistinguishesMissingGraphAndContractChangeFromCurrentEmptyHints() {
        var subject = subject("hash");
        assertEquals("NOT_BUILT", service.paramHintState(subject));
        var operation = api(10L, "createOrder"); operation.setPropsJson("{\"subjectType\":\"BUSINESS_METHOD\",\"subjectId\":21,\"contractHash\":\"hash\"}");
        when(nodes.selectList(any())).thenReturn(List.of(operation)); assertEquals("CURRENT", service.paramHintState(subject));
        assertEquals("STALE", service.paramHintState(subject("changed")));
    }
    @Test void disabledCurrentSubjectRelationIsStaleAndRelatedOwnerChecksNeverHydrateTheProject() {
        var subject = subject("hash");
        var targetApi = api(10L, "createOrder"); targetApi.setPropsJson("{\"subjectType\":\"BUSINESS_METHOD\",\"subjectId\":21,\"contractHash\":\"hash\"}");
        var sourceApi = api(11L, "queryUser"); sourceApi.setPropsJson("{\"subjectType\":\"HTTP_API\",\"subjectId\":92,\"contractHash\":\"source-hash\"}");
        var source = field(1L, 11L, "FIELD_OUT", "Long", "source-hash");
        source.setPropsJson("{\"paramPath\":\"customerId\",\"subjectType\":\"HTTP_API\",\"subjectId\":92,\"accepted\":true,\"contractHash\":\"source-hash\"}");
        var target = field(3L, 10L, "FIELD_IN", "Long", "hash");
        when(nodes.selectList(any())).thenReturn(List.of(targetApi, sourceApi, source, target));
        var edge = edge(6L, "REQUEST_REF", "CONFIRMED", .8); edge.setEnabled(false);
        edge.setEvidenceJson("{\"sourceContractHash\":\"source-hash\",\"targetContractHash\":\"hash\"}");
        when(edges.selectList(any())).thenReturn(List.of(edge));
        when(fingerprints.read(eq(7L), any())).thenReturn(java.util.Map.of(
                "BUSINESS_METHOD:21", new com.enterprise.ai.capability.catalog.enrichment.CatalogOwnerFingerprintReader.Fingerprint("hash", true),
                "HTTP_API:92", new com.enterprise.ai.capability.catalog.enrichment.CatalogOwnerFingerprintReader.Fingerprint("source-hash", true)));
        var result = service.paramHints(subject);
        assertEquals("STALE", result.state()); assertTrue(result.hints().isEmpty());
        verify(fingerprints).read(eq(7L), argThat(ids -> ids.size() == 2 && ids.contains(
                new com.enterprise.ai.capability.catalog.enrichment.CatalogOwnerFingerprintReader.Identity("HTTP_API", 92L))));
        verifyNoInteractions(subjects);
    }
    @Test void anonymousHttpObjectSchemasInferModelReferencesOnlyForExactStructuralHash() {
        var source = field(1L, 10L, "FIELD_OUT", "object", "owner-a"); var target = field(3L, 11L, "FIELD_IN", "object", "owner-b");
        source.setPropsJson("{\"schemaHash\":\"same-shape\",\"composite\":true}"); target.setPropsJson("{\"schemaHash\":\"different-shape\",\"composite\":true}");
        when(nodes.selectList(any())).thenReturn(List.of(source, target)); assertEquals(0, service.inferModelRefEdges(7L).generated());
        target.setPropsJson("{\"schemaHash\":\"same-shape\",\"composite\":true}"); assertEquals(1, service.inferModelRefEdges(7L).generated());
    }
    private CatalogSubject subject(String hash) { return new CatalogSubject("BUSINESS_METHOD", 21L, 7L, "orders:query", "Orders", "", hash, true, "GET", "/orders", List.of(), List.of()); }
    private ApiGraphNodeEntity field(Long id, Long operationId, String kind, String type, String hash) {
        ApiGraphNodeEntity node = new ApiGraphNodeEntity(); node.setId(id); node.setProjectId(7L); node.setActive(true); node.setKind(kind); node.setRefId(operationId); node.setLabel("customerId"); node.setTypeName(type);
        node.setPropsJson("{\"paramPath\":\"customerId\",\"subjectType\":\"BUSINESS_METHOD\",\"subjectId\":21,\"accepted\":true,\"contractHash\":\"" + hash + "\"}"); return node;
    }
    private ApiGraphNodeEntity api(Long id, String label) { ApiGraphNodeEntity node = new ApiGraphNodeEntity(); node.setId(id); node.setProjectId(7L); node.setActive(true); node.setKind("API"); node.setLabel(label); node.setPropsJson("{\"subjectType\":\"BUSINESS_METHOD\",\"subjectId\":21}"); return node; }
    private ApiGraphEdgeEntity edge(Long id, String kind, String status, Double score) {
        ApiGraphEdgeEntity edge = new ApiGraphEdgeEntity(); edge.setId(id); edge.setProjectId(7L); edge.setSourceNodeId(1L); edge.setTargetNodeId(3L); edge.setKind(kind); edge.setSource("auto"); edge.setStatus(status); edge.setConfidence(score); edge.setEnabled(true); return edge;
    }
}

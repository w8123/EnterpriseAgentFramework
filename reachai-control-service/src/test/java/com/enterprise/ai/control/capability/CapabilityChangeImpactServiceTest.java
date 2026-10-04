package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.enterprise.ai.control.mcp.infrastructure.persistence.*;
import com.enterprise.ai.control.a2a.infrastructure.persistence.*;
import com.enterprise.ai.control.a2a.application.port.A2aPublishedReferenceQuery;
import com.enterprise.ai.control.mcp.application.port.McpPublishedReferenceQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CapabilityChangeImpactServiceTest {
    final RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
    final InternalServiceAuthSigner signer = mock(InternalServiceAuthSigner.class);
    final ObjectMapper json = new ObjectMapper();
    final McpPublicationMapper mcp = mock(McpPublicationMapper.class);
    final McpPublicationRevisionMapper revisions = mock(McpPublicationRevisionMapper.class);
    final A2aPublicationMapper a2a = mock(A2aPublicationMapper.class);
    final A2aPublicationRevisionMapper a2aRevisions = mock(A2aPublicationRevisionMapper.class);
    final CapabilityChangeImpactService service = new CapabilityChangeImpactService(runtime, signer, json,
            new McpPublishedReferenceReader(mcp, revisions, json), new A2aPublishedReferenceReader(a2a, a2aRevisions));

    @Test void combinesRealRuntimeAndFrozenMcpEvidenceEvenWhenOriginalImpactIsNull() throws Exception {
        when(runtime.capabilityReferences(any(), any())).thenReturn(Map.of("state", "COMPLETE", "usages", List.of(
                Map.of("qualifiedName", "orders:read", "kind", "WORKFLOW", "id", "w1", "name", "查询", "versionId", 9L))));
        var publication = new McpPublicationEntity(); publication.setId(4L); publication.setName("开放查询"); publication.setCurrentRevisionId(7L);
        var revision = new McpPublicationRevisionEntity(); revision.setId(7L); revision.setPublicationId(4L); revision.setRevisionNo(1);
        revision.setToolsSnapshotJson("[{\"sourceKind\":\"WORKFLOW\",\"sourceRef\":\"w1\",\"workflowVersionId\":9}]");
        when(mcp.selectList(any())).thenReturn(List.of(publication)); when(revisions.selectList(any())).thenReturn(List.of(revision));
        var impact = impact(); assertEquals("COMPLETE", impact.path("runtimeEvidence").asText()); assertEquals("COMPLETE", impact.path("publicationEvidence").asText());
        assertEquals(2, impact.path("references").size());
        var mcpUsage = impact.path("references").get(1);
        assertEquals("w1", mcpUsage.path("workflowId").asText(), "MCP_REFERENCE_WORKFLOW_LOCATION_MISSING");
        assertEquals(9L, mcpUsage.path("versionId").asLong(), "MCP_REFERENCE_PINNED_VERSION_MISSING");
        verify(signer).sign(eq("POST"), eq("/internal/runtime/capability-references"), eq("PLATFORM_SESSION"), eq("actor"), any(byte[].class));
    }

    @Test void failedOrMalformedEvidenceCannotClaimZeroReferences() throws Exception {
        when(runtime.capabilityReferences(any(), any())).thenThrow(new IllegalStateException("offline"));
        assertEquals("UNKNOWN", impact().path("runtimeEvidence").asText());
        doReturn(Map.of("state", "COMPLETE", "usages", List.of("invalid"))).when(runtime).capabilityReferences(any(), any());
        assertEquals("UNKNOWN", impact().path("runtimeEvidence").asText());
        when(mcp.selectList(any())).thenThrow(new IllegalStateException("offline"));
        assertEquals("UNKNOWN", impact().path("publicationEvidence").asText());
    }

    private com.fasterxml.jackson.databind.JsonNode impact() throws Exception {
        ResponseEntity<Object> response = ResponseEntity.ok(Map.of("records", List.of(Map.of("qualifiedName", "orders:read", "storageName", "orders_read", "impactJson", "null"))));
        var result = service.enrich(response, "orders", "actor");
        var records = (List<?>) ((Map<?, ?>) result.getBody()).get("records");
        return json.readTree((String) ((Map<?, ?>) records.get(0)).get("impactJson"));
    }

    @Test void singleCapabilityUsesOwnerAliasesAndPreservesUnknownEvidence() throws Exception {
        when(runtime.capabilityReferences(any(), any())).thenReturn(Map.of("state", "PARTIAL", "usages", List.of(
                Map.of("qualifiedName", "orders:read", "kind", "WORKFLOW", "id", "draft-1", "stage", "DRAFT"))));
        var result = service.references("orders", "orders:read", "orders_read", "actor");
        assertEquals("PARTIAL", result.get("runtimeEvidence"));
        assertEquals(1, ((List<?>) result.get("references")).size());
        var payload = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(runtime).capabilityReferences(any(), payload.capture());
        var request = json.readTree(payload.getValue());
        assertEquals("orders", request.path("projectCode").asText());
        assertEquals("orders_read", request.path("capabilities").get(0).path("storageName").asText());
        when(runtime.capabilityReferences(any(), any())).thenThrow(new IllegalStateException("offline"));
        assertEquals("UNKNOWN", service.references("orders", "orders:read", "orders_read", "actor").get("runtimeEvidence"));
    }

    @Test void enrichmentKeepsExistingDecisionFields() throws Exception {
        when(runtime.capabilityReferences(any(), any())).thenReturn(Map.of("state", "COMPLETE", "usages", List.of()));
        var page = ResponseEntity.<Object>ok(Map.of("records", List.of(Map.of("qualifiedName", "orders:read", "storageName", "orders_read", "impactJson", "{\"reason\":\"contract changed\",\"currentCandidate\":true}"))));
        var result = service.enrich(page, "orders", "actor");
        var records = (List<?>) ((Map<?, ?>) result.getBody()).get("records");
        var impact = json.readTree((String) ((Map<?, ?>) records.get(0)).get("impactJson"));
        assertEquals("contract changed", impact.path("reason").asText());
        assertTrue(impact.path("currentCandidate").asBoolean());
    }
}

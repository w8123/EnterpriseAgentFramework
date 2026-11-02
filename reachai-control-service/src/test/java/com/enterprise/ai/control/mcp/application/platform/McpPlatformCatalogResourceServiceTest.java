package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCatalogResourcePort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCatalogResourcePort.Area;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformCatalogResourceServiceTest {
    private final McpPlatformCatalogResourcePort catalog = mock(McpPlatformCatalogResourcePort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformCatalogResourceService service = new McpPlatformCatalogResourceService(catalog, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(),
                    List.of("platform:read", "platform:write"), List.of()));

    @Test void regenerateFreezesCuratedRelationsAndRefusesWhenOneWasConfirmedMeanwhile() {
        stubGraph(edge(1, "CONFIRMED"), edge(2, "CANDIDATE"));
        service.request(actor, McpPlatformCatalogResourceService.GraphChange.REGENERATE, 7L, null, "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("API_GRAPH_CHANGE"), eq("API_GRAPH:7"), frozen.capture());
        assertEquals(1, ((List<?>) frozen.getValue().get("curatedEdges")).size());
        when(approvals.claim(actor, "approval", "API_GRAPH_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "API_GRAPH_CHANGE", "API_GRAPH:7", frozen.getValue()));

        stubGraph(edge(1, "CONFIRMED"), edge(2, "CONFIRMED"));
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(catalog, never()).call(any(), any(), eq("POST"), anyLong(), any(), any(), any());
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
    }

    @Test void lostEdgeDeleteIsUnknownAndRunsOnce() {
        stubGraph(edge(1, "CONFIRMED"));
        service.request(actor, McpPlatformCatalogResourceService.GraphChange.DELETE_EDGE, 7L, 1L, "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("API_GRAPH_CHANGE"), eq("API_GRAPH_EDGE:7:1"), frozen.capture());
        when(approvals.claim(actor, "approval", "API_GRAPH_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "API_GRAPH_CHANGE", "API_GRAPH_EDGE:7:1", frozen.getValue()));
        doThrow(new IllegalStateException("lost")).when(catalog).call(actor.session(), Area.API_GRAPH, "DELETE", 7L, "/edges/1", Map.of(), null);
        assertEquals(202, service.execute(actor, "approval").getStatusCode().value());
        verify(catalog, times(1)).call(actor.session(), Area.API_GRAPH, "DELETE", 7L, "/edges/1", Map.of(), null);
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
    }

    @Test void deleteOfAMissingRelationIsRefusedBeforeReview() {
        stubGraph(edge(1, "CONFIRMED"));
        assertEquals(404, service.request(actor, McpPlatformCatalogResourceService.GraphChange.DELETE_EDGE, 7L, 9L, "inv").getStatusCode().value());
        verifyNoInteractions(approvals);
    }

    @Test void lostDirectWriteIsUnknown() {
        doReturn(ResponseEntity.status(503).build()).when(catalog).call(actor.session(), Area.BUSINESS_CAPABILITIES, "POST", 7L, "/groups", Map.of(), Map.of("displayName", "订单"));
        assertEquals(202, service.write(actor, Area.BUSINESS_CAPABILITIES, "POST", 7L, "/groups", Map.of(), Map.of("displayName", "订单")).getStatusCode().value());
    }

    private void stubGraph(Map<?, ?>... edges) {
        doReturn(ResponseEntity.ok(Map.of("nodes", List.of(Map.of("id", 10), Map.of("id", 11)), "edges", List.of(edges), "layouts", List.of())))
                .when(catalog).call(actor.session(), Area.API_GRAPH, "GET", 7L, "/snapshot", Map.of(), null);
    }

    private static Map<String, Object> edge(long id, String status) {
        return Map.of("id", id, "sourceNodeId", 10, "targetNodeId", 11, "kind", "MODEL_REF", "source", "INFERRED", "status", status, "enabled", true);
    }
}

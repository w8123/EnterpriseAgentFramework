package com.enterprise.ai.control.mcp.application.protocol;

import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.mcp.application.port.McpCallAuditRepository;
import com.enterprise.ai.control.mcp.application.port.McpClientRepository;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.enterprise.ai.control.mcp.application.port.McpRuntimeExecutionGateway;
import com.enterprise.ai.control.mcp.application.protocol.McpProtocolApplicationService.McpAuthenticatedSession;
import com.enterprise.ai.control.mcp.application.protocol.McpProtocolApplicationService.McpToolCallResult;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.client.McpClient;
import com.enterprise.ai.control.mcp.domain.client.McpClientStatus;
import com.enterprise.ai.control.mcp.domain.publication.McpPublication;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationRevision;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationStatus;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpProtocolApplicationServiceTest {

    private static final String API_KEY = "mcp_test";
    private static final String API_KEY_HASH = "bb9c9abad60424739d208a28953a4fef9e624189af10a5f4818d258534d352ea";
    private static final long PUBLICATION_ID = 10L;
    private static final long REVISION_ID = 91L;

    private McpClientRepository clientRepository;
    private McpPublicationRepository publicationRepository;
    private McpCallAuditRepository callAuditRepository;
    private ControlToolAclDecisionService aclDecisionService;
    private McpRuntimeExecutionGateway executionGateway;
    private McpProtocolApplicationService service;

    @BeforeEach
    void setUp() {
        clientRepository = mock(McpClientRepository.class);
        publicationRepository = mock(McpPublicationRepository.class);
        callAuditRepository = mock(McpCallAuditRepository.class);
        aclDecisionService = mock(ControlToolAclDecisionService.class);
        executionGateway = mock(McpRuntimeExecutionGateway.class);
        service = new McpProtocolApplicationService(
                clientRepository, publicationRepository, callAuditRepository,
                aclDecisionService, executionGateway, new ObjectMapper());
    }

    private McpToolProjection tool(String name, String riskLevel) {
        return new McpToolProjection(
                name, "tool " + name,
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}",
                com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind.CAPABILITY,
                "orders:" + name, null, riskLevel);
    }

    private McpPublicationRevision revision(List<McpToolProjection> tools) {
        return new McpPublicationRevision(
                REVISION_ID, PUBLICATION_ID, 3, tools, null, LocalDateTime.now());
    }

    private McpClient client(McpClientStatus state, List<String> toolScope) {
        return new McpClient(
                2L, PUBLICATION_ID, "Codex", 8L, "orders", "test", "tenant-a",
                "mcp_test", API_KEY_HASH,
                List.of("ops"), toolScope, state, true, null, null, null, null);
    }

    private McpPublication published(Long revisionId) {
        return new McpPublication(
                PUBLICATION_ID, "order-tools", "desc",
                McpPublicationStatus.PUBLISHED, revisionId, null, null);
    }

    private McpAuthenticatedSession session(McpClient client, McpPublicationRevision revision) {
        when(clientRepository.findByApiKeyHash(API_KEY_HASH)).thenReturn(Optional.of(client));
        when(publicationRepository.findById(PUBLICATION_ID))
                .thenReturn(Optional.of(published(revision.id())));
        when(publicationRepository.findRevision(PUBLICATION_ID, revision.id()))
                .thenReturn(Optional.of(revision));
        return service.authenticate(API_KEY);
    }

    @Test
    void authenticateResolvesPublicationAndFrozenRevision() {
        McpAuthenticatedSession session = session(
                client(McpClientStatus.ACTIVE, List.of()),
                revision(List.of(tool("order_search", "READ"))));

        assertEquals(2L, session.client().id());
        assertEquals(PUBLICATION_ID, session.publication().id());
        assertEquals(3, session.revision().revisionNo());
        verify(clientRepository).touchLastUsed(eq(2L), any(LocalDateTime.class));
    }

    @Test
    void rotatedClientIsRejectedAsAuthFailure() {
        when(clientRepository.findByApiKeyHash(API_KEY_HASH))
                .thenReturn(Optional.of(client(McpClientStatus.ROTATED, List.of())));

        McpDomainException exception = assertThrows(McpDomainException.class,
                () -> service.authenticate(API_KEY));

        assertEquals("MCP_CLIENT_ROTATED", exception.code());
    }

    @Test
    void elapsedCredentialIsPersistedAsTerminalExpiredBeforeRejection() {
        McpClient elapsed = new McpClient(
                2L, PUBLICATION_ID, "Codex", 8L, "orders", "test", "tenant-a",
                "mcp_test", API_KEY_HASH,
                List.of("ops"), List.of(), McpClientStatus.ACTIVE, true,
                LocalDateTime.now().minusMinutes(1), null, null, null);
        when(clientRepository.findByApiKeyHash(API_KEY_HASH)).thenReturn(Optional.of(elapsed));
        when(clientRepository.save(any(McpClient.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        McpDomainException exception = assertThrows(McpDomainException.class,
                () -> service.authenticate(API_KEY));

        assertEquals("MCP_CLIENT_EXPIRED", exception.code());
        verify(clientRepository).save(org.mockito.ArgumentMatchers.argThat(client ->
                client.state() == McpClientStatus.EXPIRED));
    }

    @Test
    void unknownTokenIsRejectedAsUnauthenticated() {
        when(clientRepository.findByApiKeyHash(API_KEY_HASH)).thenReturn(Optional.empty());

        McpDomainException exception = assertThrows(McpDomainException.class,
                () -> service.authenticate("unknown"));

        assertEquals("MCP_CLIENT_UNAUTHENTICATED", exception.code());
    }

    @Test
    void suspendedPublicationIsRejected() {
        when(clientRepository.findByApiKeyHash(API_KEY_HASH))
                .thenReturn(Optional.of(client(McpClientStatus.ACTIVE, List.of())));
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(
                new McpPublication(PUBLICATION_ID, "order-tools", "desc",
                        McpPublicationStatus.SUSPENDED, REVISION_ID, null, null)));

        McpDomainException exception = assertThrows(McpDomainException.class,
                () -> service.authenticate(API_KEY));

        assertEquals("MCP_PUBLICATION_NOT_PUBLISHED", exception.code());
    }

    @Test
    void toolsListServesTheFrozenRevisionSchema() {
        McpAuthenticatedSession session = session(
                client(McpClientStatus.ACTIVE, List.of()),
                revision(List.of(tool("order_search", "READ"))));

        List<Map<String, Object>> tools = service.visibleTools(session);

        assertEquals(1, tools.size());
        assertEquals("order_search", tools.get(0).get("name"));
        assertEquals("tool order_search", tools.get(0).get("description"));
        com.fasterxml.jackson.databind.JsonNode schema =
                (com.fasterxml.jackson.databind.JsonNode) tools.get(0).get("inputSchema");
        assertEquals("object", schema.get("type").asText());
        assertTrue(schema.get("properties").has("id"));
    }

    @Test
    void toolsListFailsClosedWhenFrozenSchemaIsNotAJsonObject() {
        McpToolProjection invalid = new McpToolProjection(
                "order_search", "tool order_search", "[]",
                McpPublicationItemKind.CAPABILITY, "orders:order_search", null, "READ");
        McpAuthenticatedSession session = session(
                client(McpClientStatus.ACTIVE, List.of()), revision(List.of(invalid)));

        McpDomainException failure = assertThrows(McpDomainException.class,
                () -> service.visibleTools(session));

        assertEquals("MCP_PUBLICATION_REVISION_INVALID", failure.code());
    }

    @Test
    void rollbackChangesToolsListImmediately() {
        McpPublicationRevision newer = revision(List.of(
                tool("order_search", "READ"), tool("order_sync", "WRITE")));
        McpAuthenticatedSession withNewer = session(client(McpClientStatus.ACTIVE, List.of()), newer);
        assertEquals(2, service.visibleTools(withNewer).size());

        // Rollback only repoints current_revision_id; the next authenticate serves the old snapshot.
        McpPublicationRevision older = new McpPublicationRevision(
                90L, PUBLICATION_ID, 2, List.of(tool("order_search", "READ")),
                null, LocalDateTime.now().minusDays(1));
        when(publicationRepository.findById(PUBLICATION_ID))
                .thenReturn(Optional.of(published(90L)));
        when(publicationRepository.findRevision(PUBLICATION_ID, 90L))
                .thenReturn(Optional.of(older));

        McpAuthenticatedSession rolledBack = service.authenticate(API_KEY);

        assertEquals(1, service.visibleTools(rolledBack).size());
        assertEquals("order_search", service.visibleTools(rolledBack).get(0).get("name"));
    }

    @Test
    void toolScopeNarrowsToolsList() {
        McpAuthenticatedSession session = session(
                client(McpClientStatus.ACTIVE, List.of("order_search")),
                revision(List.of(tool("order_search", "READ"), tool("order_sync", "WRITE"))));

        List<Map<String, Object>> tools = service.visibleTools(session);

        assertEquals(1, tools.size());
        assertEquals("order_search", tools.get(0).get("name"));
    }

    @Test
    void irreversibleToolIsHiddenByDefault() {
        McpAuthenticatedSession session = session(
                client(McpClientStatus.ACTIVE, List.of()),
                revision(List.of(tool("order_cancel", "IRREVERSIBLE"), tool("order_search", "READ"))));

        List<Map<String, Object>> tools = service.visibleTools(session);

        assertEquals(1, tools.size());
        assertEquals("order_search", tools.get(0).get("name"));
    }

    @Test
    void irreversibleToolRequiresExplicitScopeOptIn() {
        McpAuthenticatedSession session = session(
                client(McpClientStatus.ACTIVE, List.of("order_cancel")),
                revision(List.of(tool("order_cancel", "IRREVERSIBLE"))));

        assertEquals(1, service.visibleTools(session).size());

        when(aclDecisionService.decide(List.of("ops"), 8L, "orders", "TOOL", "orders:order_cancel"))
                .thenReturn(ControlToolAclDecisionService.DECISION_ALLOW);
        when(executionGateway.execute(any())).thenReturn(new McpRuntimeExecutionGateway.ExecutionOutcome(
                true, null, Map.of("cancelled", true), "run-irr", "trace-irr", null));
        McpToolCallResult result = service.callTool(session, "order_cancel", Map.of("id", "O-1"));
        assertTrue(result.success());
    }

    @Test
    void toolsCallOutsideRevisionIsRejected() {
        McpAuthenticatedSession session = session(
                client(McpClientStatus.ACTIVE, List.of()),
                revision(List.of(tool("order_search", "READ"))));

        McpDomainException exception = assertThrows(McpDomainException.class,
                () -> service.callTool(session, "order_missing", Map.of()));

        assertEquals("MCP_TOOL_NOT_VISIBLE", exception.code());
    }

    @Test
    void toolsCallOutsideScopeIsRejectedAsPolicy() {
        McpAuthenticatedSession session = session(
                client(McpClientStatus.ACTIVE, List.of("order_search")),
                revision(List.of(tool("order_search", "READ"), tool("order_sync", "WRITE"))));

        McpDomainException exception = assertThrows(McpDomainException.class,
                () -> service.callTool(session, "order_sync", Map.of()));

        assertEquals("MCP_TOOL_SCOPE_DENIED", exception.code());
    }

    @Test
    void toolsCallAclDenyIsRejectedAsPolicy() {
        McpAuthenticatedSession session = session(
                client(McpClientStatus.ACTIVE, List.of()),
                revision(List.of(tool("order_search", "READ"))));
        when(aclDecisionService.decide(List.of("ops"), 8L, "orders", "TOOL", "orders:order_search"))
                .thenReturn(ControlToolAclDecisionService.DECISION_DENY_NO_MATCH);

        McpDomainException exception = assertThrows(McpDomainException.class,
                () -> service.callTool(session, "order_search", Map.of()));

        assertEquals("MCP_TOOL_ACL_DENIED", exception.code());
        verify(executionGateway, org.mockito.Mockito.never()).execute(any());
    }

    @Test
    void toolsCallExecutesThroughTheRuntimeGateway() {
        McpAuthenticatedSession session = session(
                client(McpClientStatus.ACTIVE, List.of()),
                revision(List.of(tool("order_search", "READ"))));
        when(aclDecisionService.decide(List.of("ops"), 8L, "orders", "TOOL", "orders:order_search"))
                .thenReturn(ControlToolAclDecisionService.DECISION_ALLOW);
        when(executionGateway.execute(any())).thenReturn(new McpRuntimeExecutionGateway.ExecutionOutcome(
                true, null, Map.of("data", Map.of("orderId", "O-1")), "run-1", "trace-1", null));

        McpToolCallResult result = service.callTool(session, "order_search", Map.of("orderId", "O-1"));

        assertTrue(result.success());
        assertEquals(Map.of("data", Map.of("orderId", "O-1")), result.output());
        assertEquals("run-1", result.runId());
        verify(executionGateway).execute(org.mockito.ArgumentMatchers.argThat(command ->
                "CAPABILITY".equals(command.sourceKind())
                        && "orders:order_search".equals(command.sourceRef())
                        && "order_search".equals(command.toolName())
                        && command.mcpClientId() == 2L
                        && command.projectId() == 8L
                        && "orders".equals(command.projectCode())
                        && "test".equals(command.environment())
                        && "tenant-a".equals(command.tenantId())
                        && command.publicationId() == PUBLICATION_ID
                        && command.revisionNo() == 3));
    }

    @Test
    void runtimeFailureIsReportedNotMasked() {
        McpAuthenticatedSession session = session(
                client(McpClientStatus.ACTIVE, List.of()),
                revision(List.of(tool("order_search", "READ"))));
        when(aclDecisionService.decide(any(), eq(8L), eq("orders"), anyString(), anyString()))
                .thenReturn(ControlToolAclDecisionService.DECISION_ALLOW);
        when(executionGateway.execute(any())).thenReturn(new McpRuntimeExecutionGateway.ExecutionOutcome(
                false, "MCP_CAPABILITY_TECHNICAL_FAILED", Map.of(), null, null,
                "MCP_CAPABILITY_TECHNICAL_FAILED"));

        McpToolCallResult result = service.callTool(session, "order_search", Map.of());

        assertFalse(result.success());
        assertEquals("MCP_CAPABILITY_TECHNICAL_FAILED", result.errorCode());
    }

    @Test
    void runtimeBoundaryExceptionBecomesAStableToolFailure() {
        McpAuthenticatedSession session = session(
                client(McpClientStatus.ACTIVE, List.of()),
                revision(List.of(tool("order_search", "READ"))));
        when(aclDecisionService.decide(any(), eq(8L), eq("orders"), anyString(), anyString()))
                .thenReturn(ControlToolAclDecisionService.DECISION_ALLOW);
        when(executionGateway.execute(any())).thenThrow(new IllegalStateException("internal endpoint detail"));

        McpToolCallResult result = service.callTool(session, "order_search", Map.of());

        assertFalse(result.success());
        assertEquals("MCP_RUNTIME_EXECUTION_BOUNDARY_FAILED", result.errorCode());
        assertNull(result.runId());
        assertNull(result.traceId());
    }

    @Test
    void auditWritesOutboundDirectionAndErrorCategory() {
        service.audit(new McpProtocolApplicationService.McpAuditRequest(
                null, "tools/call", "order_search", false, 42L, "POLICY",
                null, "{}", "MCP_TOOL_ACL_DENIED", null, null, "10.0.0.1"));

        verify(callAuditRepository).append(org.mockito.ArgumentMatchers.argThat(entry ->
                "OUTBOUND".equals(entry.direction())
                        && entry.clientId() == null
                        && "tools/call".equals(entry.method())
                        && "order_search".equals(entry.toolName())
                        && !entry.success()
                        && entry.latencyMs() == 42L
                        && "POLICY".equals(entry.errorCategory())
                        && "MCP_TOOL_ACL_DENIED".equals(entry.errorMessage())
                        && "10.0.0.1".equals(entry.remoteIp())));
    }

    @Test
    void auditNormalizesMissingAndOversizedUntrustedFieldsToTheSchemaContract() {
        service.audit(new McpProtocolApplicationService.McpAuditRequest(
                null, null, "x".repeat(200), false, 1L, "p".repeat(40),
                null, "{}", "e".repeat(2100), "t".repeat(80),
                "r".repeat(80), "i".repeat(80)));

        verify(callAuditRepository).append(org.mockito.ArgumentMatchers.argThat(entry ->
                "unknown".equals(entry.method())
                        && entry.toolName().length() == 128
                        && entry.errorCategory().length() == 32
                        && entry.errorMessage().length() == 2000
                        && entry.traceId().length() == 64
                        && entry.runId().length() == 64
                        && entry.remoteIp().length() == 64));
    }
}

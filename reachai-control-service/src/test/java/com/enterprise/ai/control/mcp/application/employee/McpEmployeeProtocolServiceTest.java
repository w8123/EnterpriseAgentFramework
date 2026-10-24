package com.enterprise.ai.control.mcp.application.employee;

import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.identity.BusinessUserSyncService;
import com.enterprise.ai.control.identity.BusinessUserSyncService.BusinessSystemAccount;
import com.enterprise.ai.control.mcp.application.port.McpCallAuditRepository;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.enterprise.ai.control.mcp.application.port.McpRuntimeExecutionGateway;
import com.enterprise.ai.control.mcp.application.port.McpRuntimeExecutionGateway.EmployeeToolExecutionCommand;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnection;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnection.ConnectedSystem;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnectionStatus;
import com.enterprise.ai.control.mcp.domain.employee.AiToolKind;
import com.enterprise.ai.control.mcp.domain.employee.McpEmployeeAccess;
import com.enterprise.ai.control.mcp.domain.publication.McpPublication;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationRevision;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationStatus;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpEmployeeProtocolServiceTest {

    private static final String KEY = "rai_" + "a".repeat(64);

    private final AiToolConnectionServiceTest.FakeConnections connections = new AiToolConnectionServiceTest.FakeConnections();
    private final AiToolConnectionServiceTest.FakeAccess access = new AiToolConnectionServiceTest.FakeAccess();
    private final McpPublicationRepository publications = mock(McpPublicationRepository.class);
    private final BusinessUserSyncService sync = mock(BusinessUserSyncService.class);
    private final ControlToolAclDecisionService acl = mock(ControlToolAclDecisionService.class);
    private final McpRuntimeExecutionGateway gateway = mock(McpRuntimeExecutionGateway.class);
    private final McpCallAuditRepository audit = mock(McpCallAuditRepository.class);
    private McpEmployeeProtocolService service;

    @BeforeEach
    void setUp() {
        service = new McpEmployeeProtocolService(connections, access, publications, sync, acl, gateway, audit,
                new ObjectMapper());
        access.save(new McpEmployeeAccess(null, 11L, "yudao-erp", "芋道 ERP", true));
        access.save(new McpEmployeeAccess(null, 12L, 3L, "crm", "客户管理", true));
        published(11L, List.of(tool("erpStockPage", "READ", "yudao-erp.erpStockPage"),
                tool("erpCustomerDelete", "IRREVERSIBLE", "yudao-erp.erpCustomerDelete"),
                tool("search", "READ", "yudao-erp.search")));
        published(12L, List.of(tool("search", "READ", "crm.search")));
        when(sync.activeAccount(eq("default"), eq(7L), eq("yudao-erp")))
                .thenReturn(Optional.of(new BusinessSystemAccount("yudao-erp", "1", "张三", List.of("erp_user"))));
        when(sync.activeAccount(eq("default"), eq(7L), eq("crm")))
                .thenReturn(Optional.of(new BusinessSystemAccount("crm", "zs", "张三", List.of("sales"))));
    }

    @Test
    void unknownRevokedOrExpiredKeysAreRejected() {
        assertEquals("MCP_CLIENT_UNAUTHENTICATED",
                assertThrows(McpDomainException.class, () -> service.authenticate("mcp_x")).code());
        assertEquals("MCP_CLIENT_UNAUTHENTICATED",
                assertThrows(McpDomainException.class, () -> service.authenticate(KEY)).code());

        long id = connect(LocalDateTime.now().plusDays(1), "yudao-erp").id();
        connections.revoke(id, LocalDateTime.now());
        assertEquals("MCP_CLIENT_REVOKED",
                assertThrows(McpDomainException.class, () -> service.authenticate(KEY)).code());
    }

    @Test
    void expiredConnectionIsPersistedAsExpired() {
        long id = connect(LocalDateTime.now().minusMinutes(1), "yudao-erp").id();

        assertEquals("MCP_CLIENT_EXPIRED",
                assertThrows(McpDomainException.class, () -> service.authenticate(KEY)).code());
        assertEquals(AiToolConnectionStatus.EXPIRED, connections.rows.get(id).state());
    }

    @Test
    void employeeNoLongerActiveAnywhereCannotUseTheConnection() {
        connect(LocalDateTime.now().plusDays(1), "yudao-erp");
        when(sync.activeAccount(any(), anyLong(), eq("yudao-erp"))).thenReturn(Optional.empty());

        assertEquals("MCP_CLIENT_DISABLED",
                assertThrows(McpDomainException.class, () -> service.authenticate(KEY)).code());
    }

    @Test
    void toolsHideIrreversibleAndPrefixOnlyCollidingNames() {
        connect(LocalDateTime.now().plusDays(1), "yudao-erp", "crm");

        var names = service.visibleTools(service.authenticate(KEY)).stream().map(t -> t.get("name")).toList();

        assertEquals(List.of("erpStockPage", "yudao-erp__search", "crm__search"), names);
    }

    @Test
    void callIsDeniedWithoutTheEmployeesCallPermission() {
        connect(LocalDateTime.now().plusDays(1), "yudao-erp");
        when(acl.decide(eq(List.of("erp_user")), eq(null), eq("yudao-erp"), eq("TOOL"),
                eq("yudao-erp.erpStockPage"))).thenReturn("DENY");
        var session = service.authenticate(KEY);

        McpDomainException denied = assertThrows(McpDomainException.class,
                () -> service.callTool(session, "erpStockPage", Map.of()));
        assertEquals("MCP_TOOL_ACL_DENIED", denied.code());
        verify(gateway, never()).executeAsEmployee(any());

        assertEquals("MCP_TOOL_NOT_VISIBLE", assertThrows(McpDomainException.class,
                () -> service.callTool(session, "erpCustomerDelete", Map.of())).code());
    }

    @Test
    void allowedCallRunsAsTheEmployeesAccountInThatSystem() {
        AiToolConnection connection = connect(LocalDateTime.now().plusDays(1), "yudao-erp", "crm");
        when(acl.decide(eq(List.of("sales")), eq(3L), eq("crm"), eq("TOOL"), eq("crm.search")))
                .thenReturn(ControlToolAclDecisionService.DECISION_ALLOW);
        when(gateway.executeAsEmployee(any())).thenReturn(new McpRuntimeExecutionGateway.ExecutionOutcome(
                true, "OK", Map.of("data", List.of()), "9", "mcp_t", null));
        var session = service.authenticate(KEY);

        var called = service.callTool(session, "crm__search", Map.of("q", "鼠标"));

        assertTrue(called.result().success());
        ArgumentCaptor<EmployeeToolExecutionCommand> command = ArgumentCaptor.forClass(EmployeeToolExecutionCommand.class);
        verify(gateway).executeAsEmployee(command.capture());
        assertEquals("zs", command.getValue().externalUserId());
        assertEquals("crm", command.getValue().projectCode());
        assertEquals(3L, command.getValue().projectId());
        assertEquals("crm.search", command.getValue().sourceRef());
        assertEquals("search", command.getValue().toolName());
        assertEquals("default", command.getValue().tenantId());
        assertEquals(connection.id(), command.getValue().connectionId());
        assertEquals("CLAUDE_CODE", command.getValue().aiToolKind());
        assertEquals(12L, command.getValue().publicationId());

        service.audit(new McpEmployeeProtocolService.EmployeeAuditRequest(session, called.system(), "tools/call",
                "crm__search", true, 5L, null, null, null, null, "mcp_t", "9", "127.0.0.1"));
        ArgumentCaptor<McpCallAuditRepository.Entry> entry = ArgumentCaptor.forClass(McpCallAuditRepository.Entry.class);
        verify(audit).append(entry.capture());
        assertEquals(connection.id(), entry.getValue().connectionId());
        assertEquals("zs", entry.getValue().externalUserId());
        assertEquals("CLAUDE_CODE", entry.getValue().toolClientName());
        assertEquals("crm", entry.getValue().projectCode());
    }

    private AiToolConnection connect(LocalDateTime expiresAt, String... projectCodes) {
        List<ConnectedSystem> systems = java.util.Arrays.stream(projectCodes)
                .map(code -> new ConnectedSystem(code, "crm".equals(code) ? 12L : 11L)).toList();
        return connections.create(new AiToolConnection(null, "default", 7L, "yudao-erp", "1", "张三",
                AiToolKind.CLAUDE_CODE, KEY.substring(0, 12), AiToolConnectionStatus.ACTIVE, expiresAt, null,
                LocalDateTime.now(), systems), AiToolConnectionService.sha256Hex(KEY));
    }

    private void published(long publicationId, List<McpToolProjection> tools) {
        when(publications.findById(publicationId)).thenReturn(Optional.of(new McpPublication(
                publicationId, "pub" + publicationId, "", McpPublicationStatus.PUBLISHED,
                publicationId * 100, null, null)));
        when(publications.findRevision(eq(publicationId), eq(publicationId * 100))).thenReturn(Optional.of(
                new McpPublicationRevision(publicationId * 100, publicationId, 1, tools, null, LocalDateTime.now())));
    }

    private static McpToolProjection tool(String name, String risk, String sourceRef) {
        return new McpToolProjection(name, "d", "{\"type\":\"object\"}", McpPublicationItemKind.CAPABILITY,
                sourceRef, null, risk, "c".repeat(64));
    }
}

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
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryService;
import com.enterprise.ai.control.memory.PersonalMemorySharedQueryService;
import com.enterprise.ai.control.memory.PersonalMemorySharedMutationService;
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
        when(sync.hasActiveOrigin(any(), any(), any(), any())).thenReturn(true);
        service = new McpEmployeeProtocolService(connections, access, publications, sync, acl, gateway, audit,
                new McpWriteApprovalServiceTest.FakeApprovals(), new ObjectMapper());
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
    void revokedOriginCannotUseConnectionEvenWhenAnotherSystemRemainsActive() {
        connect(LocalDateTime.now().plusDays(1), "yudao-erp", "crm");
        when(sync.hasActiveOrigin(eq("default"), eq(7L), eq("yudao-erp"), eq("1"))).thenReturn(false);
        assertEquals("MCP_CLIENT_DISABLED", assertThrows(McpDomainException.class,
                () -> service.authenticate(KEY)).code());
        verify(gateway, never()).executeAsEmployee(any());
    }

    @Test
    void toolsHideIrreversibleAndPrefixOnlyCollidingNames() {
        connect(LocalDateTime.now().plusDays(1), "yudao-erp", "crm");

        var names = service.visibleTools(service.authenticate(KEY)).stream().map(t -> t.get("name")).toList();

        assertEquals(List.of("erpStockPage", "yudao-erp__search", "crm__search"), names);
    }

    @Test
    void legacyRiskLabelsAreNotExposedOrDispatched() {
        var legacy = new McpToolProjection("oldWrite", "d", "{}", McpPublicationItemKind.CAPABILITY,
                "yudao-erp.oldWrite", null, "READ", "c".repeat(64));
        published(11L, List.of(legacy));
        connect(LocalDateTime.now().plusDays(1), "yudao-erp");
        var session = service.authenticate(KEY);
        assertTrue(service.visibleTools(session).isEmpty());
        assertTrue(service.instructions(session).contains("重新校验并发布"));
        assertEquals("MCP_TOOL_NOT_VISIBLE", assertThrows(McpDomainException.class,
                () -> service.callTool(session, "oldWrite", Map.of())).code());
        verify(gateway, never()).executeAsEmployee(any());
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
        assertEquals(3L, entry.getValue().projectId());
    }

    @Test
    void memoryToolsWorkOnTheEmployeesOwnMemoriesAndOnlyConnectedProjects() {
        PersonalMemoryService memories = mock(PersonalMemoryService.class);
        McpEmployeeProtocolService withMemory = new McpEmployeeProtocolService(connections, access, publications,
                sync, acl, gateway, audit, new McpWriteApprovalServiceTest.FakeApprovals(), new ObjectMapper(),
                new EmployeeMemoryTools(memories));
        connect(LocalDateTime.now().plusDays(1), "yudao-erp");
        var session = withMemory.authenticate(KEY);
        PersonalMemoryPrincipal owner = PersonalMemoryPrincipal.business("default", "1", "yudao-erp");
        when(memories.remember(eq(owner), any())).thenReturn(new PersonalMemoryService.RememberResult(
                new PersonalMemoryService.MemoryView(5L, "NOTE", null, "查库存默认看 1 号仓", List.of(), "PROJECT",
                        "project:yudao-erp", "yudao-erp", "芋道 ERP", 21L, "备注", "AI_TOOL", "Claude Code",
                        null, LocalDateTime.now(), LocalDateTime.now()), true, false, false));

        var visible = withMemory.visibleTools(session);
        var names = visible.stream().map(t -> t.get("name")).toList();
        assertTrue(names.containsAll(List.of(EmployeeMemoryTools.SEARCH, EmployeeMemoryTools.SAVE,
                EmployeeMemoryTools.FORGET)));
        for (var item : visible) {
            if (!List.of(EmployeeMemoryTools.SEARCH, EmployeeMemoryTools.SAVE, EmployeeMemoryTools.FORGET)
                    .contains(item.get("name"))) continue;
            @SuppressWarnings("unchecked")
            var hints = (Map<String, Object>) item.get("annotations");
            @SuppressWarnings("unchecked")
            var metadata = (Map<String, Object>) item.get("_meta");
            boolean readOnly = EmployeeMemoryTools.SEARCH.equals(item.get("name"));
            assertEquals(readOnly, hints.get("readOnlyHint"));
            assertEquals(readOnly ? "READ" : "WRITE", metadata.get("reachai/riskLevel"));
            assertEquals(false, metadata.get("reachai/requiresApproval"));
        }

        var saved = withMemory.callTool(session, EmployeeMemoryTools.SAVE,
                Map.of("content", "查库存默认看 1 号仓", "projectCode", "yudao-erp"));
        assertTrue(saved.result().success());
        assertEquals("芋道 ERP · 备注", saved.result().output().get("where"));
        ArgumentCaptor<PersonalMemoryService.RememberCommand> command =
                ArgumentCaptor.forClass(PersonalMemoryService.RememberCommand.class);
        verify(memories).remember(eq(owner), command.capture());
        assertEquals("yudao-erp", command.getValue().projectCode());
        assertEquals(PersonalMemoryService.SOURCE_AI_TOOL, command.getValue().source());
        assertEquals("Claude Code", command.getValue().agentId());
        verify(gateway, never()).executeAsEmployee(any());

        var foreign = withMemory.callTool(session, EmployeeMemoryTools.SAVE,
                Map.of("content", "x", "projectCode", "crm"));
        assertEquals("MCP_MEMORY_PROJECT", foreign.result().errorCode());

        when(memories.forgetInProjects(eq(owner), eq(99L), eq(java.util.Set.of("yudao-erp")))).thenThrow(
                new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        var missing = withMemory.callTool(session, EmployeeMemoryTools.FORGET, Map.of("memoryId", 99));
        assertEquals("MCP_MEMORY_REJECTED", missing.result().errorCode());
        assertEquals("没有找到这条记忆", missing.result().output().get("message"));
    }

    @Test
    void removedSystemIsExcludedFromMemoryOperationsOnNextAuthentication() {
        PersonalMemoryService memories = mock(PersonalMemoryService.class);
        McpEmployeeProtocolService withMemory = new McpEmployeeProtocolService(connections, access, publications,
                sync, acl, gateway, audit, new McpWriteApprovalServiceTest.FakeApprovals(), new ObjectMapper(),
                new EmployeeMemoryTools(memories));
        connect(LocalDateTime.now().plusDays(1), "yudao-erp", "crm");
        assertEquals(2, withMemory.authenticate(KEY).systems().size());

        when(sync.activeAccount(eq("default"), eq(7L), eq("crm"))).thenReturn(Optional.empty());
        var afterRevocation = withMemory.authenticate(KEY);
        assertEquals(1, afterRevocation.systems().size());
        var rejected = withMemory.callTool(afterRevocation, EmployeeMemoryTools.SEARCH,
                Map.of("projectCode", "crm"));
        assertEquals("MCP_MEMORY_PROJECT", rejected.result().errorCode());
        verify(memories, never()).query(any(), any());
        withMemory.callTool(afterRevocation, EmployeeMemoryTools.FORGET, Map.of("memoryId", 99));
        verify(memories).forgetInProjects(eq(PersonalMemoryPrincipal.business("default", "1", "yudao-erp")),
                eq(99L), eq(java.util.Set.of("yudao-erp")));
    }

    @Test
    void employeeMemoryToolsUseVerifiedSharedQueryAndGuardedDeletion() {
        var memories = mock(PersonalMemoryService.class);
        var shared = mock(PersonalMemorySharedQueryService.class);
        var mutation = mock(PersonalMemorySharedMutationService.class);
        var peer = PersonalMemoryPrincipal.business("default", "bob", "crm");
        var hit = new PersonalMemoryService.QueryHit(55L, "PREFERENCE", null, "共享偏好", 2, null, null);
        when(shared.query(any(), any())).thenReturn(new PersonalMemorySharedQueryService.SharedResult(
                List.of(new PersonalMemorySharedQueryService.SharedHit(peer, 9L, hit)), 4, false, false));
        var protocol = new McpEmployeeProtocolService(connections, access, publications, sync, acl, gateway, audit,
                new McpWriteApprovalServiceTest.FakeApprovals(), new ObjectMapper(),
                new EmployeeMemoryTools(memories, shared, mutation));
        connect(LocalDateTime.now().plusDays(1), "yudao-erp");
        var session = protocol.authenticate(KEY);
        var found = protocol.callTool(session, EmployeeMemoryTools.SEARCH, Map.of("query", "偏好"));
        assertTrue(found.result().success());
        var rows = (List<?>) found.result().output().get("memories");
        assertEquals("共享个人记忆", ((Map<?, ?>) rows.get(0)).get("where"));
        assertTrue(protocol.instructions(session).contains("共享偏好"));
        assertTrue(protocol.callTool(session, EmployeeMemoryTools.FORGET, Map.of("memoryId", 55)).result().success());
        verify(mutation).forget(eq(PersonalMemoryPrincipal.business("default", "1", "yudao-erp")),
                eq(55L), eq(java.util.Set.of("yudao-erp")));
        verify(memories, never()).forgetInProjects(any(), any(), any());
    }

    @Test
    void handshakeInstructionsCarryTheEmployeesPreferences() {
        PersonalMemoryService memories = mock(PersonalMemoryService.class);
        McpEmployeeProtocolService withMemory = new McpEmployeeProtocolService(connections, access, publications,
                sync, acl, gateway, audit, new McpWriteApprovalServiceTest.FakeApprovals(), new ObjectMapper(),
                new EmployeeMemoryTools(memories));
        connect(LocalDateTime.now().plusDays(1), "yudao-erp");
        when(memories.query(any(), any())).thenAnswer(invocation -> {
            PersonalMemoryService.QueryCommand query = invocation.getArgument(1);
            List<PersonalMemoryService.QueryHit> hits = query.projectCode() == null
                    ? List.of(new PersonalMemoryService.QueryHit(1L, "PREFERENCE", null, "回答用中文", 1, null, null))
                    : List.of(new PersonalMemoryService.QueryHit(1L, "PREFERENCE", null, "回答用中文", 1, null, null),
                            new PersonalMemoryService.QueryHit(2L, "PREFERENCE", null, "部门默认填华东销售部", 1, null,
                                    "yudao-erp"));
            return new PersonalMemoryService.QueryResult(hits, hits.size(), 10, 1500, false);
        });

        String instructions = withMemory.instructions(withMemory.authenticate(KEY));

        assertTrue(instructions.contains("- 回答用中文"));
        assertTrue(instructions.contains("- 【芋道 ERP】部门默认填华东销售部"));
        assertEquals(1, instructions.split("回答用中文", -1).length - 1);
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
                sourceRef, null, risk, "c".repeat(64), risk, 1);
    }
}

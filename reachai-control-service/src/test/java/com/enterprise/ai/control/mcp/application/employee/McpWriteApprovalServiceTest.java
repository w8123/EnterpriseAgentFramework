package com.enterprise.ai.control.mcp.application.employee;

import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.identity.BusinessUserSyncService;
import com.enterprise.ai.control.identity.BusinessUserSyncService.BusinessSystemAccount;
import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.EmployeeOwner;
import com.enterprise.ai.control.mcp.application.port.McpCallAuditRepository;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.enterprise.ai.control.mcp.application.port.McpRuntimeExecutionGateway;
import com.enterprise.ai.control.mcp.application.port.McpRuntimeExecutionGateway.EmployeeToolExecutionCommand;
import com.enterprise.ai.control.mcp.application.port.McpWriteApprovalRepository;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnection;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnection.ConnectedSystem;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnectionStatus;
import com.enterprise.ai.control.mcp.domain.employee.AiToolKind;
import com.enterprise.ai.control.mcp.domain.employee.McpEmployeeAccess;
import com.enterprise.ai.control.mcp.domain.employee.McpWriteApproval;
import com.enterprise.ai.control.mcp.domain.employee.McpWriteApprovalStatus;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpWriteApprovalServiceTest {

    private static final String KEY = "rai_" + "b".repeat(64);

    private final AiToolConnectionServiceTest.FakeConnections connections = new AiToolConnectionServiceTest.FakeConnections();
    private final AiToolConnectionServiceTest.FakeAccess access = new AiToolConnectionServiceTest.FakeAccess();
    private final FakeApprovals approvals = new FakeApprovals();
    private final McpPublicationRepository publications = mock(McpPublicationRepository.class);
    private final BusinessUserSyncService sync = mock(BusinessUserSyncService.class);
    private final ControlToolAclDecisionService acl = mock(ControlToolAclDecisionService.class);
    private final McpRuntimeExecutionGateway gateway = mock(McpRuntimeExecutionGateway.class);
    private final McpCallAuditRepository audit = mock(McpCallAuditRepository.class);
    private McpEmployeeProtocolService protocol;
    private McpWriteApprovalService service;
    private AiToolConnection connection;

    @BeforeEach
    void setUp() {
        protocol = new McpEmployeeProtocolService(connections, access, publications, sync, acl, gateway, audit,
                approvals, new ObjectMapper());
        service = new McpWriteApprovalService(approvals, protocol, new ObjectMapper());
        access.save(new McpEmployeeAccess(null, 11L, 1L, "yudao-erp", "芋道 ERP", true, true));
        when(publications.findById(11L)).thenReturn(Optional.of(new McpPublication(
                11L, "pub11", "", McpPublicationStatus.PUBLISHED, 1100L, null, null)));
        when(publications.findRevision(eq(11L), eq(1100L))).thenReturn(Optional.of(new McpPublicationRevision(
                1100L, 11L, 2, List.of(tool("erpStockPage", "READ"), tool("erpCustomerCreate", "WRITE"),
                tool("erpOrderUpdate", null)), null, LocalDateTime.now())));
        when(sync.activeAccount(eq("default"), eq(7L), eq("yudao-erp")))
                .thenReturn(Optional.of(new BusinessSystemAccount("yudao-erp", "1", "张三", List.of("erp_user"))));
        when(acl.decide(any(), eq(1L), eq("yudao-erp"), eq("TOOL"), any()))
                .thenReturn(ControlToolAclDecisionService.DECISION_ALLOW);
        connection = connections.create(new AiToolConnection(null, "default", 7L, "yudao-erp", "1", "张三",
                AiToolKind.CLAUDE_CODE, KEY.substring(0, 12), AiToolConnectionStatus.ACTIVE,
                LocalDateTime.now().plusDays(1), null, LocalDateTime.now(),
                List.of(new ConnectedSystem("yudao-erp", 11L))), AiToolConnectionService.sha256Hex(KEY));
    }

    @Test
    void writeToolsSayTheyNeedApprovalAndTheStatusToolIsListed() {
        var tools = protocol.visibleTools(protocol.authenticate(KEY));

        assertEquals(List.of("erpStockPage", "erpCustomerCreate", "erpOrderUpdate",
                McpEmployeeProtocolService.APPROVAL_STATUS_TOOL), tools.stream().map(t -> t.get("name")).toList());
        @SuppressWarnings("unchecked")
        Map<String, Object> create = (Map<String, Object>) tools.get(1).get("annotations");
        assertEquals(true, create.get("requiresApproval"));
        @SuppressWarnings("unchecked")
        Map<String, Object> read = (Map<String, Object>) tools.get(0).get("annotations");
        assertFalse(read.containsKey("requiresApproval"));
    }

    @Test
    void writeCallIsQueuedNotExecutedAndRepeatsReuseTheSameRequest() {
        var session = protocol.authenticate(KEY);

        var first = protocol.callTool(session, "erpCustomerCreate", Map.of("name", "华东客户", "level", 1));
        var again = protocol.callTool(session, "erpCustomerCreate", Map.of("level", 1, "name", "华东客户"));
        var unknownRisk = protocol.callTool(session, "erpOrderUpdate", Map.of("id", 3));

        verify(gateway, never()).executeAsEmployee(any());
        assertTrue(first.result().success());
        assertEquals("PENDING", first.result().output().get("approvalStatus"));
        assertEquals(first.result().output().get("approvalId"), again.result().output().get("approvalId"));
        assertEquals(2, approvals.rows.size());
        McpWriteApproval stored = approvals.rows.get(0);
        assertEquals("1", stored.externalUserId());
        assertEquals("yudao-erp.erpCustomerCreate", stored.sourceRef());
        assertEquals("{\"level\":1,\"name\":\"华东客户\"}", stored.argumentsJson());
        assertEquals("PENDING", unknownRisk.result().output().get("approvalStatus"));
    }

    @Test
    void readCallsAndSystemsWithoutApprovalRunDirectly() {
        when(gateway.executeAsEmployee(any())).thenReturn(ok());
        var session = protocol.authenticate(KEY);
        assertTrue(protocol.callTool(session, "erpStockPage", Map.of()).result().success());

        access.save(new McpEmployeeAccess(1L, 11L, 1L, "yudao-erp", "芋道 ERP", true, false));
        access.rows.remove(0);
        var direct = protocol.authenticate(KEY);
        assertTrue(protocol.callTool(direct, "erpCustomerCreate", Map.of("name", "x")).result().success());
        assertTrue(approvals.rows.isEmpty());
        assertFalse(protocol.visibleTools(direct).stream()
                .anyMatch(t -> McpEmployeeProtocolService.APPROVAL_STATUS_TOOL.equals(t.get("name"))));
    }

    @Test
    void approvingRunsOnceAsTheEmployeeWithTheFrozenArguments() {
        long id = submit("erpCustomerCreate", Map.of("name", "华东客户"));
        when(gateway.executeAsEmployee(any())).thenReturn(ok());

        var decided = service.approve(id, "manager01", "同意");

        assertEquals("SUCCEEDED", decided.state());
        assertEquals("manager01", decided.decidedBy());
        ArgumentCaptor<EmployeeToolExecutionCommand> command = ArgumentCaptor.forClass(EmployeeToolExecutionCommand.class);
        verify(gateway).executeAsEmployee(command.capture());
        assertEquals("1", command.getValue().externalUserId());
        assertEquals(Map.of("name", "华东客户"), command.getValue().arguments());
        assertEquals(connection.id(), command.getValue().connectionId());
        assertEquals("MCP_APPROVAL_NOT_PENDING", assertThrows(McpDomainException.class,
                () -> service.approve(id, "manager01", null)).code());

        var status = protocol.callTool(protocol.authenticate(KEY), McpEmployeeProtocolService.APPROVAL_STATUS_TOOL,
                Map.of("approvalId", id));
        assertEquals("SUCCEEDED", status.result().output().get("approvalStatus"));
        assertEquals(Map.of("data", Map.of("id", 42)), status.result().output().get("result"));
    }

    @Test
    void rejectedRequestNeverRunsAndCarriesTheComment() {
        long id = submit("erpCustomerCreate", Map.of("name", "x"));

        var decided = service.reject(id, "ops01", "客户名不规范");

        assertEquals("REJECTED", decided.state());
        assertEquals("客户名不规范", decided.decisionComment());
        verify(gateway, never()).executeAsEmployee(any());
        assertEquals("MCP_APPROVAL_NOT_PENDING", assertThrows(McpDomainException.class,
                () -> service.approve(id, "ops01", null)).code());
    }

    @Test
    void approvalAfterTheConnectionWasRevokedFailsWithoutRunning() {
        long id = submit("erpCustomerCreate", Map.of("name", "x"));
        connections.revoke(connection.id(), LocalDateTime.now());

        var decided = service.approve(id, "manager01", null);

        assertEquals("FAILED", decided.state());
        assertEquals("MCP_APPROVAL_CONNECTION_INACTIVE", decided.errorCode());
        verify(gateway, never()).executeAsEmployee(any());
    }

    @Test
    void approvalWhenTheCallPermissionWasWithdrawnFailsWithoutRunning() {
        long id = submit("erpCustomerCreate", Map.of("name", "x"));
        when(acl.decide(any(), anyLong(), any(), any(), any())).thenReturn("DENY");

        var decided = service.approve(id, "manager01", null);

        assertEquals("FAILED", decided.state());
        assertEquals("MCP_APPROVAL_ACL_DENIED", decided.errorCode());
        verify(gateway, never()).executeAsEmployee(any());
    }

    @Test
    void expiredRequestCannotBeApproved() {
        long id = submit("erpCustomerCreate", Map.of("name", "x"));
        approvals.rows.replaceAll(row -> withExpiry(row, LocalDateTime.now().minusMinutes(1)));

        assertEquals("MCP_APPROVAL_NOT_PENDING", assertThrows(McpDomainException.class,
                () -> service.approve(id, "manager01", null)).code());
        assertEquals("EXPIRED", service.get(id, true).state());
    }

    @Test
    void employeesOnlySeeTheirOwnRequests() {
        long id = submit("erpCustomerCreate", Map.of("name", "x"));
        AiToolConnection other = connections.create(new AiToolConnection(null, "default", 8L, "yudao-erp", "2", "李四",
                AiToolKind.CODEX, "rai_other", AiToolConnectionStatus.ACTIVE, LocalDateTime.now().plusDays(1), null,
                LocalDateTime.now(), List.of(new ConnectedSystem("yudao-erp", 11L))),
                AiToolConnectionService.sha256Hex("rai_" + "c".repeat(64)));
        when(sync.activeAccount(eq("default"), eq(8L), eq("yudao-erp")))
                .thenReturn(Optional.of(new BusinessSystemAccount("yudao-erp", "2", "李四", List.of("erp_user"))));
        var otherSession = protocol.authenticate("rai_" + "c".repeat(64));

        var status = protocol.callTool(otherSession, McpEmployeeProtocolService.APPROVAL_STATUS_TOOL,
                Map.of("approvalId", id));

        assertFalse(status.result().success());
        assertEquals("MCP_APPROVAL_NOT_FOUND", status.result().errorCode());
        assertEquals(1, service.mine(new EmployeeOwner("default", 7L, "yudao-erp", "yudao-erp", "1", "张三",
                List.of())).size());
        assertTrue(service.mine(new EmployeeOwner("default", other.businessUserId(), "yudao-erp", "yudao-erp", "2",
                "李四", List.of())).isEmpty());
    }

    @Test
    void approversSeeArgumentsOthersDoNot() {
        submit("erpCustomerCreate", Map.of("name", "x"));

        assertEquals(Map.of("name", "x"), service.list(null, "PENDING", null, 20, 0, true).items().get(0).arguments());
        assertEquals(null, service.list(null, "PENDING", null, 20, 0, false).items().get(0).arguments());
        assertEquals(1, service.list(null, null, null, 20, 0, false).pendingCount());
    }

    private long submit(String tool, Map<String, Object> arguments) {
        var result = protocol.callTool(protocol.authenticate(KEY), tool, arguments);
        return ((Number) result.result().output().get("approvalId")).longValue();
    }

    private static McpRuntimeExecutionGateway.ExecutionOutcome ok() {
        return new McpRuntimeExecutionGateway.ExecutionOutcome(true, "OK", Map.of("data", Map.of("id", 42)),
                "9", "mcp_t", null);
    }

    private static McpToolProjection tool(String name, String risk) {
        return new McpToolProjection(name, "d", "{\"type\":\"object\"}", McpPublicationItemKind.CAPABILITY,
                "yudao-erp." + name, null, risk, "c".repeat(64));
    }

    private static McpWriteApproval withExpiry(McpWriteApproval a, LocalDateTime expiresAt) {
        return new McpWriteApproval(a.id(), a.tenantId(), a.connectionId(), a.businessUserId(), a.externalUserId(),
                a.userName(), a.toolKind(), a.projectId(), a.projectCode(), a.systemName(), a.publicationId(),
                a.revisionNo(), a.toolName(), a.toolDescription(), a.sourceKind(), a.sourceRef(),
                a.workflowVersionId(), a.capabilityContractHash(), a.riskLevel(), a.argumentsJson(),
                a.argumentsHash(), a.state(), a.decidedBy(), a.decidedAt(), a.decisionComment(), a.executedAt(),
                a.resultJson(), a.errorCode(), a.runId(), a.traceId(), expiresAt, a.createdAt());
    }

    /** In-memory approvals with the same conditional transitions as the MyBatis repository. */
    static final class FakeApprovals implements McpWriteApprovalRepository {
        final List<McpWriteApproval> rows = new ArrayList<>();

        @Override
        public McpWriteApproval create(McpWriteApproval a) {
            McpWriteApproval saved = copy(a, (long) rows.size() + 1, a.state(), a.decidedBy(), a.decidedAt(),
                    a.decisionComment(), a.executedAt(), a.resultJson(), a.errorCode(), a.runId(), a.traceId());
            rows.add(saved);
            return saved;
        }

        @Override
        public Optional<McpWriteApproval> findById(long id) {
            return rows.stream().filter(r -> r.id() == id).findFirst();
        }

        @Override
        public Optional<McpWriteApproval> findOpenDuplicate(long connectionId, String toolName, String argumentsHash,
                                                            LocalDateTime now) {
            return rows.stream().filter(r -> r.connectionId() == connectionId && r.toolName().equals(toolName)
                    && r.argumentsHash().equals(argumentsHash) && r.state() == McpWriteApprovalStatus.PENDING
                    && !r.lapsed(now)).findFirst();
        }

        @Override
        public long countOpenByOwner(String tenantId, long businessUserId, LocalDateTime now) {
            return rows.stream().filter(r -> r.businessUserId() == businessUserId
                    && r.state() == McpWriteApprovalStatus.PENDING && !r.lapsed(now)).count();
        }

        @Override
        public List<McpWriteApproval> findByOwner(String tenantId, long businessUserId, int limit) {
            return rows.stream().filter(r -> r.tenantId().equals(tenantId) && r.businessUserId() == businessUserId)
                    .sorted(Comparator.comparing(McpWriteApproval::id).reversed()).limit(limit).toList();
        }

        @Override
        public boolean decide(long id, McpWriteApprovalStatus next, String decidedBy, String comment,
                              LocalDateTime now) {
            McpWriteApproval row = findById(id).orElseThrow();
            if (row.state() != McpWriteApprovalStatus.PENDING || row.lapsed(now)) {
                return false;
            }
            replace(copy(row, row.id(), next, decidedBy, now, comment, null, null, null, null, null));
            return true;
        }

        @Override
        public void finish(long id, McpWriteApprovalStatus outcome, String resultJson, String errorCode,
                           String runId, String traceId, LocalDateTime now) {
            McpWriteApproval row = findById(id).orElseThrow();
            if (row.state() == McpWriteApprovalStatus.EXECUTING) {
                replace(copy(row, row.id(), outcome, row.decidedBy(), row.decidedAt(), row.decisionComment(), now,
                        resultJson, errorCode, runId, traceId));
            }
        }

        @Override
        public int expireLapsed(LocalDateTime now) {
            int[] count = {0};
            rows.replaceAll(r -> {
                if (!r.lapsed(now)) return r;
                count[0]++;
                return copy(r, r.id(), McpWriteApprovalStatus.EXPIRED, null, null, null, null, null, null, null,
                        null);
            });
            return count[0];
        }

        @Override
        public SearchPage search(SearchQuery query, LocalDateTime now) {
            List<McpWriteApproval> items = rows.stream()
                    .filter(r -> query.state() == null || r.visibleState(now).name().equals(query.state()))
                    .sorted(Comparator.comparing(McpWriteApproval::id).reversed()).toList();
            return new SearchPage(items, items.size());
        }

        @Override
        public long countPending(LocalDateTime now) {
            return rows.stream().filter(r -> r.visibleState(now) == McpWriteApprovalStatus.PENDING).count();
        }

        private void replace(McpWriteApproval updated) {
            rows.replaceAll(r -> r.id().equals(updated.id()) ? updated : r);
        }

        private static McpWriteApproval copy(McpWriteApproval a, Long id, McpWriteApprovalStatus state,
                                             String decidedBy, LocalDateTime decidedAt, String comment,
                                             LocalDateTime executedAt, String resultJson, String errorCode,
                                             String runId, String traceId) {
            return new McpWriteApproval(id, a.tenantId(), a.connectionId(), a.businessUserId(), a.externalUserId(),
                    a.userName(), a.toolKind(), a.projectId(), a.projectCode(), a.systemName(), a.publicationId(),
                    a.revisionNo(), a.toolName(), a.toolDescription(), a.sourceKind(), a.sourceRef(),
                    a.workflowVersionId(), a.capabilityContractHash(), a.riskLevel(), a.argumentsJson(),
                    a.argumentsHash(), state, decidedBy, decidedAt, comment, executedAt, resultJson, errorCode,
                    runId, traceId, a.expiresAt(), a.createdAt());
        }
    }
}

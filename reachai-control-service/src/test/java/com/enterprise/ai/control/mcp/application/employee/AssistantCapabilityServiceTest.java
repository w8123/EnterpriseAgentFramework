package com.enterprise.ai.control.mcp.application.employee;

import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.identity.BusinessUserSyncService;
import com.enterprise.ai.control.identity.BusinessUserSyncService.BusinessSystemAccount;
import com.enterprise.ai.control.mcp.application.employee.AssistantCapabilityService.Caller;
import com.enterprise.ai.control.mcp.application.port.McpCallAuditRepository;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.enterprise.ai.control.mcp.application.port.McpRuntimeExecutionGateway;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantCapabilityServiceTest {

    private static final Caller EMPLOYEE = new Caller("default", "yudao-erp", "1");

    private final AiToolConnectionServiceTest.FakeConnections connections = new AiToolConnectionServiceTest.FakeConnections();
    private final AiToolConnectionServiceTest.FakeAccess access = new AiToolConnectionServiceTest.FakeAccess();
    private final McpPublicationRepository publications = mock(McpPublicationRepository.class);
    private final BusinessUserSyncService sync = mock(BusinessUserSyncService.class);
    private final ControlToolAclDecisionService acl = mock(ControlToolAclDecisionService.class);
    private final McpRuntimeExecutionGateway gateway = mock(McpRuntimeExecutionGateway.class);
    private final McpCallAuditRepository audit = mock(McpCallAuditRepository.class);
    private final McpWriteApprovalServiceTest.FakeApprovals approvals = new McpWriteApprovalServiceTest.FakeApprovals();
    private AssistantCapabilityService service;

    @BeforeEach
    void setUp() {
        when(sync.hasActiveOrigin(any(), any(), any(), any())).thenReturn(true);
        when(sync.activeBusinessUserId(eq("default"), eq("yudao-erp"), eq("1"))).thenReturn(Optional.of(7L));
        when(sync.activeAccount(eq("default"), eq(7L), eq("yudao-erp")))
                .thenReturn(Optional.of(new BusinessSystemAccount("yudao-erp", "1", "张三", List.of("erp_user"))));
        when(acl.decide(any(), any(), any(), any(), any())).thenReturn(ControlToolAclDecisionService.DECISION_ALLOW);
        access.save(new McpEmployeeAccess(null, 11L, null, "yudao-erp", "芋道 ERP", true, true));
        when(publications.findById(11L)).thenReturn(Optional.of(new McpPublication(
                11L, "pub11", "", McpPublicationStatus.PUBLISHED, 1100L, null, null)));
        when(publications.findRevision(eq(11L), eq(1100L))).thenReturn(Optional.of(new McpPublicationRevision(
                1100L, 11L, 1, List.of(
                        tool("erpStockPage", "分页查询产品库存", "READ"),
                        tool("erpSaleOrderPage", "分页查询销售订单", "READ"),
                        tool("erpCustomerCreate", "新建客户", "WRITE"),
                        tool("erpCustomerDelete", "删除客户", "IRREVERSIBLE")),
                null, LocalDateTime.now())));
        McpEmployeeProtocolService employees = new McpEmployeeProtocolService(connections, access, publications,
                sync, acl, gateway, audit, approvals, new ObjectMapper());
        service = new AssistantCapabilityService(employees, new ObjectMapper());
    }

    @Test
    void searchFindsCapabilitiesByChineseBusinessWordsAndNeverListsIrreversibleOnes() {
        var orders = service.search(EMPLOYEE, "销售订单 有几张", null);
        assertTrue(orders.available());
        assertEquals("erpSaleOrderPage", orders.tools().get(0).get("name"));
        assertEquals(false, orders.tools().get(0).get("requiresApproval"));

        var customers = service.search(EMPLOYEE, "客户", null);
        assertEquals(List.of("erpCustomerCreate"), customers.tools().stream().map(tool -> tool.get("name")).toList());
        assertEquals(true, customers.tools().get(0).get("requiresApproval"));
    }

    @Test
    void theAssistantsConnectionIsCreatedOnceAndIsNotOneTheEmployeeCanUseAsAKey() {
        service.search(EMPLOYEE, "库存", null);
        service.search(EMPLOYEE, "库存", null);

        assertEquals(1, connections.rows.size());
        var connection = connections.rows.values().iterator().next();
        assertEquals(AiToolKind.ASSISTANT, connection.toolKind());
        assertEquals(null, connection.expiresAt());
    }

    @Test
    void anAccountTheBusinessSystemNeverSyncedGetsNothing() {
        var stranger = new Caller("default", "yudao-erp", "999");

        assertFalse(service.search(stranger, "库存", null).available());
        var refused = service.call(stranger, "erpStockPage", Map.of());
        assertFalse(refused.success());
        assertEquals("ASSISTANT_CAPABILITIES_UNAVAILABLE", refused.errorCode());
        assertTrue(connections.rows.isEmpty());
        verify(gateway, never()).executeAsEmployee(any());
    }

    @Test
    void readRunsAsTheEmployeeAndIsAudited() {
        when(gateway.executeAsEmployee(any())).thenReturn(new McpRuntimeExecutionGateway.ExecutionOutcome(
                true, "OK", Map.of("total", 2), "run-1", "trace-1", null));

        var result = service.call(EMPLOYEE, "erpSaleOrderPage", Map.of("pageNo", 1));

        assertTrue(result.success());
        assertFalse(result.approvalPending());
        assertEquals(2, result.output().get("total"));
        assertEquals("run-1", result.runId());
        verify(audit, times(1)).append(any());
    }

    @Test
    void automaticEvaluationSeesAndRunsOnlyReadCapabilities() {
        var evaluation = new Caller("default", "yudao-erp", "1", true);

        assertEquals(List.of(), service.search(evaluation, "客户", null).tools());
        var catalog = service.readCatalog(evaluation, null);
        assertEquals(List.of("erpStockPage", "erpSaleOrderPage"),
                catalog.tools().stream().map(tool -> tool.get("name")).toList());
        var refused = service.call(evaluation, "erpCustomerCreate", Map.of("name", "评测客户"));

        assertFalse(refused.success());
        assertEquals("CAPABILITY_READ_ONLY", refused.errorCode());
        assertTrue(approvals.rows.isEmpty());
        verify(gateway, never()).executeAsEmployee(any());
    }

    @Test
    void writeWaitsForApprovalInsteadOfRunning() {
        var result = service.call(EMPLOYEE, "erpCustomerCreate", Map.of("name", "深圳走查科技有限公司"));

        assertTrue(result.success());
        assertTrue(result.approvalPending());
        assertEquals(1, approvals.rows.size());
        assertEquals("erpCustomerCreate", approvals.rows.get(0).toolName());
        verify(gateway, never()).executeAsEmployee(any());
    }

    @Test
    void aCallTheEmployeeHasNoPermissionForIsRefusedAndAudited() {
        when(acl.decide(any(), any(), any(), any(), eq("yudao-erp.erpStockPage"))).thenReturn("DENY");

        var result = service.call(EMPLOYEE, "erpStockPage", Map.of());

        assertFalse(result.success());
        assertEquals("MCP_TOOL_ACL_DENIED", result.errorCode());
        verify(gateway, never()).executeAsEmployee(any());
        verify(audit, times(1)).append(any());
    }

    @Test
    void irreversibleAndUnknownCapabilitiesCannotBeCalled() {
        assertEquals("MCP_TOOL_NOT_VISIBLE", service.call(EMPLOYEE, "erpCustomerDelete", Map.of()).errorCode());
        assertEquals("MCP_TOOL_NOT_VISIBLE", service.call(EMPLOYEE, "nope", Map.of()).errorCode());
        verify(gateway, never()).executeAsEmployee(any());
    }

    @Test
    void termsSplitLatinWordsAndChineseRuns() {
        assertEquals(java.util.Set.of("sale", "order", "销售订单", "销售", "售订", "订单"),
                AssistantCapabilityService.terms("sale order 销售订单"));
    }

    private static McpToolProjection tool(String name, String description, String risk) {
        return new McpToolProjection(name, description, "{\"type\":\"object\"}", McpPublicationItemKind.CAPABILITY,
                "yudao-erp." + name, null, risk, "c".repeat(64), risk, 1);
    }
}

package com.enterprise.ai.control.agentskill;

import com.enterprise.ai.control.agentskill.AgentSkillContracts.ImportCommand;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillDetail;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillSummary;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.VersionView;
import com.enterprise.ai.control.agentskill.AgentSkillRunCaptureService.CreateRequest;
import com.enterprise.ai.control.agentskill.AgentSkillRunCaptureService.Draft;
import com.enterprise.ai.control.agentskill.AgentSkillRunCaptureService.PreviewRequest;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.mcp.application.calllog.McpCallLogReader;
import com.enterprise.ai.control.mcp.application.port.McpEmployeeAccessRepository;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
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
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSkillRunCaptureServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 26, 9, 0);

    private McpCallLogReader callLogReader;
    private McpEmployeeAccessRepository accessRepository;
    private McpPublicationRepository publicationRepository;
    private RuntimeProxyClient runtimeClient;
    private AgentSkillCatalogService catalogService;
    private AgentSkillRunCaptureService service;

    @BeforeEach
    void setUp() {
        callLogReader = mock(McpCallLogReader.class);
        accessRepository = mock(McpEmployeeAccessRepository.class);
        publicationRepository = mock(McpPublicationRepository.class);
        runtimeClient = mock(RuntimeProxyClient.class);
        catalogService = mock(AgentSkillCatalogService.class);
        service = new AgentSkillRunCaptureService(callLogReader, accessRepository, publicationRepository,
                runtimeClient, catalogService, new ObjectMapper());

        when(accessRepository.findByProjectCode("yudao-erp")).thenReturn(Optional.of(
                new McpEmployeeAccess(1L, 1L, 1L, "yudao-erp", "芋道 ERP", true, true)));
        when(publicationRepository.findById(1L)).thenReturn(Optional.of(new McpPublication(1L,
                "yudao-erp-employee", null, McpPublicationStatus.PUBLISHED, 7L, T0, T0)));
        when(publicationRepository.findRevision(1L, 7L)).thenReturn(Optional.of(new McpPublicationRevision(7L, 1L, 3,
                List.of(
                        new McpToolProjection("yudao_erp_getCustomerPage", "分页查询客户。按名称筛选",
                                "{\"type\":\"object\",\"properties\":{\"pageReqVO\":{\"type\":\"object\"},"
                                        + "\"pageReqVO.name\":{\"type\":\"string\",\"description\":\"客户名称\"},"
                                        + "\"pageReqVO.pageNo\":{\"type\":\"integer\"}},\"required\":[\"pageReqVO.pageNo\"]}",
                                McpPublicationItemKind.CAPABILITY, "yudao-erp:getCustomerPage", null, "READ"),
                        new McpToolProjection("yudao_erp_createCustomer", "新建客户",
                                "{\"type\":\"object\",\"properties\":{\"createReqVO\":{\"type\":\"object\"},"
                                        + "\"createReqVO.name\":{\"type\":\"string\"},\"createReqVO.mobile\":{\"type\":\"string\"},"
                                        + "\"createReqVO.sort\":{\"type\":\"integer\"}},\"required\":[\"createReqVO.name\"]}",
                                McpPublicationItemKind.CAPABILITY, "yudao-erp:createCustomer", null, "WRITE")),
                "{}", T0)));
        when(catalogService.list(any(), any())).thenReturn(List.of());
    }

    @Test
    void employeeCallsBecomeSkillWithToolNamesAndNoRecordedValues() {
        when(callLogReader.detail(11L)).thenReturn(call(11L, "yudao_erp_getCustomerPage", true,
                "{\"params\":{\"name\":\"yudao_erp_getCustomerPage\",\"arguments\":{\"pageReqVO\":{\"name\":\"张三科技\",\"pageNo\":1}}}}", 0));
        when(callLogReader.detail(12L)).thenReturn(call(12L, "yudao_erp_createCustomer", true,
                "{\"params\":{\"arguments\":{\"createReqVO\":{\"name\":\"张三科技\",\"mobile\":\"13800000000\",\"sort\":0}}}}", 1));
        when(callLogReader.detail(13L)).thenReturn(call(13L, "reachai_approval_status", true,
                "{\"params\":{\"arguments\":{\"approvalId\":3}}}", 2));
        when(callLogReader.detail(14L)).thenReturn(call(14L, "yudao_erp_createCustomer", false, null, 3));

        Draft draft = service.preview(new PreviewRequest("MCP_CALLS", List.of(14L, 13L, 12L, 11L), null, null));

        assertEquals("yudao-erp", draft.projectCode());
        assertEquals("yudao-erp-create-customer", draft.name());
        assertEquals("1.0.0", draft.version());
        assertEquals(3, draft.steps().size());
        assertEquals("yudao_erp_getCustomerPage", draft.steps().get(0).toolName());
        assertTrue(draft.steps().get(1).requiresApproval());
        assertTrue(draft.skillMd().startsWith("---\nname: yudao-erp-create-customer\n"));
        assertTrue(draft.skillMd().contains("`yudao_erp_createCustomer`"));
        assertTrue(draft.skillMd().contains("`createReqVO.mobile`"));
        assertTrue(draft.skillMd().contains("`pageReqVO.pageNo`：整数，必填"));
        assertTrue(draft.skillMd().contains("reachai_approval_status"));
        assertFalse(draft.skillMd().contains("张三科技"), "recorded values never enter the package");
        assertFalse(draft.skillMd().contains("13800000000"));
        assertTrue(draft.warnings().stream().anyMatch(w -> w.contains("跳过 1 条失败")));
        assertEquals("mcp-calls:14,13,12,11", draft.sourceRef());
        assertTrue(draft.workflowInstruction().contains("yudao_erp_createCustomer"));
    }

    @Test
    void callsFromTwoSystemsAreRejected() {
        when(callLogReader.detail(1L)).thenReturn(call(1L, "yudao_erp_getCustomerPage", true, null, 0));
        McpCallLogReader.Entry other = new McpCallLogReader.Entry(2L, "OUTBOUND", 9L, null, "员工", "tools/call",
                "crm_list", 2L, "crm", null, "default", true, 5L, null, null, null, null, null, null, null,
                T0.plusMinutes(1), 5L, "u1", "CODEX");
        when(callLogReader.detail(2L)).thenReturn(other);

        assertThrows(AgentSkillException.class,
                () -> service.preview(new PreviewRequest("MCP_CALLS", List.of(1L, 2L), null, null)));
    }

    @Test
    void runOpsTraceMapsCapabilityRefsToEmployeeToolsAndWarnsOnUnknownTools() {
        when(runtimeClient.runOpsDetail("t-1")).thenReturn(ResponseEntity.ok(Map.of(
                "summary", Map.of("traceId", "t-1", "projectCode", "yudao-erp", "inputSummary", "帮我查一下客户"),
                "toolCalls", List.of(
                        Map.of("toolName", "getCustomerPage", "success", true,
                                "argsJson", "{\"pageReqVO\":{\"name\":\"x\"}}"),
                        Map.of("toolName", "erp_secret_tool", "success", true)))));

        Draft draft = service.preview(new PreviewRequest("RUN", null, "t-1", "查询客户"));

        assertEquals("查询客户", draft.displayName());
        assertEquals("yudao-erp-get-customer-page", draft.name(), "an unavailable tool never names the Skill");
        Draft untitled = service.preview(new PreviewRequest("RUN", null, "t-1", null));
        assertEquals("在芋道 ERP 中分页查询客户", untitled.displayName());
        assertEquals("yudao_erp_getCustomerPage", draft.steps().get(0).toolName());
        assertFalse(draft.steps().get(1).available());
        assertTrue(draft.warnings().stream().anyMatch(w -> w.contains("erp_secret_tool")));
        assertTrue(draft.skillMd().contains("用户当时的说法：帮我查一下客户"));
        assertEquals("trace:t-1", draft.sourceRef());
    }

    @Test
    void createPacksValidStandardSkillIntoReviewFlow() {
        when(callLogReader.detail(12L)).thenReturn(call(12L, "yudao_erp_createCustomer", true, null, 0));
        Draft draft = service.preview(new PreviewRequest("MCP_CALLS", List.of(12L), null, null));
        AgentSkillPackageInspector inspector = new AgentSkillPackageInspector(1024 * 1024, 2 * 1024 * 1024,
                1024 * 1024, 100);
        ArgumentCaptor<byte[]> archive = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<ImportCommand> command = ArgumentCaptor.forClass(ImportCommand.class);
        when(catalogService.importPackage(archive.capture(), command.capture())).thenReturn(null);

        service.create(new CreateRequest("yudao-erp", draft.displayName(), draft.skillMd(), null, null,
                draft.sourceRef()), "ops01", 5L);

        AgentSkillPackageInspector.PackageInspection inspected = inspector.inspect(archive.getValue());
        assertEquals("yudao-erp-create-customer", inspected.name());
        assertEquals("yudao-erp", command.getValue().publisher());
        assertEquals("PROJECT", command.getValue().visibility());
        assertEquals("yudao-erp", command.getValue().projectCode());
        assertEquals("RUN_CAPTURE", command.getValue().sourceType());
        assertEquals("1.0.0", command.getValue().version());
    }

    @Test
    void nextVersionBumpsPatchOfExistingProjectSkill() {
        SkillSummary existing = new SkillSummary(4L, "yudao-erp", "yudao-erp-create-customer", "建客户", "d",
                "PROJECT", null, "yudao-erp", "ACTIVE", 9L, 9L, 2, T0, "1.0.1", "PUBLISHED", "1.0.1");
        when(catalogService.list(eq("yudao-erp-create-customer"), any())).thenReturn(List.of(existing));
        when(catalogService.detail(4L)).thenReturn(new SkillDetail(existing, List.of(version("1.0.0"), version("1.0.1"))));

        assertEquals("1.0.2", service.nextVersion("yudao-erp", "yudao-erp-create-customer"));
    }

    @Test
    void createRejectsInvalidName() {
        assertThrows(AgentSkillException.class, () -> service.create(new CreateRequest("yudao-erp", null,
                "---\nname: Bad_Name\ndescription: x\n---\n", null, null, null), "ops01", 5L));
        verify(catalogService, org.mockito.Mockito.never()).importPackage(any(), any());
    }

    @Test
    void standardNameStripsSystemPrefix() {
        assertEquals("yudao-erp-get-stock", AgentSkillRunCaptureService.standardName("yudao-erp", "yudao_erp_getStock"));
        assertEquals("crm-list", AgentSkillRunCaptureService.standardName("crm", "list"));
        assertEquals("community", AgentSkillRunCaptureService.publisher("ReachAI"));
    }

    private static McpCallLogReader.Entry call(long id, String tool, boolean success, String body, int minute) {
        return new McpCallLogReader.Entry(id, "OUTBOUND", 1L, null, "员工", "tools/call", tool, 1L, "yudao-erp",
                null, "default", success, 5L, null, body, null, null, null, null, null,
                T0.plusMinutes(minute), 5L, "1", "CLAUDE_CODE");
    }

    private static VersionView version(String version) {
        return new VersionView(1L, 4L, version, "PUBLISHED", "RUN_CAPTURE", null, "a", "b", 1L, null, null, false,
                null, null, null, null, null, null, null, null, null, T0, T0);
    }
}

package com.enterprise.ai.control.mcp.application.usage;

import com.enterprise.ai.control.mcp.application.port.McpEmployeeAccessRepository;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.enterprise.ai.control.mcp.application.usage.AiUsageReader.AccountRow;
import com.enterprise.ai.control.mcp.application.usage.AiUsageReader.ApprovalRow;
import com.enterprise.ai.control.mcp.application.usage.AiUsageReader.CallRow;
import com.enterprise.ai.control.mcp.application.usage.AiUsageReader.ConnectionRow;
import com.enterprise.ai.control.mcp.application.usage.AiUsageReader.SkillRow;
import com.enterprise.ai.control.mcp.application.usage.AiUsageService.Person;
import com.enterprise.ai.control.mcp.application.usage.AiUsageService.Report;
import com.enterprise.ai.control.mcp.application.usage.EmbedUsageSource.EmbedUserRow;
import com.enterprise.ai.control.mcp.domain.employee.McpEmployeeAccess;
import com.enterprise.ai.control.mcp.domain.publication.McpPublication;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationRevision;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationStatus;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiUsageServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 27, 9, 0);

    private AiUsageReader reader;
    private EmbedUsageSource embedSource;
    private AiUsageService service;

    @BeforeEach
    void setUp() {
        reader = mock(AiUsageReader.class);
        embedSource = mock(EmbedUsageSource.class);
        McpEmployeeAccessRepository accessRepository = mock(McpEmployeeAccessRepository.class);
        McpPublicationRepository publicationRepository = mock(McpPublicationRepository.class);
        service = new AiUsageService(reader, embedSource, accessRepository, publicationRepository);

        when(accessRepository.findEnabled()).thenReturn(List.of(
                new McpEmployeeAccess(1L, 1L, 1L, "yudao-erp", "芋道 ERP", true, true)));
        when(publicationRepository.findById(1L)).thenReturn(Optional.of(new McpPublication(1L,
                "yudao-erp-employee", null, McpPublicationStatus.PUBLISHED, 7L, T0, T0)));
        when(publicationRepository.findRevision(1L, 7L)).thenReturn(Optional.of(new McpPublicationRevision(7L, 1L, 3,
                List.of(new McpToolProjection("yudao_erp_getStockPage", "分页查询库存。可按产品筛选", "{}",
                                McpPublicationItemKind.CAPABILITY, "yudao-erp:getStockPage", null, "READ"),
                        new McpToolProjection("yudao_erp_createCustomer", "新建客户", "{}",
                                McpPublicationItemKind.CAPABILITY, "yudao-erp:createCustomer", null, "WRITE")),
                "{}", T0)));

        // 张三 (business user 11): Claude Code connection 7 and Codex connection 8, also chats in the ERP.
        when(reader.employeeCalls(any(), eq(null))).thenReturn(List.of(
                new CallRow(7L, "yudao-erp", "yudao_erp_getStockPage", 6, 1, T0.minusHours(1)),
                new CallRow(7L, "yudao-erp", "yudao_erp_createCustomer", 2, 0, T0.minusHours(2)),
                new CallRow(8L, "yudao-erp", "yudao_erp_getStockPage", 3, 0, T0.minusHours(5)),
                new CallRow(9L, "yudao-erp", "yudao_erp_getStockPage", 1, 0, T0.minusDays(2))));
        when(reader.connections(any())).thenReturn(List.of(
                new ConnectionRow(7L, 11L, "yudao-erp", "101", "张三", "CLAUDE_CODE"),
                new ConnectionRow(8L, 11L, "yudao-erp", "101", "张三", "CODEX"),
                new ConnectionRow(9L, 12L, "yudao-erp", "102", "李四", "CLAUDE_CODE")));
        when(reader.approvals(any(), eq(null))).thenReturn(List.of(
                new ApprovalRow(7L, 11L, "yudao-erp", "101", "张三", "CLAUDE_CODE", "yudao-erp", "芋道 ERP",
                        "yudao_erp_createCustomer", "新建客户", "SUCCEEDED", 1, T0.minusHours(2)),
                new ApprovalRow(7L, 11L, "yudao-erp", "101", "张三", "CLAUDE_CODE", "yudao-erp", "芋道 ERP",
                        "yudao_erp_createCustomer", "新建客户", "REJECTED", 1, T0.minusHours(3)),
                new ApprovalRow(7L, 11L, "yudao-erp", "101", "张三", "CLAUDE_CODE", "yudao-erp", "芋道 ERP",
                        "yudao_erp_createCustomer", "新建客户", "EXECUTING", 1, T0.minusHours(3))));
        when(embedSource.embedUsers(null, 30)).thenReturn(List.of(
                new EmbedUserRow("yudao-erp", "yudao-erp", "101", 10, 3, 1, T0),
                new EmbedUserRow("yudao-erp", "yudao-erp", "103", 4, 1, 0, T0.minusDays(1))));
        when(reader.accounts(any())).thenReturn(List.of(new AccountRow("yudao-erp", "101", 11L, "张三")));
        when(reader.businessUserNames(any())).thenReturn(Map.of(11L, "张三（销售）"));
        when(reader.activeConnections(null)).thenReturn(3L);
        when(reader.capturedSkillCount(any(), eq(null))).thenReturn(1L);
        when(reader.capturedSkills(any(), eq(null), anyInt())).thenReturn(List.of(
                new SkillRow(4L, "yudao-erp-erp-customer-create", "在芋道 ERP 中新建客户", "1.0.0", "PUBLISHED",
                        "yudao-erp", "admin", T0)));
    }

    @Test
    void mergesOnePersonsEmbeddedChatsAndBothAiToolsAndCountsTheirApprovals() {
        Report report = service.report(30, null, true);

        assertEquals(3, report.summary().activePeople());
        assertEquals(12, report.summary().toolCalls());
        assertEquals(1, report.summary().toolFailedCalls());
        assertEquals(14, report.summary().embedRuns());
        assertEquals(4, report.summary().embedSessions());
        assertEquals(3, report.summary().approvals().submitted());
        assertEquals(1, report.summary().approvals().succeeded());
        assertEquals(1, report.summary().approvals().rejected());
        assertEquals(1, report.summary().approvals().pending());
        assertTrue(report.embedIncluded());
        assertNull(report.embedNotice());

        Person zhang = report.people().get(0);
        assertEquals("张三（销售）", zhang.name());
        assertEquals(List.of("EMBED", "CLAUDE_CODE", "CODEX"), zhang.channels());
        assertEquals(10, zhang.embedRuns());
        assertEquals(11, zhang.toolCalls());
        assertEquals(List.of(7L, 8L), zhang.connectionIds());
        assertEquals(List.of("芋道 ERP"), zhang.systems());
        assertEquals("分页查询库存", zhang.topOperations().get(0).label());
        assertEquals(9, zhang.topOperations().get(0).calls());
        assertEquals(3, zhang.topOperations().get(1).writes());

        Person unbound = report.people().stream().filter(p -> p.key().equals("a:yudao-erp:103")).findFirst().orElseThrow();
        assertEquals("yudao-erp · 103", unbound.name());
        assertEquals(List.of("EMBED"), unbound.channels());
    }

    @Test
    void reportsUsePerAiToolAndTheMostCommonOperations() {
        Report report = service.report(30, null, true);

        var byChannel = report.channels();
        assertEquals(List.of("EMBED", "CLAUDE_CODE", "CODEX", "OTHER"),
                byChannel.stream().map(AiUsageService.Channel::channel).toList());
        assertEquals(2, byChannel.get(0).people());
        assertEquals(14, byChannel.get(0).uses());
        assertEquals(2, byChannel.get(1).people());
        assertEquals(9, byChannel.get(1).uses());
        assertEquals(3, byChannel.get(1).approvals().submitted());
        assertEquals(1, byChannel.get(2).people());
        assertEquals(0, byChannel.get(3).people());

        var top = report.operations().get(0);
        assertEquals("分页查询库存", top.label());
        assertEquals("芋道 ERP", top.systemName());
        assertEquals(10, top.calls());
        assertEquals(2, top.people());
        assertEquals("在芋道 ERP 中新建客户", report.skills().get(0).displayName());
        assertEquals("芋道 ERP", report.skills().get(0).systemName());
        assertEquals(List.of("yudao-erp"), report.systems().stream().map(AiUsageService.SystemOption::projectCode).toList());
    }

    @Test
    void leavesTheEmbeddedChatOutWithoutRunRecordPermission() {
        Report report = service.report(7, null, false);

        verify(embedSource, never()).embedUsers(any(), anyInt());
        assertFalse(report.embedIncluded());
        assertTrue(report.embedNotice().contains("没有查看运行记录的权限"));
        assertEquals(0, report.summary().embedRuns());
        assertEquals(2, report.summary().activePeople());
    }

    @Test
    void stillReportsAiToolsWhenRuntimeIsUnavailable() {
        when(embedSource.embedUsers(null, 30)).thenThrow(new IllegalStateException("connection refused"));

        Report report = service.report(null, " ", true);

        assertEquals(30, report.days());
        assertNull(report.projectCode());
        assertFalse(report.embedIncluded());
        assertTrue(report.embedNotice().contains("暂时读不到"));
        assertEquals(12, report.summary().toolCalls());
    }

    @Test
    void clampsThePeriodAndShortensLongToolDescriptions() {
        when(embedSource.embedUsers(null, 90)).thenReturn(List.of());
        assertEquals(90, service.report(365, null, true).days());
        assertEquals("分页查询库存", AiUsageService.firstSentence("分页查询库存。可按产品、仓库筛选"));
        assertEquals("OTHER", AiUsageService.channel("EMBED"));
        assertEquals("CODEX", AiUsageService.channel("codex"));
    }
}

package com.enterprise.ai.control.mcp.application.usage;

import com.enterprise.ai.control.mcp.application.usage.AiAuditSource.Approval;
import com.enterprise.ai.control.mcp.application.usage.AiAuditSource.Login;
import com.enterprise.ai.control.mcp.application.usage.AiAuditSource.Run;
import com.enterprise.ai.control.mcp.application.usage.AiAuditSource.ToolCall;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAuditExportServiceTest {

    private final AiAuditSource source = mock(AiAuditSource.class);
    private final AiUsageReader reader = mock(AiUsageReader.class);
    private final AiAuditExportService service = new AiAuditExportService(source, reader);
    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 8, 10, 0);

    @Test
    void exportsEveryKindNewestFirstWithBomAndChineseLabels() {
        when(source.toolCalls(any(), any(), anyInt())).thenReturn(List.of(new ToolCall(T.minusHours(1), "yudao-erp",
                "yudao_erp_stock_page", true, null, "run-1", "yudao-erp", "7", "张三", "CLAUDE_CODE")));
        when(source.approvals(any(), any(), anyInt())).thenReturn(List.of(new Approval(T, "yudao-erp", "芋道 ERP",
                "yudao_erp_customer_create", "新建客户", "SUCCEEDED", "manager01", T.plusMinutes(5), null, "run-2",
                "yudao-erp", "7", "张三", "CODEX")));
        when(source.runs(any(), anyInt(), anyInt())).thenReturn(List.of(new Run(T.minusHours(2), "trace-3", "EMBED",
                "COMPLETED", "yudao-erp", "yudao-erp", "7", "ERP 助手", null, null, 1200)));
        when(source.logins(any(), anyInt())).thenReturn(List.of(new Login(T.minusHours(3), "PLATFORM_LOGIN_SUCCEEDED",
                "admin", "管理员", "{}")));

        AiAuditExportService.Export export = service.export(30, null, true, true);
        String csv = new String(export.csv(), StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");

        assertTrue(csv.startsWith("﻿\"时间\""));
        assertEquals(4, export.rows());
        assertTrue(lines[1].contains("写操作审批") && lines[1].contains("新建客户（yudao_erp_customer_create）")
                && lines[1].contains("已批准，执行成功") && lines[1].contains("manager01"));
        assertTrue(lines[2].contains("AI 工具调用") && lines[2].contains("Claude Code"));
        assertTrue(lines[3].contains("业务系统内对话") && lines[3].contains("ERP 助手") && lines[3].contains("1200"));
        assertTrue(lines[4].contains("登录管理端"));
        assertFalse(export.truncated());
    }

    @Test
    void withoutRunPermissionRunsAreSkippedAndLoginsNeedPlatformScope() {
        when(source.toolCalls(any(), any(), anyInt())).thenReturn(List.of());
        when(source.approvals(any(), any(), anyInt())).thenReturn(List.of());

        AiAuditExportService.Export export = service.export(7, "yudao-erp", false, true);

        verify(source, never()).runs(any(), anyInt(), anyInt());
        verify(source, never()).logins(any(), anyInt());
        assertEquals(1, export.notices().size());
    }

    @Test
    void cellsAreQuotedAndFormulasDefused() {
        assertEquals("\"'=HYPERLINK(1)\"", AiAuditExportService.cell("=HYPERLINK(1)"));
        assertEquals("\"a \"\"b\"\" c\"", AiAuditExportService.cell("a \"b\"\nc"));
        assertEquals("\"\"", AiAuditExportService.cell(null));
    }
}

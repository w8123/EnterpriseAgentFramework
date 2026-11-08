package com.enterprise.ai.control.mcp.application.usage;

import com.enterprise.ai.control.mcp.application.usage.AiAuditSource.Approval;
import com.enterprise.ai.control.mcp.application.usage.AiAuditSource.Login;
import com.enterprise.ai.control.mcp.application.usage.AiAuditSource.Run;
import com.enterprise.ai.control.mcp.application.usage.AiAuditSource.ToolCall;
import com.enterprise.ai.control.mcp.application.usage.AiUsageReader.AccountKey;
import com.enterprise.ai.control.mcp.application.usage.AiUsageReader.AccountRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「导出审计记录」: one CSV row per thing that happened — an employee AI-tool call, a write approval, a business run
 * (embedded chat, automation) or a login. No request, answer or argument content is exported, and cells that start
 * like a spreadsheet formula are neutralised.
 */
@Service
public class AiAuditExportService {

    private static final Logger log = LoggerFactory.getLogger(AiAuditExportService.class);
    public static final int MAX_ROWS = 50_000;
    static final List<String> HEADER = List.of("时间", "类别", "人员", "业务系统账号", "入口", "业务系统", "做了什么",
            "结果", "审批人", "审批时间", "错误", "Token", "运行编号");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Map<String, String> TOOL_LABELS = Map.of(
            "CLAUDE_CODE", "Claude Code", "CODEX", "Codex", "OTHER", "其他 AI 工具");
    private static final Map<String, String> ENTRY_LABELS = Map.of(
            "EMBED", "业务系统内对话", "AUTOMATION", "自动化任务", "API", "开放 API", "GATEWAY", "开放 API",
            "A2A", "智能体互联", "AI_CODING_TASK", "AI 编程任务");
    private static final Map<String, String> APPROVAL_STATES = Map.of(
            "PENDING", "待审批", "EXECUTING", "已批准，执行中", "SUCCEEDED", "已批准，执行成功",
            "FAILED", "已批准，执行失败", "REJECTED", "已拒绝", "EXPIRED", "已过期");
    private static final Map<String, String> RUN_STATES = Map.of(
            "COMPLETED", "成功", "FAILED", "失败", "TIMED_OUT", "超时", "CANCELLED", "已取消",
            "RUNNING", "进行中", "SUSPENDED", "等待中");
    private static final Map<String, String> LOGIN_EVENTS = Map.of(
            "PLATFORM_LOGIN_SUCCEEDED", "登录管理端",
            "PLATFORM_SSO_LOGIN_SUCCEEDED", "用业务系统账号登录管理端",
            "EMPLOYEE_SSO_LOGIN_SUCCEEDED", "用业务系统账号登录个人页",
            "BUSINESS_SSO_LOGIN_FAILED", "用业务系统账号登录失败");

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final AiAuditSource source;
    private final AiUsageReader reader;

    public AiAuditExportService(AiAuditSource source, AiUsageReader reader) {
        this.source = source;
        this.reader = reader;
    }

    /**
     * @param includeRuns    the viewer may read run records (embedded chat, automation)
     * @param includeLogins  the viewer manages the whole platform (logins are not tied to one system)
     */
    public Export export(Integer days, String projectCode, boolean includeRuns, boolean includeLogins) {
        int safeDays = days == null || days <= 0 ? 30 : Math.min(days, 90);
        String project = StringUtils.hasText(projectCode) ? projectCode.trim() : null;
        LocalDateTime since = LocalDateTime.now().minusDays(safeDays);
        int budget = MAX_ROWS + 1;
        List<Line> lines = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (ToolCall call : source.toolCalls(since, project, budget)) {
            lines.add(new Line(call.at(), "AI 工具调用", person(call.userName(), call.externalUserId()),
                    account(call.appId(), call.externalUserId()), TOOL_LABELS.getOrDefault(call.toolKind(), "AI 工具"),
                    call.projectCode(), call.toolName(), call.success() ? "成功" : "失败", null, null,
                    call.success() ? null : call.errorCategory(), null, call.runId()));
        }
        for (Approval approval : source.approvals(since, project, budget)) {
            String what = StringUtils.hasText(approval.toolDescription())
                    ? approval.toolDescription() + "（" + approval.toolName() + "）" : approval.toolName();
            lines.add(new Line(approval.at(), "写操作审批", person(approval.userName(), approval.externalUserId()),
                    account(approval.appId(), approval.externalUserId()),
                    TOOL_LABELS.getOrDefault(approval.toolKind(), "AI 工具"),
                    StringUtils.hasText(approval.systemName()) ? approval.systemName() : approval.projectCode(), what,
                    APPROVAL_STATES.getOrDefault(approval.state(), approval.state()), approval.decidedBy(),
                    approval.decidedAt(), approval.errorCode(), null, approval.runId()));
        }
        if (includeRuns) {
            try {
                List<Run> runs = source.runs(project, safeDays, budget);
                Map<AccountKey, String> names = names(runs);
                for (Run run : runs) {
                    String app = StringUtils.hasText(run.appId()) ? run.appId() : run.projectCode();
                    String name = run.externalUserId() == null ? null
                            : names.get(new AccountKey(app, run.externalUserId()));
                    String what = firstText(run.workflowName(), run.agentName(), "-");
                    lines.add(new Line(run.at(), ENTRY_LABELS.getOrDefault(run.entryType(), run.entryType()),
                            run.externalUserId() == null ? "-" : person(name, run.externalUserId()),
                            account(app, run.externalUserId()), ENTRY_LABELS.getOrDefault(run.entryType(), run.entryType()),
                            run.projectCode(), what, RUN_STATES.getOrDefault(run.status(), run.status()), null, null,
                            run.errorCode(), run.tokens() > 0 ? String.valueOf(run.tokens()) : null, run.traceId()));
                }
            } catch (RuntimeException failure) {
                log.warn("[AiAudit] runs unavailable: {}", failure.getMessage());
                skipped.add("暂时读不到业务系统内对话和自动化的运行记录，本次导出不含这部分。");
            }
        } else {
            skipped.add("你没有查看运行记录的权限，导出不含业务系统内对话和自动化。");
        }
        if (includeLogins && project == null) {
            for (Login login : source.logins(since, budget)) {
                Map<String, String> details = loginDetails(login.detailsJson());
                String label = LOGIN_EVENTS.getOrDefault(login.eventType(), login.eventType());
                String ip = details.get("ip");
                lines.add(new Line(login.at(), "登录",
                        firstText(login.displayName(), details.get("userName"), login.username(), "-"),
                        firstText(details.get("externalUserId"), login.username(), null),
                        label, details.get("projectCode"),
                        ip == null ? label : label + "（IP " + ip + "）",
                        login.eventType() != null && login.eventType().endsWith("FAILED") ? "失败" : "成功",
                        null, null, details.get("reason"), null, null));
            }
        }
        lines.sort(Comparator.comparing(Line::at, Comparator.nullsLast(Comparator.reverseOrder())));
        boolean truncated = lines.size() > MAX_ROWS;
        List<Line> kept = truncated ? lines.subList(0, MAX_ROWS) : lines;
        return new Export(csv(kept), kept.size(), truncated, safeDays, project, skipped);
    }

    /** Login audit details are flat metadata written by Control itself (never credentials). */
    static Map<String, String> loginDetails(String json) {
        Map<String, String> details = new HashMap<>();
        if (!StringUtils.hasText(json)) return details;
        try {
            com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(json);
            node.fields().forEachRemaining(field -> {
                if (field.getValue().isValueNode() && !field.getValue().isNull()) {
                    details.put(field.getKey(), field.getValue().asText());
                }
            });
        } catch (Exception malformed) {
            // Older or foreign rows: export them without details.
        }
        return details;
    }

    private Map<AccountKey, String> names(List<Run> runs) {
        Set<AccountKey> keys = new LinkedHashSet<>();
        for (Run run : runs) {
            String app = StringUtils.hasText(run.appId()) ? run.appId() : run.projectCode();
            if (StringUtils.hasText(app) && StringUtils.hasText(run.externalUserId())) {
                keys.add(new AccountKey(app, run.externalUserId()));
            }
        }
        Map<AccountKey, String> names = new HashMap<>();
        if (keys.isEmpty()) return names;
        List<AccountKey> list = new ArrayList<>(keys);
        for (int start = 0; start < list.size(); start += 500) {
            for (AccountRow row : reader.accounts(list.subList(start, Math.min(list.size(), start + 500)))) {
                if (StringUtils.hasText(row.name())) names.put(new AccountKey(row.appId(), row.externalUserId()), row.name());
            }
        }
        return names;
    }

    static byte[] csv(List<Line> lines) {
        StringBuilder out = new StringBuilder("﻿");
        appendRow(out, HEADER);
        for (Line line : lines) {
            appendRow(out, List.of(time(line.at()), text(line.category()), text(line.person()), text(line.account()),
                    text(line.entry()), text(line.system()), text(line.what()), text(line.result()),
                    text(line.decidedBy()), time(line.decidedAt()), text(line.error()), text(line.tokens()),
                    text(line.runId())));
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendRow(StringBuilder out, List<String> cells) {
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) out.append(',');
            out.append(cell(cells.get(index)));
        }
        out.append("\r\n");
    }

    /** Quotes every cell and defuses values a spreadsheet would run as a formula. */
    static String cell(String value) {
        String safe = value == null ? "" : value.replace("\r", " ").replace("\n", " ");
        if (!safe.isEmpty() && "=+-@\t".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static String person(String name, String externalUserId) {
        return firstText(name, externalUserId == null ? null : "账号 " + externalUserId, "-");
    }

    private static String account(String appId, String externalUserId) {
        if (!StringUtils.hasText(externalUserId)) return null;
        return StringUtils.hasText(appId) ? appId + " · " + externalUserId : externalUserId;
    }

    private static String time(LocalDateTime value) {
        return value == null ? "" : TIME.format(value);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }

    record Line(LocalDateTime at, String category, String person, String account, String entry, String system,
                String what, String result, String decidedBy, LocalDateTime decidedAt, String error, String tokens,
                String runId) {
    }

    public record Export(byte[] csv, int rows, boolean truncated, int days, String projectCode, List<String> notices) {
    }
}

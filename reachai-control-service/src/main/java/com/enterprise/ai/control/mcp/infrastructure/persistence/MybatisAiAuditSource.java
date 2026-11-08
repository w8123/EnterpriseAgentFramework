package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.mcp.application.usage.AiAuditSource;
import com.enterprise.ai.control.mcp.infrastructure.runtime.RuntimeEmbedUsageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
public class MybatisAiAuditSource implements AiAuditSource {

    static final List<String> LOGIN_EVENTS = List.of("PLATFORM_LOGIN_SUCCEEDED", "PLATFORM_SSO_LOGIN_SUCCEEDED",
            "EMPLOYEE_SSO_LOGIN_SUCCEEDED", "BUSINESS_SSO_LOGIN_FAILED");

    private final AiAuditMapper mapper;
    private final RuntimeProxyClient runtimeClient;
    private final com.enterprise.ai.control.mcp.infrastructure.runtime.RuntimeUsageRequestSigner usageSigner;

    public MybatisAiAuditSource(AiAuditMapper mapper, RuntimeProxyClient runtimeClient,
                                com.enterprise.ai.control.mcp.infrastructure.runtime.RuntimeUsageRequestSigner usageSigner) {
        this.usageSigner = usageSigner;
        this.mapper = mapper;
        this.runtimeClient = runtimeClient;
    }

    @Override
    public List<ToolCall> toolCalls(LocalDateTime since, String projectCode, int limit) {
        return mapper.selectToolCalls(since, projectCode, limit).stream()
                .map(row -> new ToolCall(row.getCreatedAt(), row.getProjectCode(), row.getToolName(),
                        Boolean.TRUE.equals(row.getSuccess()), row.getErrorCategory(), row.getRunId(), row.getAppId(),
                        row.getExternalUserId(), row.getUserName(),
                        row.getToolKind() != null ? row.getToolKind() : row.getToolClientName()))
                .toList();
    }

    @Override
    public List<Approval> approvals(LocalDateTime since, String projectCode, int limit) {
        return mapper.selectApprovals(since, projectCode, limit).stream()
                .map(row -> new Approval(row.getCreatedAt(), row.getProjectCode(), row.getSystemName(),
                        row.getToolName(), row.getToolDescription(), row.getState(), row.getDecidedBy(),
                        row.getDecidedAt(), row.getErrorCode(), row.getRunId(), row.getAppId(),
                        row.getExternalUserId(), row.getUserName(), row.getToolKind()))
                .toList();
    }

    @Override
    public List<Run> runs(String projectCode, int days, int limit) {
        ResponseEntity<List<Map<String, Object>>> response = runtimeClient.runOpsAuditRuns(
                usageSigner.headers("/internal/runtime/usage/audit-runs"), projectCode, days, limit);
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("runtime audit runs returned "
                    + (response == null ? "nothing" : response.getStatusCode()));
        }
        List<Map<String, Object>> body = response.getBody();
        if (body == null) return List.of();
        return body.stream().map(row -> new Run(RuntimeEmbedUsageSource.time(row.get("startedAt")),
                text(row.get("traceId")), text(row.get("entryType")), text(row.get("status")),
                text(row.get("projectCode")), text(row.get("appId")), text(row.get("externalUserId")),
                text(row.get("agentName")), text(row.get("workflowName")), text(row.get("errorCode")),
                row.get("tokenCost") instanceof Number number ? number.longValue() : 0L)).toList();
    }

    @Override
    public List<Login> logins(LocalDateTime since, int limit) {
        return mapper.selectLogins(since, LOGIN_EVENTS, limit).stream()
                .map(row -> new Login(row.getCreatedAt(), row.getEventType(), row.getUsername(), row.getDisplayName(),
                        row.getDetailsJson()))
                .toList();
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }
}

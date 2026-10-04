package com.enterprise.ai.runtime.runops.consolehttpapi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.runtime.runops.RuntimeRunEntity;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.runops.consolecapability.ConsoleCapabilityInvocationEntity;
import com.enterprise.ai.runtime.runops.consolecapability.ConsoleCapabilityInvocationMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Read-only current-selection proof. Does not store a second result or inspect Capability tables. */
@Service
@RequiredArgsConstructor
public class RuntimeHttpApiVerificationService {
    private final ConsoleCapabilityInvocationMapper attempts;
    private final RuntimeRunMapper runs;
    private final ObjectMapper json;

    public static boolean isMarket(String qualifiedName) {
        return qualifiedName != null && qualifiedName.matches("http-api:[^:]+:[^:]+:market:[0-9a-f]{64}");
    }

    public HttpApiConsoleContracts.MarketVerificationView read(HttpApiConsoleContracts.ExecutionContext owner,
            RuntimeHttpApiConnectionEntity connection, String credentialRevision, String configurationReason) {
        if (!isMarket(owner.qualifiedName())) return null;
        if (configurationReason != null || connection == null) {
            return unavailable("BLOCKED", configurationReason == null ? "请先显式配置当前 API 连接" : configurationReason);
        }
        var attempt = attempts.selectOne(Wrappers.<ConsoleCapabilityInvocationEntity>lambdaQuery()
                .eq(ConsoleCapabilityInvocationEntity::getTargetType, "HTTP_API")
                .eq(ConsoleCapabilityInvocationEntity::getProjectId, owner.projectId())
                .eq(ConsoleCapabilityInvocationEntity::getProjectCode, owner.projectCode())
                .eq(ConsoleCapabilityInvocationEntity::getEnvironment, owner.environment())
                .eq(ConsoleCapabilityInvocationEntity::getQualifiedName, owner.qualifiedName())
                .orderByDesc(ConsoleCapabilityInvocationEntity::getId).last("LIMIT 1"));
        if (attempt == null) return unavailable("UNVERIFIED", "已配置不等于已验证；请先完成当前 API 的真实 Console GET");
        RuntimeRunEntity run = attempt.getRunId() == null ? null : runs.selectById(attempt.getRunId());
        JsonNode snapshot = snapshot(run);
        Long recordedApiId = snapshot.path("apiId").canConvertToLong() ? snapshot.path("apiId").longValue() : null;
        boolean current = Objects.equals(owner.apiId(), recordedApiId)
                && Objects.equals(owner.acceptedContractHash(), attempt.getExpectedContractHash())
                && Objects.equals(owner.sourceSetRevision(), attempt.getSourceSetRevision())
                && Objects.equals(connection.getRevision(), attempt.getConnectionRevision())
                && Objects.equals(credentialRevision, attempt.getCredentialRevision())
                && Objects.equals(owner.qualifiedName(), snapshot.path("qualifiedName").asText(null))
                && Objects.equals(attempt.getExpectedContractHash(), snapshot.path("contractHash").asText(null))
                && Objects.equals(attempt.getSourceSetRevision(), snapshot.path("sourceSetRevision").asText(null))
                && Objects.equals(attempt.getConnectionRevision(), snapshot.path("connectionRevision").canConvertToLong()
                    ? snapshot.path("connectionRevision").longValue() : null)
                && Objects.equals(credentialRevision, snapshot.path("credentialRevision").asText(null));
        String status;
        String reason;
        if (!current) {
            status = "STALE"; reason = "来源选择、契约、连接或凭据修订已变化，请重新进行真实 Console 验证";
        } else if (run == null || !"CONSOLE_HTTP_API".equals(run.getRunType()) || !"HTTP_API".equals(run.getRuntimeType())
                || !Objects.equals(run.getProjectId(), owner.projectId())
                || !Objects.equals(run.getProjectCode(), owner.projectCode())
                || !Objects.equals(run.getTraceId(), attempt.getTraceId())) {
            status = "UNKNOWN"; reason = "Console 调用的 Run/Trace 事实不完整，不能确认当前验证";
        } else if ("SUCCEEDED".equals(attempt.getStatus()) && "CONFIRMED".equals(attempt.getDispatchStage())
                && attempt.getHttpStatus() != null && attempt.getHttpStatus() >= 200 && attempt.getHttpStatus() < 300
                && attempt.getDispatchedAt() != null && attempt.getEndedAt() != null
                && "COMPLETED".equals(run.getStatus()) && run.getEndedAt() != null) {
            status = "VERIFIED"; reason = "当前来源、契约及连接已通过真实 Console GET；发布仍需校验项目权限和 pin";
        } else if ("HTTP_FAILED".equals(attempt.getStatus()) || "NOT_DISPATCHED".equals(attempt.getStatus())) {
            status = "FAILED"; reason = "最近一次当前 API 验证未成功，请查看 Run/Trace 并修正后显式重试";
        } else {
            status = "UNKNOWN"; reason = "最近一次调用尚未确认成功；未知或进行中的结果不作为验证凭证";
        }
        return new HttpApiConsoleContracts.MarketVerificationView(status, reason, recordedApiId,
                attempt.getExpectedContractHash(), attempt.getSourceSetRevision(), attempt.getConnectionRevision(),
                attempt.getCredentialRevision(), attempt.getInvocationId(), attempt.getRunId(), attempt.getTraceId(),
                "VERIFIED".equals(status) ? attempt.getEndedAt().toString() : null);
    }

    private JsonNode snapshot(RuntimeRunEntity run) {
        try {
            JsonNode snapshot = run == null || run.getSnapshotJson() == null ? null : json.readTree(run.getSnapshotJson());
            return snapshot != null && snapshot.isObject() ? snapshot : json.createObjectNode();
        }
        catch (Exception invalid) { return json.createObjectNode(); }
    }

    public static HttpApiConsoleContracts.MarketVerificationView unavailable(String status, String reason) {
        return new HttpApiConsoleContracts.MarketVerificationView(status, reason, null, null, null,
                null, null, null, null, null, null);
    }
}

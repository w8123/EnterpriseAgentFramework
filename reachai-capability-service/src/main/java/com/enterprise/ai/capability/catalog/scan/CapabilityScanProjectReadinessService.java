package com.enterprise.ai.capability.catalog.scan;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.registry.CapabilitySnapshotEntity;
import com.enterprise.ai.agent.registry.CapabilitySnapshotMapper;
import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.ProjectInstanceMapper;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads current instance and signed callback evidence; configured endpoints are never treated as proof. */
@Service
@RequiredArgsConstructor
public class CapabilityScanProjectReadinessService {
    private final ProjectInstanceMapper projectInstanceMapper;
    private final CapabilitySnapshotMapper capabilitySnapshotMapper;
    private final RegistrySecurityService registrySecurityService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> readinessFacts(ScanProjectEntity project) {
        return readinessFacts(project, latestInstance(project.getId()), sdkSyncCallbackCheck(project));
    }

    private Map<String, Object> readinessFacts(ScanProjectEntity project, ProjectInstanceEntity latest,
                                              SdkAccessCheckItem callbackCheck) {
        boolean instanceExists = latest != null;
        boolean online = CapabilityProjectInstanceOnlineSupport.isOnline(latest);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", "capability.project-readiness-facts.v1");
        body.put("projectId", project.getId());
        body.put("projectCode", project.getProjectCode());
        body.put("instanceExists", instanceExists);
        body.put("latestInstanceId", latest == null ? null : latest.getInstanceId());
        body.put("latestInstanceStatus", CapabilityProjectInstanceOnlineSupport.statusOrNull(latest));
        body.put("lastHeartbeatAt", latest == null || latest.getLastHeartbeatAt() == null
                ? null
                : latest.getLastHeartbeatAt().toString());
        body.put("online", online);
        body.put("heartbeatOnlineThresholdMinutes", CapabilityProjectInstanceOnlineSupport.HEARTBEAT_ONLINE_MINUTES);
        body.put("instanceCount", instanceExists ? 1 : 0);
        body.put("sdkCallbackStatus", callbackCheck.status());
        body.put("sdkCallbackMessage", callbackCheck.message());
        body.put("sdkCallbackEvidence", callbackCheck.evidence());
        return body;
    }

    public SdkAccessCheckResponse sdkAccessCheck(ScanProjectEntity entity) {
        RegistryCredentialEntity credential = registrySecurityService.findPrimaryActiveCredential(entity.getProjectCode()).orElse(null);
        boolean credentialConfigured = credential != null;
        boolean aiCodingEnabled = Boolean.TRUE.equals(entity.getAiCodingAccessEnabled())
                && StringUtils.hasText(entity.getAiCodingAccessKey());
        SdkAccessCheckItem sdkSyncCallbackCheck = sdkSyncCallbackCheck(entity);
        Map<String, Object> facts = readinessFacts(entity, latestInstance(entity.getId()), sdkSyncCallbackCheck);
        boolean instanceExists = Boolean.TRUE.equals(facts.get("instanceExists"));
        boolean online = Boolean.TRUE.equals(facts.get("online"));
        String lastHeartbeatAt = facts.get("lastHeartbeatAt") == null ? null : String.valueOf(facts.get("lastHeartbeatAt"));
        List<SdkAccessCheckItem> checks = List.of(
                new SdkAccessCheckItem(
                        "PROJECT",
                        "项目识别",
                        "PASS",
                        "已读取项目 " + entity.getProjectCode(),
                        null),
                new SdkAccessCheckItem(
                        "REGISTRY_CREDENTIAL",
                        "注册凭据",
                        credentialConfigured ? "PASS" : "WARN",
                        credentialConfigured ? "已配置启用中的注册凭据" : "尚未配置启用中的注册凭据",
                        null),
                new SdkAccessCheckItem(
                        "AI_CODING_ACCESS",
                        "AI Coding 接入",
                        aiCodingEnabled ? "PASS" : "WARN",
                        aiCodingEnabled ? "已启用 AI Coding 接入" : "AI Coding 接入未启用",
                        null),
                sdkSyncCallbackCheck,
                new SdkAccessCheckItem(
                        "INSTANCE_HEARTBEAT",
                        "业务实例心跳",
                        online ? "PASS" : (instanceExists ? "WARN" : "FAIL"),
                        online
                                ? "业务实例在线，且最近心跳有效"
                                : (instanceExists
                                ? "业务实例已注册，但没有有效的在线心跳；请启动或重启业务服务"
                                : "尚无业务实例注册；请使用 ReachAI Starter 启动业务服务"),
                        lastHeartbeatAt));
        String codeReady;
        if (!aiCodingEnabled || !credentialConfigured) {
            codeReady = "WARN";
        } else if (!instanceExists) {
            codeReady = "PENDING";
        } else {
            codeReady = "PASS";
        }
        String runtimeReady = online ? "PASS" : (instanceExists ? "WARN" : "FAIL");
        String sdkCallbackReady = "PASS".equals(sdkSyncCallbackCheck.status()) ? "PASS" : "PENDING";
        String overall = checks.stream().anyMatch(check -> "FAIL".equals(check.status()))
                ? "FAIL"
                : (checks.stream().allMatch(check -> "PASS".equals(check.status())) ? "PASS" : "WARN");
        return new SdkAccessCheckResponse(
                entity.getId(),
                entity.getProjectCode(),
                overall,
                List.of(
                        new SdkAccessReadiness("CODE_READY", "代码接入", codeReady,
                                "PASS".equals(codeReady)
                                        ? "项目接入配置已启用，且已观测到业务实例通过 ReachAI Starter 注册"
                                        : (instanceExists
                                        ? "业务实例已注册，但项目凭据或 AI Coding 接入尚未配置完整"
                                        : "尚未观测到业务实例通过 ReachAI Starter 注册")),
                        new SdkAccessReadiness("RUNTIME_READY", "Runtime 就绪", runtimeReady,
                                online
                                        ? "业务实例在线，且最近心跳有效"
                                        : "业务服务当前没有有效的在线心跳；请启动服务并等待心跳"),
                        new SdkAccessReadiness("SDK_CALLBACK_READY", "SDK 回调闭环", sdkCallbackReady,
                                "PASS".equals(sdkCallbackReady)
                                        ? "ReachAI 已完成签名 SDK 回调并收到能力快照"
                                        : "尚无签名 SDK 回调证据；仅配置回调地址不能视为通过")),
                checks);
    }

    private ProjectInstanceEntity latestInstance(Long projectId) {
        return projectInstanceMapper.selectOne(
                Wrappers.<ProjectInstanceEntity>lambdaQuery()
                        .eq(ProjectInstanceEntity::getProjectId, projectId)
                        .orderByDesc(ProjectInstanceEntity::getLastHeartbeatAt)
                        .last("LIMIT 1"));
    }

    private String resolveManifestBaseUrl(ScanProjectEntity project) {
        return StringUtils.hasText(project.getBaseUrl()) ? project.getBaseUrl().trim() : null;
    }

    private SdkAccessCheckItem sdkSyncCallbackCheck(ScanProjectEntity project) {
        String baseUrl = resolveManifestBaseUrl(project);
        if (!StringUtils.hasText(baseUrl)) {
            return new SdkAccessCheckItem(
                    "SDK_SYNC_CALLBACK",
                    "SDK 同步回调",
                    "WARN",
                    "尚未配置 ReachAI 服务端可访问的 reachai.project.base-url",
                    null);
        }
        String targetUrl = baseUrl.replaceAll("/+$", "")
                + normalizeContextPath(project.getContextPath())
                + "/reachai/registry/capabilities/sync";
        CapabilitySnapshotEntity callbackSnapshot = capabilitySnapshotMapper.selectOne(
                Wrappers.<CapabilitySnapshotEntity>lambdaQuery()
                        .eq(CapabilitySnapshotEntity::getProjectId, project.getId())
                        .eq(CapabilitySnapshotEntity::getSource, "SDK_CALLBACK")
                        .orderByDesc(CapabilitySnapshotEntity::getCreatedAt)
                        .orderByDesc(CapabilitySnapshotEntity::getId)
                        .last("LIMIT 1"));
        if (callbackSnapshot != null) {
            if (project.getUpdateTime() != null
                    && (callbackSnapshot.getCreatedAt() == null
                    || callbackSnapshot.getCreatedAt().isBefore(project.getUpdateTime()))
                    && !callbackSnapshotMatchesCurrentEndpoint(callbackSnapshot, project)) {
                return new SdkAccessCheckItem(
                        "SDK_SYNC_CALLBACK",
                        "SDK 同步回调",
                        "WARN",
                        "STALE_EVIDENCE: 项目接入配置已在上次签名回调后更新，请重新执行 SDK 同步验证",
                        targetUrl);
            }
            String verifiedAt = callbackSnapshot.getCreatedAt() == null
                    ? null
                    : callbackSnapshot.getCreatedAt().toString();
            return new SdkAccessCheckItem(
                    "SDK_SYNC_CALLBACK",
                    "SDK 同步回调",
                    "PASS",
                    verifiedAt == null
                            ? "ReachAI 已完成签名回调并收到能力快照"
                            : "ReachAI 已完成签名回调并收到能力快照，验证时间：" + verifiedAt,
                    targetUrl);
        }
        boolean loopback = isLoopbackUrl(targetUrl);
        String message = "CONFIGURED_NOT_PROBED: "
                + (loopback
                ? "已推导同步回调目标（回环地址），未主动探测可达性"
                : "已推导同步回调目标，未主动探测可达性/鉴权");
        return new SdkAccessCheckItem(
                "SDK_SYNC_CALLBACK",
                "SDK 同步回调",
                "WARN",
                message,
                targetUrl);
    }

    private boolean callbackSnapshotMatchesCurrentEndpoint(CapabilitySnapshotEntity snapshot,
                                                           ScanProjectEntity project) {
        if (!StringUtils.hasText(snapshot.getPayloadJson())) {
            return false;
        }
        try {
            JsonNode capabilities = objectMapper.readTree(snapshot.getPayloadJson()).path("capabilities");
            if (!capabilities.isArray() || capabilities.isEmpty()) {
                return false;
            }
            String currentBaseUrl = normalizeHttpBaseUrl(project.getBaseUrl());
            String currentContextPath = normalizeContextPath(project.getContextPath());
            for (JsonNode capability : capabilities) {
                String snapshotBaseUrl = normalizeHttpBaseUrl(capability.path("baseUrl").asText(null));
                String snapshotContextPath = normalizeContextPath(capability.path("contextPath").asText(null));
                if (!Objects.equals(currentBaseUrl, snapshotBaseUrl)
                        || !Objects.equals(currentContextPath, snapshotContextPath)) {
                    return false;
                }
            }
            return true;
        } catch (JsonProcessingException ex) {
            return false;
        }
    }

    private boolean isLoopbackUrl(String value) {
        try {
            String host = URI.create(value).getHost();
            return host != null && ("localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private String normalizeContextPath(String contextPath) {
        if (!StringUtils.hasText(contextPath)) {
            return "";
        }
        String trimmed = contextPath.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private String normalizeHttpBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "";
        }
        return baseUrl.trim()
                .replaceFirst("^http:(?!//)", "http://")
                .replaceFirst("^https:(?!//)", "https://");
    }

    public record SdkAccessCheckResponse(
            Long projectId,
            String projectCode,
            String overallStatus,
            List<SdkAccessReadiness> readiness,
            List<SdkAccessCheckItem> checks
    ) {
    }

    public record SdkAccessReadiness(String key, String label, String status, String message) {
    }

    public record SdkAccessCheckItem(String key, String label, String status, String message, String evidence) {
    }

}

package com.enterprise.ai.control.aiassist;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared CODE / RUNTIME / E2E readiness evidence calculator for AI Coding onboarding.
 */
public final class ControlSdkAccessReadinessCalculator {

    private ControlSdkAccessReadinessCalculator() {
    }

    public static ControlAiAssistProjectController.SdkAccessCheckResponse calculate(
            Map<String, Object> project,
            Map<String, Object> readinessFacts,
            boolean embedArtifactAvailable,
            String embedArtifactEvidence) {
        boolean credentialConfigured = booleanValue(project.get("registryCredentialConfigured"));
        boolean aiCodingEnabled = booleanValue(mapValue(project.get("aiCodingAccess")).get("enabled"))
                || booleanValue(project.get("aiCodingAccessEnabled"));
        ControlAiAssistProjectController.SdkAccessCheckItem callbackCheck =
                ControlAiAssistProjectController.sdkSyncCallbackCheck(project);
        // Callback URL computed is CONFIGURED_NOT_PROBED, never PASS evidence for runtime/e2e.
        if ("PASS".equals(callbackCheck.status())) {
            callbackCheck = new ControlAiAssistProjectController.SdkAccessCheckItem(
                    callbackCheck.key(),
                    callbackCheck.label(),
                    "WARN",
                    "Callback URL is configured/derived but not probed (CONFIGURED_NOT_PROBED). "
                            + callbackCheck.message(),
                    callbackCheck.evidence());
        }

        List<ControlAiAssistProjectController.SdkAccessCheckItem> checks = new ArrayList<>();
        checks.add(new ControlAiAssistProjectController.SdkAccessCheckItem(
                "PROJECT",
                "项目识别",
                "PASS",
                "已读取项目 " + stringValue(project.get("projectCode")),
                null));
        checks.add(new ControlAiAssistProjectController.SdkAccessCheckItem(
                "REGISTRY_CREDENTIAL",
                "注册凭据",
                credentialConfigured ? "PASS" : "WARN",
                credentialConfigured ? "已配置 active registry credential" : "尚未配置 active registry credential",
                null));
        checks.add(new ControlAiAssistProjectController.SdkAccessCheckItem(
                "AI_CODING_ACCESS",
                "AI Coding 接入",
                aiCodingEnabled ? "PASS" : "WARN",
                aiCodingEnabled ? "已启用 AI Coding 接入" : "AI Coding 接入未启用",
                null));
        checks.add(new ControlAiAssistProjectController.SdkAccessCheckItem(
                "EMBED_SDK_ARTIFACT",
                "Embed SDK 制品",
                embedArtifactAvailable ? "PASS" : "WARN",
                embedArtifactAvailable
                        ? "Embed SDK tarball is available for install"
                        : "Embed SDK tarball is missing from platform artifacts",
                embedArtifactEvidence));
        checks.add(callbackCheck);

        boolean instanceExists = booleanValue(readinessFacts.get("instanceExists"));
        boolean online = booleanValue(readinessFacts.get("online"));
        String lastHeartbeatAt = stringValue(readinessFacts.get("lastHeartbeatAt"));
        checks.add(new ControlAiAssistProjectController.SdkAccessCheckItem(
                "INSTANCE_HEARTBEAT",
                "业务实例心跳",
                online ? "PASS" : (instanceExists ? "WARN" : "FAIL"),
                online
                        ? "Latest project instance is ONLINE with a fresh heartbeat"
                        : (instanceExists
                        ? "Project instance exists but is not ONLINE with a fresh heartbeat; start/restart the business service"
                        : "No project instance has registered yet; start the business service with ReachAI starter"),
                lastHeartbeatAt));

        String codeReady = (credentialConfigured || aiCodingEnabled) && embedArtifactAvailable
                ? (aiCodingEnabled && embedArtifactAvailable ? "PASS" : "WARN")
                : "WARN";
        if (!embedArtifactAvailable) {
            codeReady = "WARN";
        } else if (aiCodingEnabled && credentialConfigured) {
            codeReady = "PASS";
        } else {
            codeReady = "WARN";
        }

        String runtimeReady = online ? "PASS" : (instanceExists ? "WARN" : "FAIL");
        String e2eReady = "PENDING";
        String e2eMessage = "No explicit E2E evidence yet (embed token/session/message or probed callback success).";

        String overall = worst(codeReady, runtimeReady, e2eReady.equals("PENDING") ? "WARN" : e2eReady);
        return new ControlAiAssistProjectController.SdkAccessCheckResponse(
                longValue(project.get("id")),
                stringValue(project.get("projectCode")),
                overall,
                List.of(
                        new ControlAiAssistProjectController.SdkAccessReadiness(
                                "CODE_READY",
                                "代码接入",
                                codeReady,
                                codeReadyMessage(codeReady, embedArtifactAvailable, aiCodingEnabled)),
                        new ControlAiAssistProjectController.SdkAccessReadiness(
                                "RUNTIME_READY",
                                "Runtime 就绪",
                                runtimeReady,
                                runtimeReadyMessage(runtimeReady, instanceExists, online)),
                        new ControlAiAssistProjectController.SdkAccessReadiness(
                                "E2E_READY",
                                "端到端",
                                e2eReady,
                                e2eMessage)),
                checks);
    }

    private static String codeReadyMessage(String status, boolean artifact, boolean aiCoding) {
        if ("PASS".equals(status)) {
            return "Manifest/config/SDK artifact contracts are available for coding.";
        }
        if (!artifact) {
            return "Rebuild Embed SDK artifact (npm run build:sdk:pack) and restart Control.";
        }
        if (!aiCoding) {
            return "Enable AI Coding access and ensure registry credentials before coding.";
        }
        return "Code readiness is incomplete; review CODE checks.";
    }

    private static String runtimeReadyMessage(String status, boolean instanceExists, boolean online) {
        if ("PASS".equals(status)) {
            return "Business project instance is ONLINE with a fresh heartbeat.";
        }
        if (!instanceExists) {
            return "Start the business service so ReachAI starter can register an instance and heartbeat.";
        }
        if (!online) {
            return "Business instance is not ONLINE with a fresh heartbeat; restart/start the business service.";
        }
        return "Runtime readiness is not confirmed.";
    }

    private static String worst(String... statuses) {
        String worst = "PASS";
        for (String status : statuses) {
            if ("FAIL".equals(status)) {
                return "FAIL";
            }
            if ("WARN".equals(status) || "PENDING".equals(status)) {
                worst = "WARN";
            }
        }
        return worst;
    }

    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return Map.of();
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return Long.parseLong(text.trim());
        }
        return null;
    }
}

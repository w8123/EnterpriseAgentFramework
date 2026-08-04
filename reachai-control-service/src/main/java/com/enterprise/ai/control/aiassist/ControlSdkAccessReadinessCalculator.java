package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.platform.PlatformEmbedE2eEvidenceService.EmbedConversationEvidence;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared CODE / RUNTIME / browser Embed E2E readiness calculator for AI Coding onboarding.
 */
public final class ControlSdkAccessReadinessCalculator {

    private ControlSdkAccessReadinessCalculator() {
    }

    public static ControlAiAssistProjectController.SdkAccessCheckResponse calculate(
            Map<String, Object> project,
            Map<String, Object> readinessFacts,
            boolean embedArtifactAvailable,
            String embedArtifactEvidence) {
        return calculate(
                project,
                readinessFacts,
                embedArtifactAvailable,
                embedArtifactEvidence,
                EmbedConversationEvidence.pending(
                        "尚未观察到当前任务启动后的真实浏览器 Embed 会话。"));
    }

    public static ControlAiAssistProjectController.SdkAccessCheckResponse calculate(
            Map<String, Object> project,
            Map<String, Object> readinessFacts,
            boolean embedArtifactAvailable,
            String embedArtifactEvidence,
            EmbedConversationEvidence embedE2eEvidence) {
        EmbedConversationEvidence e2eEvidence = embedE2eEvidence == null
                ? EmbedConversationEvidence.pending(
                        "尚未观察到当前任务启动后的真实浏览器 Embed 会话。")
                : embedE2eEvidence;
        boolean credentialConfigured = booleanValue(project.get("registryCredentialConfigured"));
        boolean aiCodingEnabled = booleanValue(mapValue(project.get("aiCodingAccess")).get("enabled"))
                || booleanValue(project.get("aiCodingAccessEnabled"));
        ControlAiAssistProjectController.SdkAccessCheckItem callbackCheck =
                callbackCheck(project, readinessFacts);

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
                credentialConfigured ? "已配置启用中的注册凭据" : "尚未配置启用中的注册凭据",
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
                        ? "Embed SDK 安装制品已就绪"
                        : "平台缺少 Embed SDK 安装制品",
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
                        ? "业务项目实例在线，且最近心跳有效"
                        : (instanceExists
                        ? "业务项目实例已存在，但没有有效的在线心跳；请启动或重启业务服务"
                        : "尚无业务项目实例注册；请使用 ReachAI Starter 启动业务服务"),
                lastHeartbeatAt));
        checks.add(new ControlAiAssistProjectController.SdkAccessCheckItem(
                "EMBED_CONVERSATION_E2E",
                "Embed 真实会话",
                e2eEvidence.passed() ? "PASS" : "PENDING",
                e2eEvidence.message(),
                e2eEvidence.evidence()));

        String codeReady;
        if (!embedArtifactAvailable) {
            codeReady = "WARN";
        } else if (!aiCodingEnabled || !credentialConfigured) {
            codeReady = "WARN";
        } else if (!instanceExists) {
            codeReady = "PENDING";
        } else {
            codeReady = "PASS";
        }

        String runtimeReady = online ? "PASS" : (instanceExists ? "WARN" : "FAIL");
        String callbackReady = "PASS".equals(callbackCheck.status())
                ? "PASS"
                : "PENDING";
        String e2eReady = e2eEvidence.passed() ? "PASS" : "PENDING";
        String e2eMessage = e2eEvidence.message();

        String overall = worst(
                codeReady,
                runtimeReady,
                callbackReady.equals("PENDING") ? "WARN" : callbackReady,
                e2eReady.equals("PENDING") ? "WARN" : e2eReady);
        return new ControlAiAssistProjectController.SdkAccessCheckResponse(
                longValue(project.get("id")),
                stringValue(project.get("projectCode")),
                overall,
                List.of(
                        new ControlAiAssistProjectController.SdkAccessReadiness(
                                "CODE_READY",
                                "代码接入",
                                codeReady,
                                codeReadyMessage(
                                        codeReady,
                                        embedArtifactAvailable,
                                        aiCodingEnabled,
                                        credentialConfigured,
                                        instanceExists)),
                        new ControlAiAssistProjectController.SdkAccessReadiness(
                                "RUNTIME_READY",
                                "Runtime 就绪",
                                runtimeReady,
                                runtimeReadyMessage(runtimeReady, instanceExists, online)),
                        new ControlAiAssistProjectController.SdkAccessReadiness(
                                "SDK_CALLBACK_READY",
                                "SDK 回调闭环",
                                callbackReady,
                                callbackCheck.message()),
                        new ControlAiAssistProjectController.SdkAccessReadiness(
                                "E2E_READY",
                                "浏览器 Embed 闭环",
                                e2eReady,
                                e2eMessage)),
                checks);
    }

    private static ControlAiAssistProjectController.SdkAccessCheckItem callbackCheck(
            Map<String, Object> project,
            Map<String, Object> readinessFacts) {
        String observedStatus = stringValue(readinessFacts.get("sdkCallbackStatus"));
        if (StringUtils.hasText(observedStatus)) {
            return new ControlAiAssistProjectController.SdkAccessCheckItem(
                    "SDK_SYNC_CALLBACK",
                    "SDK 同步回调",
                    observedStatus,
                    stringValue(readinessFacts.get("sdkCallbackMessage")),
                    stringValue(readinessFacts.get("sdkCallbackEvidence")));
        }
        ControlAiAssistProjectController.SdkAccessCheckItem computed =
                ControlAiAssistProjectController.sdkSyncCallbackCheck(project);
        return new ControlAiAssistProjectController.SdkAccessCheckItem(
                computed.key(),
                computed.label(),
                "WARN",
                "回调地址已配置或可推导，但 Capability owning service "
                        + "尚未提供真实签名回调证据（CONFIGURED_NOT_PROBED）。"
                        + computed.message(),
                computed.evidence());
    }

    private static String codeReadyMessage(
            String status,
            boolean artifact,
            boolean aiCoding,
            boolean credential,
            boolean instanceExists) {
        if ("PASS".equals(status)) {
            return "已观测到业务实例注册，项目配置和 SDK 接入已实际生效。";
        }
        if (!artifact) {
            return "请重新构建 Embed SDK 制品（npm run build:sdk:pack）并重启 Control 服务。";
        }
        if (!aiCoding) {
            return "请启用 AI Coding 接入，并确认项目注册凭据已配置。";
        }
        if (!credential) {
            return "请配置并启用项目注册凭据。";
        }
        if (!instanceExists) {
            return "平台配置和 SDK 制品已就绪，但尚未观测到业务实例注册，无法确认 Starter 已实际生效。";
        }
        return "代码接入条件尚不完整，请检查相关配置。";
    }

    private static String runtimeReadyMessage(String status, boolean instanceExists, boolean online) {
        if ("PASS".equals(status)) {
            return "业务项目实例在线，且最近心跳有效。";
        }
        if (!instanceExists) {
            return "请启动业务服务，让 ReachAI Starter 完成实例注册和心跳上报。";
        }
        if (!online) {
            return "业务实例没有有效的在线心跳，请启动或重启业务服务。";
        }
        return "尚未确认 Runtime 运行状态。";
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

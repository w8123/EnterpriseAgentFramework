package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.platform.PlatformEmbedE2eEvidenceService.EmbedConversationEvidence;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlSdkAccessReadinessCalculatorTest {

    private static final Map<String, Object> CONFIGURED_PROJECT = Map.of(
            "id", 7L,
            "projectCode", "orders",
            "registryCredentialConfigured", true,
            "aiCodingAccess", Map.of("enabled", true));

    @Test
    void keepsCodeReadinessPendingUntilAnInstanceRegisters() {
        var result = ControlSdkAccessReadinessCalculator.calculate(
                CONFIGURED_PROJECT,
                Map.of("instanceExists", false, "online", false),
                true,
                "/api/sdk/embed-chat/package",
                EmbedConversationEvidence.pending(
                        "尚未观察到真实会话。"));

        assertEquals(
                "PENDING",
                readinessStatus(result, "CODE_READY"));
        assertEquals(
                "FAIL",
                readinessStatus(result, "RUNTIME_READY"));
        assertEquals(
                "PENDING",
                readinessStatus(result, "E2E_READY"));
        assertEquals(
                "浏览器 Embed 闭环",
                readinessLabel(result, "E2E_READY"));
    }

    @Test
    void treatsRegisteredButOfflineInstanceAsCodeEvidenceOnly() {
        var result = ControlSdkAccessReadinessCalculator.calculate(
                CONFIGURED_PROJECT,
                Map.of("instanceExists", true, "online", false),
                true,
                "/api/sdk/embed-chat/package",
                EmbedConversationEvidence.pending(
                        "尚未观察到真实会话。"));

        assertEquals(
                "PASS",
                readinessStatus(result, "CODE_READY"));
        assertEquals(
                "WARN",
                readinessStatus(result, "RUNTIME_READY"));
        assertEquals(
                "PENDING",
                readinessStatus(result, "E2E_READY"));
    }

    @Test
    void trustsCapabilityCallbackEvidenceButKeepsBrowserE2eSeparate() {
        var result = ControlSdkAccessReadinessCalculator.calculate(
                CONFIGURED_PROJECT,
                Map.of(
                        "instanceExists", true,
                        "online", true,
                        "sdkCallbackStatus", "PASS",
                        "sdkCallbackMessage", "已收到签名能力快照",
                        "sdkCallbackEvidence", "http://business/reachai/registry/capabilities/sync"),
                true,
                "/api/sdk/embed-chat/package",
                EmbedConversationEvidence.pending("尚未观察到真实会话。"));

        assertEquals(
                "PASS",
                readinessStatus(result, "SDK_CALLBACK_READY"));
        assertEquals(
                "PENDING",
                readinessStatus(result, "E2E_READY"));
    }

    private static String readinessStatus(
            ControlAiAssistProjectController.SdkAccessCheckResponse result,
            String key) {
        return result.readiness().stream()
                .filter(item -> key.equals(item.key()))
                .findFirst()
                .orElseThrow()
                .status();
    }

    private static String readinessLabel(
            ControlAiAssistProjectController.SdkAccessCheckResponse result,
            String key) {
        return result.readiness().stream()
                .filter(item -> key.equals(item.key()))
                .findFirst()
                .orElseThrow()
                .label();
    }
}

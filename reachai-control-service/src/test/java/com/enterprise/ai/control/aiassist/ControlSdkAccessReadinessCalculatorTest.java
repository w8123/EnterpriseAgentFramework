package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.platform.PlatformEmbedE2eEvidenceService.EmbedConversationEvidence;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                "授权对话链路",
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

    @Test
    void exposesOnlyScopedRecordedTimesWithoutChangingReadiness() {
        var result = ControlSdkAccessReadinessCalculator.calculate(
                CONFIGURED_PROJECT,
                Map.of("instanceExists", true, "online", true,
                        "lastHeartbeatAt", "2026-07-26T10:05:00",
                        "sdkCallbackStatus", "PASS",
                        "sdkCallbackVerifiedAt", "2026-07-25T09:30:00",
                        "sdkCallbackEvidence", "https://business.invalid/?signature=secret"),
                true, "/api/sdk/embed-chat/package",
                EmbedConversationEvidence.passed("已观察到会话。", "secret-session"));

        var runtime = readinessItem(result, "RUNTIME_READY");
        var callback = readinessItem(result, "SDK_CALLBACK_READY");
        var conversation = readinessItem(result, "E2E_READY");
        assertEquals("PASS", runtime.status());
        assertEquals(Map.of("scope", "PROJECT", "timeKind", "LAST_HEARTBEAT",
                "observedAt", "2026-07-26T10:05:00"), runtime.evidence());
        assertEquals(Map.of("scope", "PROJECT", "timeKind", "LAST_SUCCESSFUL_CALLBACK",
                "observedAt", "2026-07-25T09:30:00"), callback.evidence());
        assertFalse(callback.evidence().toString().contains("secret"));
        assertEquals("PASS", conversation.status());
        assertTrue(conversation.message().contains("真实页面验收"));
        assertEquals(null, conversation.evidence());
    }

    @Test
    void doesNotInventObservationTime() {
        var result = ControlSdkAccessReadinessCalculator.calculate(
                CONFIGURED_PROJECT, Map.of("instanceExists", true, "online", false),
                true, "/api/sdk/embed-chat/package", EmbedConversationEvidence.pending("等待会话"));
        assertEquals(Map.of("scope", "PROJECT"), readinessItem(result, "RUNTIME_READY").evidence());
        assertEquals(Map.of("scope", "PROJECT"), readinessItem(result, "SDK_CALLBACK_READY").evidence());
    }

    private static ControlAiAssistProjectController.SdkAccessReadiness readinessItem(
            ControlAiAssistProjectController.SdkAccessCheckResponse result, String key) {
        return result.readiness().stream().filter(item -> key.equals(item.key())).findFirst().orElseThrow();
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

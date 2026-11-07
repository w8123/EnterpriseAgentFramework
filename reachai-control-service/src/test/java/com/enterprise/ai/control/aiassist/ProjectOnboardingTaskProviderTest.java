package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetView;
import com.enterprise.ai.control.aicoding.provider.AiCodingContractResourceLoader;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.platform.PlatformEmbedE2eEvidenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectOnboardingTaskProviderTest {

    @Test
    void goReadinessRejectsOtherLanguagesAndUnscopedEvidence() {
        for (String language : List.of("java", "csharp", "python", "node")) {
            var other = ProjectOnboardingTaskProvider.factsForLanguage("go", Map.of(
                    "sdkLanguage", language, "instanceExists", true, "online", true,
                    "sdkCallbackStatus", "PASS", "sdkCallbackEvidence", "other-language callback"));
            assertEquals(false, other.get("instanceExists"));
            assertEquals(false, other.get("online"));
            assertEquals("WARN", other.get("sdkCallbackStatus"));
            assertEquals(null, other.get("sdkCallbackEvidence"));
        }
        var same = ProjectOnboardingTaskProvider.factsForLanguage("go", Map.of(
                "sdkLanguage", "go", "instanceExists", true, "online", true));
        assertEquals(true, same.get("online"));
        assertEquals("WARN", same.get("sdkCallbackStatus"));
    }

    @Test
    void nodeReadinessRejectsOtherLanguagesAndUnscopedCallbackEvidence() {
        var other = ProjectOnboardingTaskProvider.factsForLanguage("node", Map.of(
                "sdkLanguage", "python", "instanceExists", true, "online", true,
                "sdkCallbackStatus", "PASS", "sdkCallbackEvidence", "old callback"));
        assertEquals(false, other.get("instanceExists"));
        assertEquals(false, other.get("online"));
        assertEquals("WARN", other.get("sdkCallbackStatus"));
        assertEquals(null, other.get("sdkCallbackEvidence"));
        var same = ProjectOnboardingTaskProvider.factsForLanguage("node", Map.of(
                "sdkLanguage", "node", "instanceExists", true, "online", true));
        assertEquals(true, same.get("online"));
        assertEquals("WARN", same.get("sdkCallbackStatus"));
    }

    @Test
    void pythonReadinessRejectsUnscopedOrOtherLanguageEvidence() {
        Map<String, Object> javaFacts = Map.of("sdkLanguage", "java", "instanceExists", true,
                "online", true, "sdkCallbackStatus", "PASS", "sdkCallbackEvidence", "old callback");
        var scoped = ProjectOnboardingTaskProvider.factsForLanguage("python", javaFacts);
        assertEquals(false, scoped.get("instanceExists"));
        assertEquals(false, scoped.get("online"));
        assertEquals("WARN", scoped.get("sdkCallbackStatus"));
        assertEquals(null, scoped.get("sdkCallbackEvidence"));
        var pythonFacts = ProjectOnboardingTaskProvider.factsForLanguage("python", Map.of(
                "sdkLanguage", "python", "instanceExists", true, "online", true));
        assertEquals(true, pythonFacts.get("online"));
        assertEquals("WARN", pythonFacts.get("sdkCallbackStatus"));
        assertEquals(javaFacts, ProjectOnboardingTaskProvider.factsForLanguage("java", javaFacts));
    }

    @Test
    void languageSelectionKeepsLegacyDefaultAndPrioritizesExplicitTarget() {
        ObjectMapper json = new ObjectMapper();
        TaskTargetView target = new TaskTargetView(1L, "PROJECT", "orders", "PRIMARY", "READ_WRITE",
                json.createObjectNode().put("sdkLanguage", "csharp"));
        TaskDescriptor task = new TaskDescriptor("id", 7L, "orders", "PROJECT_ONBOARDING",
                "PROJECT_ONBOARDING", "v1", "CODEX", "title", "objective", "READ_WRITE", "READY",
                json.createObjectNode(), List.of(target), null, null);
        assertEquals("csharp", ProjectOnboardingTaskProvider.sdkLanguage(task, Map.of("sdkLanguage", "java")));
        assertEquals("csharp", ProjectOnboardingTaskProvider.sdkLanguage(null, Map.of("sdkLanguage", "csharp")));
        assertEquals("python", ProjectOnboardingTaskProvider.sdkLanguage(null, Map.of("sdkLanguage", "python")));
        assertEquals("go", ProjectOnboardingTaskProvider.sdkLanguage(null, Map.of("sdkLanguage", "go")));
        assertEquals("unknown", ProjectOnboardingTaskProvider.sdkLanguage(null, Map.of("sdkLanguage", "other")));
        assertEquals("java", ProjectOnboardingTaskProvider.sdkLanguage(null, Map.of()));
    }

    @Test
    void exposesSdkOwnedEmbedPageIdentityContract() {
        CapabilityProjectOnboardingClient client =
                mock(CapabilityProjectOnboardingClient.class);
        when(client.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "projectCode", "orders",
                "name", "Orders"));
        ObjectMapper objectMapper = new ObjectMapper();
        PlatformEmbedE2eEvidenceService e2eEvidenceService =
                mock(PlatformEmbedE2eEvidenceService.class);
        ProjectOnboardingTaskProvider provider =
                new ProjectOnboardingTaskProvider(
                        client,
                        e2eEvidenceService,
                        objectMapper,
                        new AiCodingContractResourceLoader(objectMapper));
        TaskDescriptor task = new TaskDescriptor(
                "ait_test",
                7L,
                "orders",
                "PROJECT_ONBOARDING",
                ProjectOnboardingTaskProvider.TASK_KIND,
                "v1",
                "TRAE",
                "接入 ReachAI",
                "完成项目接入",
                "READ_WRITE",
                "READY",
                objectMapper.createObjectNode(),
                List.of(),
                null,
                null);

        JsonNode context = provider.buildContext(task);
        assertEquals("STARTER", context.path("canonicalSteps").path(1).path("stepKey").asText());
        assertEquals("后端 SDK", context.path("canonicalSteps").path(1).path("title").asText());
        JsonNode contract = context
                .path("implementationGuidance")
                .path("embedPageIdentityContract");

        assertEquals(
                "@reachai/embed-chat tokenProvider context",
                contract.path("source").asText());
        assertEquals(
                List.of("pageKey", "pageInstanceId", "route", "origin"),
                objectMapper.convertValue(
                        contract.path("requiredFields"),
                        objectMapper.getTypeFactory()
                                .constructCollectionType(List.class, String.class)));
        assertEquals(
                "Never generate a replacement pageInstanceId in the browser token provider, business broker or platform client.",
                contract.path("fallbackRule").asText());
        assertEquals(
                "测试与浏览器验收",
                context.path("canonicalSteps").path(5).path("title").asText());
        assertEquals(
                "SDK_SYNC",
                context.path("implementationGuidance")
                        .path("sdkSyncVerificationKey")
                        .asText());
        assertEquals(
                List.of(
                        "CODE_READY",
                        "RUNTIME_READY",
                        "SDK_CALLBACK_READY",
                        "E2E_READY"),
                provider.acceptanceReadinessKeys());

        JsonNode materialized = provider.materializeContext(
                task,
                context,
                "http://localhost:18603/");
        assertEquals(
                "http://localhost:18603/api/ai-assist/artifacts/java-sdk/"
                        + "reachai-capability-sdk/1.0.0-SNAPSHOT.jar",
                materialized.path("sdkArtifacts")
                        .path(0)
                        .path("downloadUrl")
                        .asText());
        assertEquals(
                "http://localhost:18603/api/ai-assist/skills/"
                        + "reachai-onboarding/latest.zip",
                materialized.path("implementationGuidance")
                        .path("skillPackageUrl")
                        .asText());

        var requiredResources = provider.requiredResources(
                task,
                "http://localhost:18603/");
        assertEquals(1, requiredResources.size());
        assertEquals("reachai-onboarding-skill", requiredResources.get(0).id());
        assertEquals("reachai-onboarding/SKILL.md", requiredResources.get(0).entrypoint());
        assertEquals(true, requiredResources.get(0).requiredBeforeEditing());

        var verificationGuide = provider.verificationGuide(
                task,
                "http://localhost:18603/api/ai-coding/tasks/ait_test");
        assertEquals(4, verificationGuide.size());
        assertEquals("ACTION", verificationGuide.get(0).type());
        assertEquals("http://localhost:18603/api/ai-coding/tasks/ait_test/verifications/SDK_SYNC",
                verificationGuide.get(0).url());
        assertEquals("OBSERVATION", verificationGuide.get(2).type());
        assertEquals("EMBED_CONVERSATION_E2E", verificationGuide.get(2).key());
        assertEquals("READINESS_GATE", verificationGuide.get(3).type());

        when(client.triggerSdkSync(7L)).thenReturn(Map.of(
                "projectId", 7L,
                "projectCode", "orders",
                "capabilityCount", 2));
        JsonNode verification = provider.requestVerification(task, "SDK_SYNC");
        assertEquals(2, verification.path("capabilityCount").asInt());
        verify(client).triggerSdkSync(7L);
    }
}

package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
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

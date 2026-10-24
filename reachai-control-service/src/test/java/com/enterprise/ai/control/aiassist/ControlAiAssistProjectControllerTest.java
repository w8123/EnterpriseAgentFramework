package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.capability.CapabilityAiCodingAccessUpdateRequest;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlAiAssistProjectControllerTest {

    @Test
    void exposesProjectOnboardingManifestFromControlService() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        ControlAiAssistProjectController controller = new ControlAiAssistProjectController(client);
        when(client.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "name", "Orders",
                "projectCode", "orders",
                "projectKind", "REGISTERED",
                "environment", "dev",
                "baseUrl", "http://localhost:18080",
                "contextPath", "/orders",
                "registryAppKey", "app-orders",
                "registryCredentialConfigured", true,
                "aiCodingAccess", Map.of("enabled", true, "accessKey", "aic_test")
        ));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/ai-assist/projects/7/onboarding-manifest");
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(18603);
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session("orders"));

        ResponseEntity<ControlAiAssistProjectController.OnboardingManifestResponse> response =
                controller.onboardingManifest(7L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("reachai.onboarding.v2", response.getBody().schema());
        assertEquals(7L, response.getBody().project().id());
        assertEquals("orders", response.getBody().project().projectCode());
        assertEquals(true, response.getBody().aiCodingAccess().enabled());
        assertEquals("aic_test", response.getBody().aiCodingAccess().accessKey());
        assertEquals("http://localhost:18603", response.getBody().sdk().config().registryUrl());
        assertEquals("com.enterprise.ai:reachai-capability-sdk:1.0.0-SNAPSHOT",
                response.getBody().sdkArtifacts().get(0).coordinates());
        assertEquals("platform-artifact-download",
                response.getBody().sdkArtifacts().get(0).sourcePolicy());
        assertTrue(response.getBody().sdkArtifacts().get(0).downloadUrl()
                .endsWith("/api/ai-assist/artifacts/java-sdk/reachai-capability-sdk/1.0.0-SNAPSHOT.jar"));
        assertTrue(response.getBody().sdkArtifacts().get(0).pomDownloadUrl()
                .endsWith("/api/ai-assist/artifacts/java-sdk/reachai-capability-sdk/1.0.0-SNAPSHOT.pom"));
        assertEquals(64, response.getBody().sdkArtifacts().get(0).integritySha256().length());
        assertEquals(64, response.getBody().sdkArtifacts().get(0).pomIntegritySha256().length());
        assertTrue(response.getBody().sdkArtifacts().get(0).installCommandTemplate()
                .contains("scripts/install-java-sdk.ps1"));
        assertEquals("@reachai/embed-chat@1.0.0-SNAPSHOT",
                response.getBody().sdkArtifacts().get(2).coordinates());
        assertEquals("platform-artifact-tarball",
                response.getBody().sdkArtifacts().get(2).sourcePolicy());
        assertEquals("npm-tarball", response.getBody().sdkArtifacts().get(2).format());
        assertTrue(response.getBody().sdkArtifacts().get(2).downloadUrl()
                .endsWith("/api/ai-assist/artifacts/embed-chat/1.0.0-SNAPSHOT.tgz"));
        assertNotNull(response.getBody().sdkArtifacts().get(2).integritySha256());
        assertEquals(64, response.getBody().sdkArtifacts().get(2).integritySha256().length());
        assertTrue(response.getBody().sdkArtifacts().get(2).installCommand()
                .contains("scripts/install-embed-chat.mjs"));
        assertEquals("@reachai/embed-chat", response.getBody().sdkArtifacts().get(2).packageName());
        assertEquals("reachai-onboarding/artifacts/reachai-embed-chat-1.0.0-SNAPSHOT.tgz",
                response.getBody().sdkArtifacts().get(2).artifactPathWithinSkill());
        assertEquals("business-frontend-package-root",
                response.getBody().sdkArtifacts().get(2).installWorkingDirectory());
        assertTrue(response.getBody().sdkArtifacts().get(2).installCommandTemplate()
                .contains("{skillExtractDir}"));
        assertTrue(response.getBody().sdkArtifacts().get(2).notes()
                .contains("vendor/reachai/reachai-embed-chat-1.0.0-SNAPSHOT.tgz"));
        assertEquals("skill-zip-tarball", response.getBody().sdkArtifacts().get(2).fallbackPolicy());
        assertEquals(6, response.getBody().gatewayChecklist().size());
        assertEquals("embed-route-forwarding", response.getBody().gatewayChecklist().get(0).id());
        assertTrue(response.getBody().gatewayChecklist().get(0).required());
        assertTrue(response.getBody().agentSupervisor().endpoints().workflowToolAttachUrlTemplate()
                .contains("/agent-supervisor/workflow-tools/attach"));
        assertEquals("agent-supervisor.authoring.v2", response.getBody().agentSupervisor().model());
        assertTrue(response.getBody().agentSupervisor().endpoints().agentsUrl()
                .contains("/api/ai-coding/projects/7/agents"));
        assertTrue(response.getBody().agentSupervisor().endpoints().skillPackageUrl()
                .endsWith("/api/ai-assist/skills/agent-ai-coding/latest.zip"));
        assertTrue(response.getBody().agentSupervisor().endpoints().bindableSkillsUrl()
                .endsWith("/agent-skills/bindable"));
        assertEquals("ApiResult", response.getBody().responseShapes().get("embed").wrapper());
        assertEquals("data.token", response.getBody().responseShapes().get("embed").fields().get("token"));
        assertEquals("bare-json", response.getBody().responseShapes().get("agentProvisioning").wrapper());
        assertEquals("bare-json", response.getBody().responseShapes().get("agentAiCoding").wrapper());
        assertEquals("bindings", response.getBody().responseShapes().get("agentSkillBinding")
                .fields().get("catalog"));
        assertEquals("agent.keySlug",
                response.getBody().responseShapes().get("agentProvisioning").fields().get("agentKeySlug"));
        assertEquals("supervisorConfig.status",
                response.getBody().responseShapes().get("agentProvisioning").fields().get("supervisorConfigStatus"));
        assertEquals("agent-provisioning.v2", response.getBody().agentProvisioning().model());
        assertEquals(true, response.getBody().agentProvisioning().activatesSupervisorConfig());
        assertEquals("AGENTSCOPE", response.getBody().agentSupervisor().runtimeType());
        assertEquals("optional exact currently attached Workflow id to replace; omit for additive attachment",
                response.getBody().responseShapes().get("workflowToolAttach")
                        .fields().get("request.replaceWorkflowId"));
        assertTrue(response.getBody().responseShapes().get("workflowToolAttach")
                .notes().contains("WORKFLOW_REPLACEMENT_INVALID"));
        assertEquals("http://localhost:18603/api/ai-assist/projects/7/onboarding-manifest",
                response.getBody().endpoints().manifestUrl());
        assertEquals("scripts/set-reachai-registry-secret.ps1",
                response.getBody().security().secretSetupScriptWithinSkill());
        assertTrue(response.getBody().security().secretSetupCommandTemplate()
                .contains("{skillExtractDir}"));
        verify(client).getOnboardingProjectById(7L);
    }

    @Test
    void forwardsAiCodingAccessUpdatesToCapabilityOwner() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        ControlAiAssistProjectController controller = new ControlAiAssistProjectController(client);
        CapabilityAiCodingAccessUpdateRequest request =
                new CapabilityAiCodingAccessUpdateRequest(true, "aic_manual");
        when(client.updateAiCodingAccess(7L, request)).thenReturn(Map.of(
                "enabled", true,
                "accessKey", "aic_manual"
        ));
        when(client.getOnboardingProjectById(7L)).thenReturn(Map.of("id", 7L, "projectCode", "orders"));
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session("orders"));

        ResponseEntity<ControlAiAssistProjectController.AiCodingAccessManifest> response =
                controller.updateAiCodingAccess(7L, request, httpRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().enabled());
        assertEquals("aic_manual", response.getBody().accessKey());
        verify(client).updateAiCodingAccess(7L, request);
    }

    @Test
    void hidesAiCodingKeyFromReadOnlyRolesAndOtherProjects() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        ControlAiAssistProjectController controller = new ControlAiAssistProjectController(client);
        when(client.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "projectCode", "orders",
                "aiCodingAccess", Map.of("enabled", true, "accessKey", "aic_test")));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai-assist/projects/7/onboarding-manifest");
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session("billing"));

        var manifest = controller.onboardingManifest(7L, request).getBody();

        assertNotNull(manifest);
        assertEquals(true, manifest.aiCodingAccess().enabled());
        assertNull(manifest.aiCodingAccess().accessKey());
    }

    @Test
    void rejectsAiCodingAccessUpdatesOutsideTheGrantedProject() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        ControlAiAssistProjectController controller = new ControlAiAssistProjectController(client);
        when(client.getOnboardingProjectById(7L)).thenReturn(Map.of("id", 7L, "projectCode", "orders"));
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session("billing"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                controller.updateAiCodingAccess(7L, new CapabilityAiCodingAccessUpdateRequest(true, null), httpRequest));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(client, never()).updateAiCodingAccess(anyLong(), any());
    }

    private static PlatformAuthenticatedSession session(String writableProject) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(9L);
        user.setUsername("dev");
        return new PlatformAuthenticatedSession(
                PlatformPrincipal.fromUser(user),
                "session-1",
                LocalDateTime.now().plusHours(1),
                List.of("DEVELOPER"),
                List.of(PlatformPermissions.PLATFORM_WRITE),
                List.of(new PlatformPermissionGrant(PlatformPermissions.PLATFORM_WRITE, "PROJECT", writableProject)));
    }
}

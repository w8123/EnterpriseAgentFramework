package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
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

        ResponseEntity<ControlAiAssistProjectController.OnboardingManifestResponse> response =
                controller.onboardingManifest(7L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("reachai.onboarding.v1", response.getBody().schema());
        assertEquals(7L, response.getBody().project().id());
        assertEquals("orders", response.getBody().project().projectCode());
        assertEquals(true, response.getBody().aiCodingAccess().enabled());
        assertEquals("aic_test", response.getBody().aiCodingAccess().accessKey());
        assertEquals("http://localhost:18603", response.getBody().sdk().config().registryUrl());
        assertEquals("http://localhost:18603/api/ai-assist/projects/7/onboarding-manifest",
                response.getBody().endpoints().manifestUrl());
        verify(client).getOnboardingProjectById(7L);
    }

    @Test
    void forwardsAiCodingAccessUpdatesToCapabilityOwner() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        ControlAiAssistProjectController controller = new ControlAiAssistProjectController(client);
        ControlAiAssistProjectController.AiCodingAccessUpdateRequest request =
                new ControlAiAssistProjectController.AiCodingAccessUpdateRequest(true, "aic_manual");
        when(client.updateAiCodingAccess(7L, request)).thenReturn(Map.of(
                "enabled", true,
                "accessKey", "aic_manual"
        ));

        ResponseEntity<ControlAiAssistProjectController.AiCodingAccessManifest> response =
                controller.updateAiCodingAccess(7L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().enabled());
        assertEquals("aic_manual", response.getBody().accessKey());
        verify(client).updateAiCodingAccess(7L, request);
    }

    @Test
    void startsSdkAccessSessionWithoutFallingThroughToRetiredProxy() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        ControlAiAssistProjectController controller = new ControlAiAssistProjectController(client);
        when(client.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "name", "Orders",
                "projectCode", "orders",
                "aiCodingAccess", Map.of("enabled", true, "accessKey", "aic_test")
        ));

        ResponseEntity<ControlAiAssistProjectController.AiAccessSessionView> response =
                controller.startAccessSession(7L, "Codex");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(7L, response.getBody().projectId());
        assertEquals("orders", response.getBody().projectCode());
        assertEquals("SDK_ACCESS", response.getBody().scenario());
        assertEquals(6, response.getBody().totalSteps());
    }

    @Test
    void runsSdkAccessChecksWithoutFallingThroughToRetiredProxy() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        ControlAiAssistProjectController controller = new ControlAiAssistProjectController(client);
        when(client.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "name", "Orders",
                "projectCode", "orders",
                "registryCredentialConfigured", true,
                "aiCodingAccess", Map.of("enabled", true, "accessKey", "aic_test")
        ));

        ResponseEntity<ControlAiAssistProjectController.AiAccessCheckRunResponse> response =
                controller.runAccessSessionChecks(7L, "sdk-access-7", Map.of("args", Map.of()));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(7L, response.getBody().checkResult().projectId());
        assertEquals("orders", response.getBody().checkResult().projectCode());
        assertEquals("PASS", response.getBody().checkResult().overallStatus());
        assertEquals("PASS", response.getBody().session().status());
    }

    @Test
    void exposesPageAssistantOnboardingManifestFromControlService() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        ControlAiAssistProjectController controller = new ControlAiAssistProjectController(client);
        when(client.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "name", "Orders",
                "projectCode", "orders",
                "projectKind", "REGISTERED",
                "environment", "dev",
                "baseUrl", "http://localhost:18080",
                "registryAppKey", "app-orders",
                "registryCredentialConfigured", true,
                "aiCodingAccess", Map.of("enabled", true, "accessKey", "aic_test")
        ));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/ai-assist/projects/7/page-assistant/onboarding-manifest");
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(18603);

        ResponseEntity<ControlAiAssistProjectController.PageAssistantOnboardingManifestResponse> response =
                controller.pageAssistantOnboardingManifest(7L, "Codex", "orders.list", "/orders", null, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("reachai.page-assistant.onboarding.v1", response.getBody().schema());
        assertEquals("orders.list", response.getBody().target().pageKey());
        assertEquals("PAGE_ASSISTANT", response.getBody().session().scenario());
        assertEquals("http://localhost:18603/api/ai-assist/projects/7/page-assistant/onboarding-manifest",
                response.getBody().endpoints().manifestUrl());
        assertEquals("X-ReachAI-AiCoding-Key", response.getBody().auth().headerName());
    }

    @Test
    void runsPageAssistantChecksWithoutFallingThroughToRetiredProxy() {
        CapabilityProjectOnboardingClient client = mock(CapabilityProjectOnboardingClient.class);
        ControlAiAssistProjectController controller = new ControlAiAssistProjectController(client);
        when(client.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "name", "Orders",
                "projectCode", "orders",
                "aiCodingAccess", Map.of("enabled", true, "accessKey", "aic_test")
        ));

        ResponseEntity<ControlAiAssistProjectController.PageAssistantCheckRunResponse> response =
                controller.runPageAssistantChecks(7L, "page-assistant-7", Map.of(
                        "pageKey", "orders.list",
                        "routePattern", "/orders"
                ));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("PASS", response.getBody().checkResult().overallStatus());
        assertEquals("orders.list", response.getBody().session().targetPageKey());
    }
}

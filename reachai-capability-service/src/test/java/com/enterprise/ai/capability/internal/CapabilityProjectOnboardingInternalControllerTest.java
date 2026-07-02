package com.enterprise.ai.capability.internal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityProjectOnboardingInternalControllerTest {

    @Test
    void returnsOnboardingProjectDetailsForControlService() {
        CapabilityProjectOnboardingInternalService service = mock(CapabilityProjectOnboardingInternalService.class);
        CapabilityProjectOnboardingInternalController controller =
                new CapabilityProjectOnboardingInternalController(service);
        Map<String, Object> project = Map.of(
                "id", 7L,
                "projectCode", "orders",
                "aiCodingAccess", Map.of("enabled", true, "accessKey", "aic_test")
        );
        when(service.getOnboardingProjectById(7L)).thenReturn(project);

        ResponseEntity<Map<String, Object>> response = controller.getOnboardingProjectById(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(project, response.getBody());
        verify(service).getOnboardingProjectById(7L);
    }

    @Test
    void updatesAiCodingAccessForControlService() {
        CapabilityProjectOnboardingInternalService service = mock(CapabilityProjectOnboardingInternalService.class);
        CapabilityProjectOnboardingInternalController controller =
                new CapabilityProjectOnboardingInternalController(service);
        CapabilityProjectOnboardingInternalController.AiCodingAccessUpdateRequest request =
                new CapabilityProjectOnboardingInternalController.AiCodingAccessUpdateRequest(true, "aic_manual");
        when(service.updateAiCodingAccess(7L, true, "aic_manual"))
                .thenReturn(Map.of("enabled", true, "accessKey", "aic_manual"));

        ResponseEntity<Map<String, Object>> response = controller.updateAiCodingAccess(7L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().get("enabled"));
        assertEquals("aic_manual", response.getBody().get("accessKey"));
        verify(service).updateAiCodingAccess(7L, true, "aic_manual");
    }

    @Test
    void returnsNotFoundWhenOnboardingProjectMissing() {
        CapabilityProjectOnboardingInternalService service = mock(CapabilityProjectOnboardingInternalService.class);
        CapabilityProjectOnboardingInternalController controller =
                new CapabilityProjectOnboardingInternalController(service);
        when(service.getOnboardingProjectById(404L)).thenThrow(new IllegalArgumentException("Project not found: 404"));

        ResponseEntity<Map<String, Object>> response = controller.getOnboardingProjectById(404L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("CAPABILITY_PROJECT_NOT_FOUND", response.getBody().get("code"));
        assertEquals(404L, response.getBody().get("projectId"));
    }
}

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

class CapabilityProjectInternalControllerTest {

    @Test
    void returnsProjectSummaryForRuntimeService() {
        CapabilityProjectLookupService lookupService = mock(CapabilityProjectLookupService.class);
        CapabilityProjectInternalController controller = new CapabilityProjectInternalController(lookupService);
        Map<String, Object> project = Map.of(
                "projectId", 7L,
                "projectCode", "orders"
        );
        when(lookupService.getProject("orders")).thenReturn(project);

        ResponseEntity<Map<String, Object>> response = controller.getProject("orders");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(project, response.getBody());
        verify(lookupService).getProject("orders");
    }

    @Test
    void returnsProjectSummaryByIdForRuntimeService() {
        CapabilityProjectLookupService lookupService = mock(CapabilityProjectLookupService.class);
        CapabilityProjectInternalController controller = new CapabilityProjectInternalController(lookupService);
        Map<String, Object> project = Map.of(
                "projectId", 7L,
                "projectCode", "orders"
        );
        when(lookupService.getProjectById(7L)).thenReturn(project);

        ResponseEntity<Map<String, Object>> response = controller.getProjectById(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(project, response.getBody());
        verify(lookupService).getProjectById(7L);
    }

    @Test
    void returnsNotFoundWhenProjectDoesNotExist() {
        CapabilityProjectLookupService lookupService = mock(CapabilityProjectLookupService.class);
        CapabilityProjectInternalController controller = new CapabilityProjectInternalController(lookupService);
        when(lookupService.getProject("missing")).thenThrow(new IllegalArgumentException("Project not found: missing"));

        ResponseEntity<Map<String, Object>> response = controller.getProject("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("reachai-capability-service", body.get("service"));
        assertEquals("CAPABILITY_PROJECT_NOT_FOUND", body.get("code"));
        assertEquals("missing", body.get("projectCode"));
    }

    @Test
    void returnsNotFoundWhenProjectIdDoesNotExist() {
        CapabilityProjectLookupService lookupService = mock(CapabilityProjectLookupService.class);
        CapabilityProjectInternalController controller = new CapabilityProjectInternalController(lookupService);
        when(lookupService.getProjectById(404L)).thenThrow(new IllegalArgumentException("Project not found: 404"));

        ResponseEntity<Map<String, Object>> response = controller.getProjectById(404L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("reachai-capability-service", body.get("service"));
        assertEquals("CAPABILITY_PROJECT_NOT_FOUND", body.get("code"));
        assertEquals(404L, body.get("projectId"));
    }
}

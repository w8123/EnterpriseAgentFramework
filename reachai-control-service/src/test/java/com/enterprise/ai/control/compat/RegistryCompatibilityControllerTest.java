package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistryCompatibilityControllerTest {

    @Test
    void keepsPublicRegistryRouteShapeOnControlService() throws Exception {
        RequestMapping controllerMapping = RegistryCompatibilityController.class.getAnnotation(RequestMapping.class);
        Method registerProject = RegistryCompatibilityController.class.getDeclaredMethod("registerProject", Map.class);
        Method listInstances = RegistryCompatibilityController.class.getDeclaredMethod("listInstances", String.class);

        assertArrayEquals(new String[] {"/api/registry/projects"}, controllerMapping.value());
        assertArrayEquals(new String[] {"/register"}, registerProject.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{projectCode}/instances"}, listInstances.getAnnotation(GetMapping.class).value());
    }

    @Test
    void delegatesProjectRegistrationToCapabilityService() {
        CapabilityProxyClient capabilityProxyClient = mock(CapabilityProxyClient.class);
        RegistryCompatibilityController controller = new RegistryCompatibilityController(capabilityProxyClient);
        Map<String, Object> request = Map.of("projectCode", "demo");
        ResponseEntity<Map<String, Object>> delegated = ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("projectCode", "demo", "status", "QUEUED"));
        when(capabilityProxyClient.registerProject(request)).thenReturn(delegated);

        ResponseEntity<Map<String, Object>> response = controller.registerProject(request);

        assertEquals(delegated, response);
        verify(capabilityProxyClient).registerProject(request);
    }

    @Test
    void delegatesInstanceListingToCapabilityService() {
        CapabilityProxyClient capabilityProxyClient = mock(CapabilityProxyClient.class);
        RegistryCompatibilityController controller = new RegistryCompatibilityController(capabilityProxyClient);
        ResponseEntity<List<Map<String, Object>>> delegated = ResponseEntity.ok(List.of(
                Map.of("projectCode", "demo", "instanceId", "instance-1")
        ));
        when(capabilityProxyClient.listInstances("demo")).thenReturn(delegated);

        ResponseEntity<List<Map<String, Object>>> response = controller.listInstances("demo");

        assertEquals(delegated, response);
        verify(capabilityProxyClient).listInstances("demo");
    }
}

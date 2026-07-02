package com.enterprise.ai.capability.registry;

import com.enterprise.ai.agent.registry.RegistryContracts.ProjectRegisterRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.RegistryProjectResponse;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityRegistryCompatibilityControllerTest {

    @Test
    void keepsSdkRegistryRouteShapeForControlDelegation() throws Exception {
        RequestMapping controllerMapping =
                CapabilityRegistryCompatibilityController.class.getAnnotation(RequestMapping.class);
        Method registerProject = CapabilityRegistryCompatibilityController.class
                .getDeclaredMethod("registerProject", ProjectRegisterRequest.class);
        Method listInstances = CapabilityRegistryCompatibilityController.class
                .getDeclaredMethod("listInstances", String.class);

        assertArrayEquals(new String[] {"/api/registry/projects"}, controllerMapping.value());
        assertArrayEquals(new String[] {"/register"}, registerProject.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/{projectCode}/instances"}, listInstances.getAnnotation(GetMapping.class).value());
    }

    @Test
    void registerProjectRouteDelegatesToCapabilityRegistryService() {
        CapabilityRegistryService registryService = mock(CapabilityRegistryService.class);
        CapabilityRegistryCompatibilityController controller =
                new CapabilityRegistryCompatibilityController(registryService);
        ProjectRegisterRequest request = new ProjectRegisterRequest(
                "demo",
                "Demo",
                "dev",
                "owner",
                "PRIVATE",
                "http://localhost:8080",
                "/demo",
                "app-key",
                "app-secret",
                List.of("http://localhost:5173"),
                List.of("agent-1"),
                120,
                Map.of("source", "test")
        );
        RegistryProjectResponse delegated = new RegistryProjectResponse(1L, "demo", "Demo", "dev", "PRIVATE");
        when(registryService.registerProject(request)).thenReturn(delegated);

        ResponseEntity<?> response = controller.registerProject(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(delegated, response.getBody());
        verify(registryService).registerProject(request);
    }

    @Test
    void listInstancesRouteDelegatesToCapabilityRegistryService() {
        CapabilityRegistryService registryService = mock(CapabilityRegistryService.class);
        CapabilityRegistryCompatibilityController controller =
                new CapabilityRegistryCompatibilityController(registryService);
        when(registryService.listInstances("demo")).thenReturn(List.of());

        ResponseEntity<?> response = controller.listInstances("demo");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(), response.getBody());
        verify(registryService).listInstances("demo");
    }
}

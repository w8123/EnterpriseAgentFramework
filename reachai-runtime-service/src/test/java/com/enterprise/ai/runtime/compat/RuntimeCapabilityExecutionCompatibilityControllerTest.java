package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.execution.RuntimeCompositionExecutionService;
import com.enterprise.ai.runtime.execution.RuntimeInteractionResumeService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeCapabilityExecutionCompatibilityControllerTest {

    @Test
    void keepsPublicCapabilityRuntimeRouteShapeOnRuntimeService() throws Exception {
        Method executeTool = RuntimeCapabilityExecutionCompatibilityController.class
                .getDeclaredMethod("executeTool", String.class, Map.class);
        Method executeComposition = RuntimeCapabilityExecutionCompatibilityController.class
                .getDeclaredMethod("executeComposition", String.class, Map.class);
        Method resumeInteraction = RuntimeCapabilityExecutionCompatibilityController.class
                .getDeclaredMethod("resumeInteraction", String.class, Map.class);

        assertArrayEquals(new String[] {
                "/api/runtime/tools/{qualifiedName}/execute"
        }, executeTool.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {
                "/api/runtime/compositions/{qualifiedName}/execute"
        }, executeComposition.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {
                "/api/runtime/interactions/{sessionId}/resume"
        }, resumeInteraction.getAnnotation(PostMapping.class).value());
    }

    @Test
    void delegatesToolExecutionToCapabilityService() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeCompositionExecutionService compositionExecutionService = mock(RuntimeCompositionExecutionService.class);
        RuntimeInteractionResumeService interactionResumeService = mock(RuntimeInteractionResumeService.class);
        RuntimeCapabilityExecutionCompatibilityController controller =
                new RuntimeCapabilityExecutionCompatibilityController(capabilityClient, compositionExecutionService,
                        interactionResumeService);
        Map<String, Object> request = Map.of("input", Map.of("message", "hello"));
        Map<String, Object> expected = Map.of("success", true, "data", Map.of("echo", "hello"));
        when(capabilityClient.executeTool("system.echo", request)).thenReturn(expected);

        ResponseEntity<Map<String, Object>> response = controller.executeTool("system.echo", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(capabilityClient).executeTool("system.echo", request);
    }

    @Test
    void delegatesCompositionExecutionToRuntimeService() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeCompositionExecutionService compositionExecutionService = mock(RuntimeCompositionExecutionService.class);
        RuntimeInteractionResumeService interactionResumeService = mock(RuntimeInteractionResumeService.class);
        RuntimeCapabilityExecutionCompatibilityController controller =
                new RuntimeCapabilityExecutionCompatibilityController(capabilityClient, compositionExecutionService,
                        interactionResumeService);
        Map<String, Object> request = Map.of("message", "hello");
        Map<String, Object> expected = Map.of("success", true, "code", "RUNTIME_GRAPH_EXECUTED", "answer", "ok");
        when(compositionExecutionService.execute("system.echo", request)).thenReturn(expected);

        ResponseEntity<Map<String, Object>> response = controller.executeComposition("system.echo", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(compositionExecutionService).execute("system.echo", request);
    }

    @Test
    void delegatesInteractionResumeToRuntimeService() {
        RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeCompositionExecutionService compositionExecutionService = mock(RuntimeCompositionExecutionService.class);
        RuntimeInteractionResumeService interactionResumeService = mock(RuntimeInteractionResumeService.class);
        RuntimeCapabilityExecutionCompatibilityController controller =
                new RuntimeCapabilityExecutionCompatibilityController(capabilityClient, compositionExecutionService,
                        interactionResumeService);
        Map<String, Object> request = Map.of("values", Map.of("approved", true));
        Map<String, Object> expected = Map.of("success", true, "code", "RUNTIME_GRAPH_EXECUTED", "answer", "ok");
        when(interactionResumeService.resume("session-1", request)).thenReturn(expected);

        ResponseEntity<Map<String, Object>> response = controller.resumeInteraction("session-1", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(interactionResumeService).resume("session-1", request);
    }

    private static final class NoopCapabilityClient implements RuntimeCapabilityCatalogClient {
        @Override
        public Map<String, Object> getToolDefinition(String qualifiedName) {
            return Map.of();
        }

        @Override
        public Map<String, Object> executeTool(String qualifiedName, Map<String, Object> request) {
            return Map.of();
        }

        @Override
        public Map<String, Object> getCompositionDefinition(String qualifiedName) {
            return Map.of();
        }

        @Override
        public Map<String, Object> getProject(String projectCode) {
            return Map.of();
        }

        @Override
        public Map<String, Object> getProjectById(Long projectId) {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> listRuntimeInstances() {
            return List.of();
        }
    }
}

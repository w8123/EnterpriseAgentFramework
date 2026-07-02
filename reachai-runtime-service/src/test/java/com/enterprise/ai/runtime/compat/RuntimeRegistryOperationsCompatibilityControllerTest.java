package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphSyncRequest;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphSyncResponse;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeRegistryOperationsCompatibilityControllerTest {

    @Test
    void keepsAgentGraphSyncCompatibilityRouteShapeOnRuntimeService() throws Exception {
        RequestMapping controllerMapping =
                RuntimeRegistryOperationsCompatibilityController.class.getAnnotation(RequestMapping.class);
        Method syncAgentGraphs = RuntimeRegistryOperationsCompatibilityController.class
                .getDeclaredMethod("syncAgentGraphs", String.class, AgentGraphSyncRequest.class);

        assertArrayEquals(new String[] {"/api/registry"}, controllerMapping.value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/agent-graphs/sync"},
                syncAgentGraphs.getAnnotation(PostMapping.class).value());
    }

    @Test
    void delegatesAgentGraphSyncToRuntimeService() {
        RuntimeAgentGraphSyncService syncService = mock(RuntimeAgentGraphSyncService.class);
        RuntimeRegistryOperationsCompatibilityController controller =
                new RuntimeRegistryOperationsCompatibilityController(syncService);
        AgentGraphSyncRequest request = new AgentGraphSyncRequest("sync-1", "SDK", true, List.of());
        AgentGraphSyncResponse delegated = new AgentGraphSyncResponse(
                "sync-1",
                7L,
                "orders",
                0,
                0,
                0,
                List.of()
        );
        when(syncService.sync("orders", request)).thenReturn(delegated);

        ResponseEntity<?> response = controller.syncAgentGraphs("orders", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(delegated, response.getBody());
        verify(syncService).sync("orders", request);
    }

    @Test
    void returnsBadRequestForInvalidAgentGraphSyncRequest() {
        RuntimeAgentGraphSyncService syncService = mock(RuntimeAgentGraphSyncService.class);
        RuntimeRegistryOperationsCompatibilityController controller =
                new RuntimeRegistryOperationsCompatibilityController(syncService);
        AgentGraphSyncRequest request = new AgentGraphSyncRequest("sync-1", "SDK", true, List.of());
        when(syncService.sync("orders", request)).thenThrow(new IllegalArgumentException("SDK Agent Graph 缺少 graphSpec"));

        ResponseEntity<?> response = controller.syncAgentGraphs("orders", request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(new RuntimeRegistryOperationsCompatibilityController.ApiErrorResponse(
                "SDK Agent Graph 缺少 graphSpec"
        ), response.getBody());
    }
}

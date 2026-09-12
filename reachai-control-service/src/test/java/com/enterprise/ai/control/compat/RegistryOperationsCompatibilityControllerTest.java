package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistryOperationsCompatibilityControllerTest {

    @Test
    void keepsSdkRegistryOperationRouteShapeOnControlService() throws Exception {
        RequestMapping controllerMapping =
                RegistryOperationsCompatibilityController.class.getAnnotation(RequestMapping.class);
        Method heartbeat = RegistryOperationsCompatibilityController.class
                .getDeclaredMethod("heartbeat", String.class, String.class, String.class,
                        String.class, String.class, Map.class);
        Method syncCapabilities = RegistryOperationsCompatibilityController.class
                .getDeclaredMethod("syncCapabilities", String.class, String.class, String.class,
                        String.class, String.class, Map.class);
        Method syncAgentGraphs = RegistryOperationsCompatibilityController.class
                .getDeclaredMethod("syncAgentGraphs", String.class, Map.class);

        assertArrayEquals(new String[] {"/api/registry"}, controllerMapping.value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/instances/heartbeat"},
                heartbeat.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/capabilities/sync"},
                syncCapabilities.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/agent-graphs/sync"},
                syncAgentGraphs.getAnnotation(PostMapping.class).value());
    }

    @Test
    void delegatesSdkRegistryOperationsToCapabilityService() {
        CapabilityProxyClient capabilityProxyClient = mock(CapabilityProxyClient.class);
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RegistryOperationsCompatibilityController controller =
                new RegistryOperationsCompatibilityController(capabilityProxyClient, runtimeProxyClient);
        Map<String, Object> request = Map.of("syncId", "sync-1");
        ResponseEntity<Object> delegated = ResponseEntity.ok(Map.of("syncId", "sync-1"));

        when(capabilityProxyClient.heartbeat(
                "orders", "key", "123", "nonce", "signature", request)).thenReturn(delegated);
        when(capabilityProxyClient.syncCapabilities(
                "orders", "key", "123", "nonce", "signature", request)).thenReturn(delegated);

        assertEquals(delegated, controller.heartbeat(
                "orders", "key", "123", "nonce", "signature", request));
        assertEquals(delegated, controller.syncCapabilities(
                "orders", "key", "123", "nonce", "signature", request));

        verify(capabilityProxyClient).heartbeat(
                "orders", "key", "123", "nonce", "signature", request);
        verify(capabilityProxyClient).syncCapabilities(
                "orders", "key", "123", "nonce", "signature", request);
    }

    @Test
    void delegatesAgentGraphSyncToRuntimeService() {
        CapabilityProxyClient capabilityProxyClient = mock(CapabilityProxyClient.class);
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RegistryOperationsCompatibilityController controller =
                new RegistryOperationsCompatibilityController(capabilityProxyClient, runtimeProxyClient);
        Map<String, Object> request = Map.of("syncId", "sync-1");
        ResponseEntity<Object> delegated = ResponseEntity.ok(Map.of("syncId", "sync-1"));

        when(runtimeProxyClient.syncAgentGraphs("orders", request)).thenReturn(delegated);

        assertEquals(delegated, controller.syncAgentGraphs("orders", request));

        verify(runtimeProxyClient).syncAgentGraphs("orders", request);
    }

}

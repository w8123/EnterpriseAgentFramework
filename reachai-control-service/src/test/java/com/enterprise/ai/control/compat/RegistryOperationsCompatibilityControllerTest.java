package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import org.junit.jupiter.api.Test;
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

class RegistryOperationsCompatibilityControllerTest {

    @Test
    void keepsSdkRegistryOperationRouteShapeOnControlService() throws Exception {
        RequestMapping controllerMapping =
                RegistryOperationsCompatibilityController.class.getAnnotation(RequestMapping.class);
        Method heartbeat = RegistryOperationsCompatibilityController.class
                .getDeclaredMethod("heartbeat", String.class, Map.class);
        Method syncCapabilities = RegistryOperationsCompatibilityController.class
                .getDeclaredMethod("syncCapabilities", String.class, Map.class);
        Method diffCapabilities = RegistryOperationsCompatibilityController.class
                .getDeclaredMethod("diffCapabilities", String.class, Map.class);
        Method applyCapabilities = RegistryOperationsCompatibilityController.class
                .getDeclaredMethod("applyCapabilities", String.class, Map.class);
        Method syncAgentGraphs = RegistryOperationsCompatibilityController.class
                .getDeclaredMethod("syncAgentGraphs", String.class, Map.class);
        Method listSnapshots = RegistryOperationsCompatibilityController.class
                .getDeclaredMethod("listCapabilitySnapshots", String.class);
        Method listDiffItems = RegistryOperationsCompatibilityController.class
                .getDeclaredMethod("listCapabilityDiffItems", Long.class);
        Method reviewDiffItem = RegistryOperationsCompatibilityController.class
                .getDeclaredMethod("reviewCapabilityDiffItem", Long.class, Map.class);

        assertArrayEquals(new String[] {"/api/registry"}, controllerMapping.value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/instances/heartbeat"},
                heartbeat.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/capabilities/sync"},
                syncCapabilities.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/capabilities/diff"},
                diffCapabilities.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/capabilities/apply"},
                applyCapabilities.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/agent-graphs/sync"},
                syncAgentGraphs.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/capability-snapshots"},
                listSnapshots.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/capability-snapshots/{snapshotId}/diff-items"},
                listDiffItems.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/capability-diff-items/{diffItemId}/review"},
                reviewDiffItem.getAnnotation(PostMapping.class).value());
    }

    @Test
    void delegatesSdkRegistryOperationsToCapabilityService() {
        CapabilityProxyClient capabilityProxyClient = mock(CapabilityProxyClient.class);
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RegistryOperationsCompatibilityController controller =
                new RegistryOperationsCompatibilityController(capabilityProxyClient, runtimeProxyClient);
        Map<String, Object> request = Map.of("syncId", "sync-1");
        ResponseEntity<Object> delegated = ResponseEntity.ok(Map.of("syncId", "sync-1"));

        when(capabilityProxyClient.heartbeat("orders", request)).thenReturn(delegated);
        when(capabilityProxyClient.syncCapabilities("orders", request)).thenReturn(delegated);
        when(capabilityProxyClient.diffCapabilities("orders", request)).thenReturn(delegated);
        when(capabilityProxyClient.applyCapabilities("orders", request)).thenReturn(delegated);

        assertEquals(delegated, controller.heartbeat("orders", request));
        assertEquals(delegated, controller.syncCapabilities("orders", request));
        assertEquals(delegated, controller.diffCapabilities("orders", request));
        assertEquals(delegated, controller.applyCapabilities("orders", request));

        verify(capabilityProxyClient).heartbeat("orders", request);
        verify(capabilityProxyClient).syncCapabilities("orders", request);
        verify(capabilityProxyClient).diffCapabilities("orders", request);
        verify(capabilityProxyClient).applyCapabilities("orders", request);
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

    @Test
    void delegatesSdkRegistryReviewReadsToCapabilityService() {
        CapabilityProxyClient capabilityProxyClient = mock(CapabilityProxyClient.class);
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RegistryOperationsCompatibilityController controller =
                new RegistryOperationsCompatibilityController(capabilityProxyClient, runtimeProxyClient);
        ResponseEntity<Object> snapshots = ResponseEntity.ok(List.of(Map.of("id", 1L)));
        ResponseEntity<Object> diffItems = ResponseEntity.ok(List.of(Map.of("id", 12L)));
        ResponseEntity<Object> review = ResponseEntity.ok(Map.of("id", 12L, "reviewStatus", "APPLIED"));
        Map<String, Object> reviewRequest = Map.of("action", "APPLY");

        when(capabilityProxyClient.listCapabilitySnapshots("orders")).thenReturn(snapshots);
        when(capabilityProxyClient.listCapabilityDiffItems(1L)).thenReturn(diffItems);
        when(capabilityProxyClient.reviewCapabilityDiffItem(12L, reviewRequest)).thenReturn(review);

        assertEquals(snapshots, controller.listCapabilitySnapshots("orders"));
        assertEquals(diffItems, controller.listCapabilityDiffItems(1L));
        assertEquals(review, controller.reviewCapabilityDiffItem(12L, reviewRequest));

        verify(capabilityProxyClient).listCapabilitySnapshots("orders");
        verify(capabilityProxyClient).listCapabilityDiffItems(1L);
        verify(capabilityProxyClient).reviewCapabilityDiffItem(12L, reviewRequest);
    }
}

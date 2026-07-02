package com.enterprise.ai.capability.registry;

import com.enterprise.ai.agent.registry.RegistryContracts.InstanceHeartbeatRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.InstanceHeartbeatResponse;
import com.enterprise.ai.agent.registry.RegistryContracts.RuntimeGovernancePolicy;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityDiffItemDTO;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityReviewRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySnapshotDTO;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySyncRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySyncResponse;
import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.RegistryContracts.RuntimeGovernancePolicyUpdateRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.SdkCapabilityDescriptionSettings;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityRegistryOperationsCompatibilityControllerTest {

    @Test
    void keepsSdkRegistryOperationRouteShapeOnCapabilityService() throws Exception {
        RequestMapping controllerMapping =
                CapabilityRegistryOperationsCompatibilityController.class.getAnnotation(RequestMapping.class);
        Method heartbeat = CapabilityRegistryOperationsCompatibilityController.class
                .getDeclaredMethod("heartbeat", String.class, InstanceHeartbeatRequest.class);
        Method getSdkCapabilityDescriptionSettings = CapabilityRegistryOperationsCompatibilityController.class
                .getDeclaredMethod("getSdkCapabilityDescriptionSettings", String.class);
        Method offline = CapabilityRegistryOperationsCompatibilityController.class
                .getDeclaredMethod("offline", String.class,
                        CapabilityRegistryOperationsCompatibilityController.InstanceOfflineRequest.class);
        Method syncCapabilities = CapabilityRegistryOperationsCompatibilityController.class
                .getDeclaredMethod("syncCapabilities", String.class, CapabilitySyncRequest.class);
        Method diffCapabilities = CapabilityRegistryOperationsCompatibilityController.class
                .getDeclaredMethod("diffCapabilities", String.class, CapabilitySyncRequest.class);
        Method applyCapabilities = CapabilityRegistryOperationsCompatibilityController.class
                .getDeclaredMethod("applyCapabilities", String.class, CapabilitySyncRequest.class);
        Method listSnapshots = CapabilityRegistryOperationsCompatibilityController.class
                .getDeclaredMethod("listCapabilitySnapshots", String.class);
        Method listDiffItems = CapabilityRegistryOperationsCompatibilityController.class
                .getDeclaredMethod("listCapabilityDiffItems", Long.class);
        Method reviewDiffItem = CapabilityRegistryOperationsCompatibilityController.class
                .getDeclaredMethod("reviewCapabilityDiffItem", Long.class, CapabilityReviewRequest.class);
        Method purgeOfflineInstances = CapabilityRegistryOperationsCompatibilityController.class
                .getDeclaredMethod("purgeOfflineInstances", String.class,
                        CapabilityRegistryOperationsCompatibilityController.PurgeOfflineRequest.class);
        Method updateInstanceStatus = CapabilityRegistryOperationsCompatibilityController.class
                .getDeclaredMethod("updateInstanceStatus", String.class,
                        CapabilityRegistryOperationsCompatibilityController.InstanceStatusRequest.class);
        Method updateInstanceGovernancePolicy = CapabilityRegistryOperationsCompatibilityController.class
                .getDeclaredMethod("updateInstanceGovernancePolicy", String.class,
                        RuntimeGovernancePolicyUpdateRequest.class);

        assertArrayEquals(new String[] {"/api/registry"}, controllerMapping.value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/instances/heartbeat"},
                heartbeat.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/capability-description-settings"},
                getSdkCapabilityDescriptionSettings.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/instances/offline"},
                offline.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/capabilities/sync"},
                syncCapabilities.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/capabilities/diff"},
                diffCapabilities.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/capabilities/apply"},
                applyCapabilities.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/capability-snapshots"},
                listSnapshots.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/capability-snapshots/{snapshotId}/diff-items"},
                listDiffItems.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/capability-diff-items/{diffItemId}/review"},
                reviewDiffItem.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/instances/purge-offline"},
                purgeOfflineInstances.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/instances/status"},
                updateInstanceStatus.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/instances/governance-policy"},
                updateInstanceGovernancePolicy.getAnnotation(PostMapping.class).value());
    }

    @Test
    void sdkDescriptionSettingsRouteDelegatesToCapabilityRegistryService() {
        CapabilityRegistryService registryService = mock(CapabilityRegistryService.class);
        CapabilityRegistryOperationsCompatibilityController controller =
                new CapabilityRegistryOperationsCompatibilityController(registryService);
        SdkCapabilityDescriptionSettings delegated = new SdkCapabilityDescriptionSettings(
                List.of("SWAGGER_API_OPERATION", "METHOD_NAME"),
                List.of("SCHEMA_ANNO", "FIELD_NAME"),
                Map.of("SWAGGER_API_OPERATION", true, "METHOD_NAME", true),
                Map.of("SCHEMA_ANNO", true, "FIELD_NAME", true)
        );
        when(registryService.getSdkCapabilityDescriptionSettings("orders")).thenReturn(delegated);

        ResponseEntity<?> response = controller.getSdkCapabilityDescriptionSettings("orders");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(delegated, response.getBody());
        verify(registryService).getSdkCapabilityDescriptionSettings("orders");
    }

    @Test
    void offlineRouteDelegatesToCapabilityRegistryService() {
        CapabilityRegistryService registryService = mock(CapabilityRegistryService.class);
        CapabilityRegistryOperationsCompatibilityController controller =
                new CapabilityRegistryOperationsCompatibilityController(registryService);
        CapabilityRegistryOperationsCompatibilityController.InstanceOfflineRequest request =
                new CapabilityRegistryOperationsCompatibilityController.InstanceOfflineRequest("dev-1");

        ResponseEntity<?> response = controller.offline("orders", request);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(registryService).offline("orders", "dev-1");
    }

    @Test
    void heartbeatRouteDelegatesToCapabilityRegistryService() {
        CapabilityRegistryService registryService = mock(CapabilityRegistryService.class);
        CapabilityRegistryOperationsCompatibilityController controller =
                new CapabilityRegistryOperationsCompatibilityController(registryService);
        InstanceHeartbeatRequest request = new InstanceHeartbeatRequest(
                "dev-1",
                "http://orders.local",
                "orders-host",
                8080,
                "1.0.0",
                "0.3.0",
                Map.of("zone", "dev")
        );
        ProjectInstanceEntity instance = new ProjectInstanceEntity();
        instance.setProjectCode("orders");
        instance.setInstanceId("dev-1");
        InstanceHeartbeatResponse delegated = new InstanceHeartbeatResponse(
                instance,
                new RuntimeGovernancePolicy(false, "ONLINE", null, true, true, "ok")
        );
        when(registryService.heartbeat("orders", request)).thenReturn(delegated);

        ResponseEntity<?> response = controller.heartbeat("orders", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(delegated, response.getBody());
        verify(registryService).heartbeat("orders", request);
    }

    @Test
    void diffRouteDelegatesToCapabilityRegistryService() {
        CapabilityRegistryService registryService = mock(CapabilityRegistryService.class);
        CapabilityRegistryOperationsCompatibilityController controller =
                new CapabilityRegistryOperationsCompatibilityController(registryService);
        CapabilitySyncRequest request = new CapabilitySyncRequest(
                "sync-1",
                "SDK",
                true,
                List.of()
        );
        CapabilitySyncResponse delegated = new CapabilitySyncResponse(
                "sync-1",
                7L,
                "orders",
                0,
                0,
                0,
                0,
                0,
                List.of()
        );
        when(registryService.diff("orders", request)).thenReturn(delegated);

        ResponseEntity<?> response = controller.diffCapabilities("orders", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(delegated, response.getBody());
        verify(registryService).diff("orders", request);
    }

    @Test
    void syncRouteDelegatesToCapabilityRegistryService() {
        CapabilityRegistryService registryService = mock(CapabilityRegistryService.class);
        CapabilityRegistryOperationsCompatibilityController controller =
                new CapabilityRegistryOperationsCompatibilityController(registryService);
        CapabilitySyncRequest request = new CapabilitySyncRequest("sync-1", "SDK", null, List.of());
        CapabilitySyncResponse delegated = new CapabilitySyncResponse(
                "sync-1",
                7L,
                "orders",
                0,
                0,
                0,
                0,
                0,
                List.of()
        );
        when(registryService.sync("orders", request)).thenReturn(delegated);

        ResponseEntity<?> response = controller.syncCapabilities("orders", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(delegated, response.getBody());
        verify(registryService).sync("orders", request);
    }

    @Test
    void applyRouteDelegatesToCapabilityRegistryService() {
        CapabilityRegistryService registryService = mock(CapabilityRegistryService.class);
        CapabilityRegistryOperationsCompatibilityController controller =
                new CapabilityRegistryOperationsCompatibilityController(registryService);
        CapabilitySyncRequest request = new CapabilitySyncRequest("sync-1", "SDK", false, List.of());
        CapabilitySyncResponse delegated = new CapabilitySyncResponse(
                "sync-1",
                7L,
                "orders",
                0,
                0,
                0,
                0,
                0,
                List.of()
        );
        when(registryService.apply("orders", request)).thenReturn(delegated);

        ResponseEntity<?> response = controller.applyCapabilities("orders", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(delegated, response.getBody());
        verify(registryService).apply("orders", request);
    }

    @Test
    void snapshotRouteDelegatesToCapabilityRegistryService() {
        CapabilityRegistryService registryService = mock(CapabilityRegistryService.class);
        CapabilityRegistryOperationsCompatibilityController controller =
                new CapabilityRegistryOperationsCompatibilityController(registryService);
        CapabilitySnapshotDTO snapshot = new CapabilitySnapshotDTO(
                21L,
                7L,
                "orders",
                "sync-1",
                "SDK",
                "PENDING",
                3,
                1,
                1,
                1,
                0,
                "2026-06-29T10:00",
                "2026-06-29T10:05"
        );
        when(registryService.listSnapshots("orders")).thenReturn(List.of(snapshot));

        ResponseEntity<?> response = controller.listCapabilitySnapshots("orders");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(snapshot), response.getBody());
        verify(registryService).listSnapshots("orders");
    }

    @Test
    void diffItemsRouteDelegatesToCapabilityRegistryService() {
        CapabilityRegistryService registryService = mock(CapabilityRegistryService.class);
        CapabilityRegistryOperationsCompatibilityController controller =
                new CapabilityRegistryOperationsCompatibilityController(registryService);
        CapabilityDiffItemDTO item = new CapabilityDiffItemDTO(
                31L,
                21L,
                "sync-1",
                "orders",
                "orders:createOrder",
                "createOrder",
                "orders_create_order",
                "ADDED",
                null,
                "[]",
                "{}",
                "PENDING",
                null
        );
        when(registryService.listDiffItems(21L)).thenReturn(List.of(item));

        ResponseEntity<?> response = controller.listCapabilityDiffItems(21L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(item), response.getBody());
        verify(registryService).listDiffItems(21L);
    }

    @Test
    void reviewRouteDelegatesToCapabilityRegistryService() {
        CapabilityRegistryService registryService = mock(CapabilityRegistryService.class);
        CapabilityRegistryOperationsCompatibilityController controller =
                new CapabilityRegistryOperationsCompatibilityController(registryService);
        CapabilityReviewRequest request = new CapabilityReviewRequest("IGNORE", "alice", "not ready");
        CapabilityDiffItemDTO reviewed = new CapabilityDiffItemDTO(
                31L,
                21L,
                "sync-1",
                "orders",
                "orders:createOrder",
                "createOrder",
                "orders_create_order",
                "ADDED",
                null,
                "[]",
                "{}",
                "IGNORED",
                "not ready"
        );
        when(registryService.reviewDiffItem(31L, request)).thenReturn(reviewed);

        ResponseEntity<?> response = controller.reviewCapabilityDiffItem(31L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(reviewed, response.getBody());
        verify(registryService).reviewDiffItem(31L, request);
    }

    @Test
    void reviewRouteReturnsBadRequestForInvalidReview() {
        CapabilityRegistryService registryService = mock(CapabilityRegistryService.class);
        CapabilityRegistryOperationsCompatibilityController controller =
                new CapabilityRegistryOperationsCompatibilityController(registryService);
        CapabilityReviewRequest request = new CapabilityReviewRequest("APPLY", "alice", null);
        when(registryService.reviewDiffItem(31L, request))
                .thenThrow(new IllegalArgumentException("Capability diff APPLY has not moved into reachai-capability-service yet"));

        ResponseEntity<?> response = controller.reviewCapabilityDiffItem(31L, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(new CapabilityRegistryOperationsCompatibilityController.ApiErrorResponse(
                "Capability diff APPLY has not moved into reachai-capability-service yet"
        ), response.getBody());
    }

    @Test
    void purgeOfflineRouteDelegatesToCapabilityRegistryService() {
        CapabilityRegistryService registryService = mock(CapabilityRegistryService.class);
        CapabilityRegistryOperationsCompatibilityController controller =
                new CapabilityRegistryOperationsCompatibilityController(registryService);
        CapabilityRegistryOperationsCompatibilityController.PurgeOfflineRequest request =
                new CapabilityRegistryOperationsCompatibilityController.PurgeOfflineRequest(30);
        when(registryService.purgeOfflineInstances("orders", 30)).thenReturn(2);

        ResponseEntity<?> response = controller.purgeOfflineInstances("orders", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(new CapabilityRegistryOperationsCompatibilityController.PurgeOfflineResponse(2), response.getBody());
        verify(registryService).purgeOfflineInstances("orders", 30);
    }

    @Test
    void statusRouteDelegatesToCapabilityRegistryService() {
        CapabilityRegistryService registryService = mock(CapabilityRegistryService.class);
        CapabilityRegistryOperationsCompatibilityController controller =
                new CapabilityRegistryOperationsCompatibilityController(registryService);
        CapabilityRegistryOperationsCompatibilityController.InstanceStatusRequest request =
                new CapabilityRegistryOperationsCompatibilityController.InstanceStatusRequest("dev-1", "disabled");
        ProjectInstanceEntity delegated = new ProjectInstanceEntity();
        delegated.setProjectCode("orders");
        delegated.setInstanceId("dev-1");
        delegated.setStatus("DISABLED");
        when(registryService.updateInstanceStatus("orders", "dev-1", "disabled")).thenReturn(delegated);

        ResponseEntity<?> response = controller.updateInstanceStatus("orders", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(delegated, response.getBody());
        verify(registryService).updateInstanceStatus("orders", "dev-1", "disabled");
    }

    @Test
    void governancePolicyRouteDelegatesToCapabilityRegistryService() {
        CapabilityRegistryService registryService = mock(CapabilityRegistryService.class);
        CapabilityRegistryOperationsCompatibilityController controller =
                new CapabilityRegistryOperationsCompatibilityController(registryService);
        RuntimeGovernancePolicyUpdateRequest request = new RuntimeGovernancePolicyUpdateRequest(
                "dev-1",
                true,
                "0.4.0",
                false,
                true,
                "paused"
        );
        ProjectInstanceEntity delegated = new ProjectInstanceEntity();
        delegated.setProjectCode("orders");
        delegated.setInstanceId("dev-1");
        delegated.setStatus("DISABLED");
        when(registryService.updateInstanceGovernancePolicy("orders", request)).thenReturn(delegated);

        ResponseEntity<?> response = controller.updateInstanceGovernancePolicy("orders", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(delegated, response.getBody());
        verify(registryService).updateInstanceGovernancePolicy("orders", request);
    }

    @Test
    void doesNotExposeRuntimeOwnedAgentGraphSyncRoute() {
        assertThrows(NoSuchMethodException.class, () -> CapabilityRegistryOperationsCompatibilityController.class
                .getDeclaredMethod("syncAgentGraphs", String.class, Map.class));
    }
}

package com.enterprise.ai.capability.registry;

import com.enterprise.ai.agent.registry.RegistryContracts.InstanceHeartbeatRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityReviewRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySyncRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.RuntimeGovernancePolicyUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/registry")
@RequiredArgsConstructor
public class CapabilityRegistryOperationsCompatibilityController {

    private final CapabilityRegistryService registryService;

    @PostMapping("/projects/{projectCode}/instances/heartbeat")
    public ResponseEntity<?> heartbeat(@PathVariable String projectCode,
                                       @RequestBody(required = false) InstanceHeartbeatRequest request) {
        try {
            return ResponseEntity.ok(registryService.heartbeat(projectCode, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/projects/{projectCode}/capability-description-settings")
    public ResponseEntity<?> getSdkCapabilityDescriptionSettings(@PathVariable String projectCode) {
        try {
            return ResponseEntity.ok(registryService.getSdkCapabilityDescriptionSettings(projectCode));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectCode}/instances/offline")
    public ResponseEntity<?> offline(@PathVariable String projectCode,
                                     @RequestBody(required = false) InstanceOfflineRequest request) {
        try {
            registryService.offline(projectCode, request == null ? null : request.instanceId());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectCode}/capabilities/sync")
    public ResponseEntity<?> syncCapabilities(@PathVariable String projectCode,
                                              @RequestBody(required = false) CapabilitySyncRequest request) {
        try {
            return ResponseEntity.ok(registryService.sync(projectCode, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectCode}/capabilities/diff")
    public ResponseEntity<?> diffCapabilities(@PathVariable String projectCode,
                                              @RequestBody(required = false) CapabilitySyncRequest request) {
        try {
            return ResponseEntity.ok(registryService.diff(projectCode, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectCode}/capabilities/apply")
    public ResponseEntity<?> applyCapabilities(@PathVariable String projectCode,
                                               @RequestBody(required = false) CapabilitySyncRequest request) {
        try {
            return ResponseEntity.ok(registryService.apply(projectCode, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/projects/{projectCode}/capability-snapshots")
    public ResponseEntity<?> listCapabilitySnapshots(@PathVariable String projectCode) {
        try {
            return ResponseEntity.ok(registryService.listSnapshots(projectCode));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/capability-snapshots/{snapshotId}/diff-items")
    public ResponseEntity<?> listCapabilityDiffItems(@PathVariable Long snapshotId) {
        return ResponseEntity.ok(registryService.listDiffItems(snapshotId));
    }

    @PostMapping("/capability-diff-items/{diffItemId}/review")
    public ResponseEntity<?> reviewCapabilityDiffItem(@PathVariable Long diffItemId,
                                                      @RequestBody(required = false) CapabilityReviewRequest request) {
        try {
            return ResponseEntity.ok(registryService.reviewDiffItem(diffItemId, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectCode}/instances/purge-offline")
    public ResponseEntity<?> purgeOfflineInstances(@PathVariable String projectCode,
                                                   @RequestBody(required = false) PurgeOfflineRequest request) {
        try {
            int minIdleMinutes = request == null || request.minIdleMinutes() == null
                    ? 0
                    : request.minIdleMinutes();
            return ResponseEntity.ok(new PurgeOfflineResponse(
                    registryService.purgeOfflineInstances(projectCode, minIdleMinutes)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectCode}/instances/status")
    public ResponseEntity<?> updateInstanceStatus(@PathVariable String projectCode,
                                                  @RequestBody(required = false) InstanceStatusRequest request) {
        try {
            return ResponseEntity.ok(registryService.updateInstanceStatus(
                    projectCode,
                    request == null ? null : request.instanceId(),
                    request == null ? null : request.status()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectCode}/instances/governance-policy")
    public ResponseEntity<?> updateInstanceGovernancePolicy(@PathVariable String projectCode,
                                                            @RequestBody(required = false)
                                                            RuntimeGovernancePolicyUpdateRequest request) {
        try {
            return ResponseEntity.ok(registryService.updateInstanceGovernancePolicy(projectCode, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    record InstanceOfflineRequest(String instanceId) {
    }

    record PurgeOfflineRequest(Integer minIdleMinutes) {
    }

    record PurgeOfflineResponse(int removed) {
    }

    record InstanceStatusRequest(String instanceId, String status) {
    }

    record ApiErrorResponse(String message) {
    }
}

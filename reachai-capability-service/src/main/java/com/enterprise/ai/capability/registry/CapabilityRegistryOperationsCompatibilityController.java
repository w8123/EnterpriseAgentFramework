package com.enterprise.ai.capability.registry;

import com.enterprise.ai.agent.registry.RegistryContracts.InstanceHeartbeatRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityReviewRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySyncRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/registry")
@RequiredArgsConstructor
public class CapabilityRegistryOperationsCompatibilityController {

    private final CapabilityRegistryService registryService;

    @GetMapping("/projects/{projectCode}/capability-changes")
    public ResponseEntity<?> listChanges(@PathVariable String projectCode,
                                         @RequestParam(defaultValue = "PENDING") String state,
                                         @RequestParam(defaultValue = "") String keyword,
                                         @RequestParam(defaultValue = "1") int current,
                                         @RequestParam(defaultValue = "20") int size) {
        try {
            return ResponseEntity.ok(registryService.listChanges(projectCode, state, keyword, current, size));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(invalid.getMessage()));
        }
    }

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
                                              @RequestHeader(value = "X-ReachAI-App-Key", required = false) String appKey,
                                              @RequestHeader(value = "X-ReachAI-Timestamp", required = false) String timestamp,
                                              @RequestHeader(value = "X-ReachAI-Nonce", required = false) String nonce,
                                              @RequestHeader(value = "X-ReachAI-Signature", required = false) String signature,
                                              @RequestBody(required = false) CapabilitySyncRequest request) {
        try {
            return ResponseEntity.ok(registryService.syncFromProject(
                    projectCode,
                    request,
                    new com.enterprise.ai.agent.registry.RegistrySecurityService.RegistrySignatureHeaders(
                            appKey, timestamp, nonce, signature)));
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

    @GetMapping("/projects/{projectCode}/capability-snapshots")
    public ResponseEntity<?> listCapabilitySnapshots(@PathVariable String projectCode) {
        try {
            return ResponseEntity.ok(registryService.listSnapshots(projectCode));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/projects/{projectCode}/capability-snapshots/{snapshotId}/diff-items")
    public ResponseEntity<?> listCapabilityDiffItems(@PathVariable String projectCode,
                                                     @PathVariable Long snapshotId) {
        try {
            return ResponseEntity.ok(registryService.listDiffItems(projectCode, snapshotId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectCode}/capability-diff-items/{diffItemId}/review")
    public ResponseEntity<?> reviewCapabilityDiffItem(@PathVariable String projectCode,
                                                      @PathVariable Long diffItemId,
                                                      @RequestBody(required = false) CapabilityReviewRequest request) {
        try {
            return ResponseEntity.ok(registryService.reviewDiffItem(projectCode, diffItemId, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectCode}/capability-diff-items/{diffItemId}/rollback")
    public ResponseEntity<?> rollbackCapabilityDiffItem(@PathVariable String projectCode,
                                                        @PathVariable Long diffItemId,
                                                        @RequestBody(required = false) CapabilityReviewRequest request) {
        try {
            return ResponseEntity.ok(registryService.rollbackDiffItem(projectCode, diffItemId, request));
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

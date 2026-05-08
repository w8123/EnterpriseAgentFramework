package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.registry.AiRegistryService;
import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityDiffItemDTO;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityReviewRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySnapshotDTO;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySyncRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySyncResponse;
import com.enterprise.ai.agent.registry.RegistryContracts.InstanceHeartbeatRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.ProjectRegisterRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.RegistryProjectResponse;
import com.enterprise.ai.agent.registry.RegistryContracts.SdkCapabilityDescriptionSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/registry")
@RequiredArgsConstructor
public class AiRegistryController {

    private final AiRegistryService registryService;
    private final RegistrySecurityService securityService;

    @PostMapping("/projects/register")
    public ResponseEntity<?> registerProject(@RequestBody ProjectRegisterRequest request,
                                             @RequestHeader(value = "X-EAF-App-Key", required = false) String appKey,
                                             @RequestHeader(value = "X-EAF-Timestamp", required = false) String timestamp,
                                             @RequestHeader(value = "X-EAF-Nonce", required = false) String nonce,
                                             @RequestHeader(value = "X-EAF-Signature", required = false) String signature) {
        try {
            if (request != null) {
                securityService.verifyIfConfigured(request.projectCode(),
                        new RegistrySecurityService.RegistrySignatureHeaders(appKey, timestamp, nonce, signature));
            }
            RegistryProjectResponse response = registryService.registerProject(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectCode}/instances/heartbeat")
    public ResponseEntity<?> heartbeat(@PathVariable String projectCode,
                                       @RequestBody InstanceHeartbeatRequest request,
                                       @RequestHeader(value = "X-EAF-App-Key", required = false) String appKey,
                                       @RequestHeader(value = "X-EAF-Timestamp", required = false) String timestamp,
                                       @RequestHeader(value = "X-EAF-Nonce", required = false) String nonce,
                                       @RequestHeader(value = "X-EAF-Signature", required = false) String signature) {
        try {
            securityService.verifyIfConfigured(projectCode,
                    new RegistrySecurityService.RegistrySignatureHeaders(appKey, timestamp, nonce, signature));
            return ResponseEntity.ok(registryService.heartbeat(projectCode, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/projects/{projectCode}/capability-description-settings")
    public ResponseEntity<?> getSdkCapabilityDescriptionSettings(@PathVariable String projectCode,
                                                                 @RequestHeader(value = "X-EAF-App-Key", required = false) String appKey,
                                                                 @RequestHeader(value = "X-EAF-Timestamp", required = false) String timestamp,
                                                                 @RequestHeader(value = "X-EAF-Nonce", required = false) String nonce,
                                                                 @RequestHeader(value = "X-EAF-Signature", required = false) String signature) {
        try {
            securityService.verifyIfConfigured(projectCode,
                    new RegistrySecurityService.RegistrySignatureHeaders(appKey, timestamp, nonce, signature));
            SdkCapabilityDescriptionSettings body = registryService.getSdkCapabilityDescriptionSettings(projectCode);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/projects/{projectCode}/instances")
    public ResponseEntity<?> listInstances(@PathVariable String projectCode) {
        try {
            List<ProjectInstanceEntity> instances = registryService.listInstances(projectCode);
            return ResponseEntity.ok(instances);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/projects/{projectCode}/instances/offline")
    public ResponseEntity<?> offline(@PathVariable String projectCode,
                                     @RequestBody InstanceOfflineRequest request,
                                     @RequestHeader(value = "X-EAF-App-Key", required = false) String appKey,
                                     @RequestHeader(value = "X-EAF-Timestamp", required = false) String timestamp,
                                     @RequestHeader(value = "X-EAF-Nonce", required = false) String nonce,
                                     @RequestHeader(value = "X-EAF-Signature", required = false) String signature) {
        try {
            securityService.verifyIfConfigured(projectCode,
                    new RegistrySecurityService.RegistrySignatureHeaders(appKey, timestamp, nonce, signature));
            registryService.offline(projectCode, request == null ? null : request.instanceId());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectCode}/capabilities/sync")
    public ResponseEntity<?> syncCapabilities(@PathVariable String projectCode,
                                              @RequestBody CapabilitySyncRequest request,
                                              @RequestHeader(value = "X-EAF-App-Key", required = false) String appKey,
                                              @RequestHeader(value = "X-EAF-Timestamp", required = false) String timestamp,
                                              @RequestHeader(value = "X-EAF-Nonce", required = false) String nonce,
                                              @RequestHeader(value = "X-EAF-Signature", required = false) String signature) {
        try {
            securityService.verifyIfConfigured(projectCode,
                    new RegistrySecurityService.RegistrySignatureHeaders(appKey, timestamp, nonce, signature));
            CapabilitySyncResponse response = registryService.sync(projectCode, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectCode}/capabilities/diff")
    public ResponseEntity<?> diffCapabilities(@PathVariable String projectCode,
                                              @RequestBody CapabilitySyncRequest request,
                                              @RequestHeader(value = "X-EAF-App-Key", required = false) String appKey,
                                              @RequestHeader(value = "X-EAF-Timestamp", required = false) String timestamp,
                                              @RequestHeader(value = "X-EAF-Nonce", required = false) String nonce,
                                              @RequestHeader(value = "X-EAF-Signature", required = false) String signature) {
        try {
            securityService.verifyIfConfigured(projectCode,
                    new RegistrySecurityService.RegistrySignatureHeaders(appKey, timestamp, nonce, signature));
            CapabilitySyncResponse response = registryService.diff(projectCode, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectCode}/capabilities/apply")
    public ResponseEntity<?> applyCapabilities(@PathVariable String projectCode,
                                               @RequestBody CapabilitySyncRequest request,
                                               @RequestHeader(value = "X-EAF-App-Key", required = false) String appKey,
                                               @RequestHeader(value = "X-EAF-Timestamp", required = false) String timestamp,
                                               @RequestHeader(value = "X-EAF-Nonce", required = false) String nonce,
                                               @RequestHeader(value = "X-EAF-Signature", required = false) String signature) {
        try {
            securityService.verifyIfConfigured(projectCode,
                    new RegistrySecurityService.RegistrySignatureHeaders(appKey, timestamp, nonce, signature));
            CapabilitySyncRequest applyRequest = new CapabilitySyncRequest(
                    request == null ? null : request.syncId(),
                    request == null ? null : request.source(),
                    true,
                    request == null ? null : request.capabilities());
            CapabilitySyncResponse response = registryService.sync(projectCode, applyRequest);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/projects/{projectCode}/capability-snapshots")
    public ResponseEntity<?> listCapabilitySnapshots(@PathVariable String projectCode) {
        try {
            List<CapabilitySnapshotDTO> snapshots = registryService.listSnapshots(projectCode);
            return ResponseEntity.ok(snapshots);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/capability-snapshots/{snapshotId}/diff-items")
    public ResponseEntity<?> listCapabilityDiffItems(@PathVariable Long snapshotId) {
        List<CapabilityDiffItemDTO> items = registryService.listDiffItems(snapshotId);
        return ResponseEntity.ok(items);
    }

    @PostMapping("/capability-diff-items/{diffItemId}/review")
    public ResponseEntity<?> reviewCapabilityDiffItem(@PathVariable Long diffItemId,
                                                      @RequestBody CapabilityReviewRequest request) {
        try {
            CapabilityDiffItemDTO item = registryService.reviewDiffItem(diffItemId, request);
            return ResponseEntity.ok(item);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    record InstanceOfflineRequest(String instanceId) {
    }

    record ApiErrorResponse(String message) {
    }
}

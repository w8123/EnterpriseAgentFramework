package com.enterprise.ai.agent.capability.catalog.controller;

import com.enterprise.ai.agent.platform.control.identity.PageActionCatalogContracts.PageCatalogRegisterRequest;
import com.enterprise.ai.agent.platform.control.identity.PageActionCatalogService;
import com.enterprise.ai.agent.platform.control.identity.PageCatalogRegisterResult;
import com.enterprise.ai.agent.registry.AiRegistryService;
import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityDiffItemDTO;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityReviewRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySnapshotDTO;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySyncRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySyncResponse;
import com.enterprise.ai.agent.registry.RegistryContracts.AgentGraphSyncRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.AgentGraphSyncResponse;
import com.enterprise.ai.agent.registry.RegistryContracts.InstanceHeartbeatRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.ProjectRegisterRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.RegistryProjectResponse;
import com.enterprise.ai.agent.registry.RegistryContracts.RuntimeGovernancePolicyUpdateRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.SdkCapabilityDescriptionSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/registry")
@RequiredArgsConstructor
public class AiRegistryController {

    private final AiRegistryService registryService;
    private final RegistrySecurityService securityService;
    private final PageActionCatalogService pageActionCatalogService;

    @PostMapping("/projects/register")
    public ResponseEntity<?> registerProject(@RequestBody ProjectRegisterRequest request,
                                             @RequestHeader HttpHeaders headers) {
        try {
            if (request != null) {
                securityService.verifyIfConfigured(request.projectCode(), signatureHeaders(headers));
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
                                       @RequestHeader HttpHeaders headers) {
        try {
            securityService.verifyIfConfigured(projectCode, signatureHeaders(headers));
            return ResponseEntity.ok(registryService.heartbeat(projectCode, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/projects/{projectCode}/capability-description-settings")
    public ResponseEntity<?> getSdkCapabilityDescriptionSettings(@PathVariable String projectCode,
                                                                 @RequestHeader HttpHeaders headers) {
        try {
            securityService.verifyIfConfigured(projectCode, signatureHeaders(headers));
            SdkCapabilityDescriptionSettings body = registryService.getSdkCapabilityDescriptionSettings(projectCode);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectCode}/pages/register")
    public ResponseEntity<?> registerPageCatalog(@PathVariable String projectCode,
                                                 @RequestBody PageCatalogRegisterRequest request,
                                                 @RequestHeader HttpHeaders headers) {
        try {
            RegistryCredentialEntity credential = securityService.verifyRequired(projectCode, signatureHeaders(headers));
            PageCatalogRegisterResult result = pageActionCatalogService.registerFromProjectCredential(credential, request);
            return ResponseEntity.ok(result);
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
                                     @RequestHeader HttpHeaders headers) {
        try {
            securityService.verifyIfConfigured(projectCode, signatureHeaders(headers));
            registryService.offline(projectCode, request == null ? null : request.instanceId());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectCode}/capabilities/sync")
    public ResponseEntity<?> syncCapabilities(@PathVariable String projectCode,
                                              @RequestBody CapabilitySyncRequest request,
                                              @RequestHeader HttpHeaders headers) {
        try {
            securityService.verifyIfConfigured(projectCode, signatureHeaders(headers));
            CapabilitySyncResponse response = registryService.sync(projectCode, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectCode}/agent-graphs/sync")
    public ResponseEntity<?> syncAgentGraphs(@PathVariable String projectCode,
                                             @RequestBody AgentGraphSyncRequest request,
                                             @RequestHeader HttpHeaders headers) {
        try {
            securityService.verifyIfConfigured(projectCode, signatureHeaders(headers));
            AgentGraphSyncResponse response = registryService.syncAgentGraphs(projectCode, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectCode}/capabilities/diff")
    public ResponseEntity<?> diffCapabilities(@PathVariable String projectCode,
                                              @RequestBody CapabilitySyncRequest request,
                                              @RequestHeader HttpHeaders headers) {
        try {
            securityService.verifyIfConfigured(projectCode, signatureHeaders(headers));
            CapabilitySyncResponse response = registryService.diff(projectCode, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/projects/{projectCode}/capabilities/apply")
    public ResponseEntity<?> applyCapabilities(@PathVariable String projectCode,
                                               @RequestBody CapabilitySyncRequest request,
                                               @RequestHeader HttpHeaders headers) {
        try {
            securityService.verifyIfConfigured(projectCode, signatureHeaders(headers));
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

    @PostMapping("/projects/{projectCode}/instances/purge-offline")
    public ResponseEntity<?> purgeOfflineInstances(@PathVariable String projectCode,
                                                   @RequestBody(required = false) PurgeOfflineRequest request) {
        try {
            int minIdleMinutes = request == null || request.minIdleMinutes() == null
                    ? 0
                    : request.minIdleMinutes();
            int removed = registryService.purgeOfflineInstances(projectCode, minIdleMinutes);
            return ResponseEntity.ok(new PurgeOfflineResponse(removed));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    record PurgeOfflineRequest(Integer minIdleMinutes) {
    }

    record PurgeOfflineResponse(int removed) {
    }

    @PostMapping("/projects/{projectCode}/instances/status")
    public ResponseEntity<?> updateInstanceStatus(@PathVariable String projectCode,
                                                  @RequestBody InstanceStatusRequest request) {
        try {
            ProjectInstanceEntity entity = registryService.updateInstanceStatus(
                    projectCode,
                    request == null ? null : request.instanceId(),
                    request == null ? null : request.status());
            return ResponseEntity.ok(entity);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    record InstanceStatusRequest(String instanceId, String status) {
    }

    @PostMapping("/projects/{projectCode}/instances/governance-policy")
    public ResponseEntity<?> updateInstanceGovernancePolicy(@PathVariable String projectCode,
                                                            @RequestBody RuntimeGovernancePolicyUpdateRequest request) {
        try {
            ProjectInstanceEntity entity = registryService.updateInstanceGovernancePolicy(projectCode, request);
            return ResponseEntity.ok(entity);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    record ApiErrorResponse(String message) {
    }

    private RegistrySecurityService.RegistrySignatureHeaders signatureHeaders(HttpHeaders headers) {
        return new RegistrySecurityService.RegistrySignatureHeaders(
                firstHeader(headers, "X-ReachAI-App-Key", "X-EAF-App-Key"),
                firstHeader(headers, "X-ReachAI-Timestamp", "X-EAF-Timestamp"),
                firstHeader(headers, "X-ReachAI-Nonce", "X-EAF-Nonce"),
                firstHeader(headers, "X-ReachAI-Signature", "X-EAF-Signature"));
    }

    private String firstHeader(HttpHeaders headers, String primary, String fallback) {
        if (headers == null) {
            return null;
        }
        String value = headers.getFirst(primary);
        return value == null || value.isBlank() ? headers.getFirst(fallback) : value;
    }
}

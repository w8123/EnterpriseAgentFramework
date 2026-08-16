package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/registry")
@RequiredArgsConstructor
public class RegistryOperationsCompatibilityController {

    private final CapabilityProxyClient capabilityProxyClient;
    private final RuntimeProxyClient runtimeProxyClient;

    @PostMapping("/projects/{projectCode}/instances/heartbeat")
    public ResponseEntity<Object> heartbeat(@PathVariable String projectCode,
                                            @RequestHeader(value = "X-ReachAI-App-Key", required = false) String appKey,
                                            @RequestHeader(value = "X-ReachAI-Timestamp", required = false) String timestamp,
                                            @RequestHeader(value = "X-ReachAI-Nonce", required = false) String nonce,
                                            @RequestHeader(value = "X-ReachAI-Signature", required = false) String signature,
                                            @RequestBody Map<String, Object> body) {
        return capabilityProxyClient.heartbeat(projectCode, appKey, timestamp, nonce, signature, body);
    }

    @PostMapping("/projects/{projectCode}/capabilities/sync")
    public ResponseEntity<Object> syncCapabilities(@PathVariable String projectCode,
                                                   @RequestHeader(value = "X-ReachAI-App-Key", required = false) String appKey,
                                                   @RequestHeader(value = "X-ReachAI-Timestamp", required = false) String timestamp,
                                                   @RequestHeader(value = "X-ReachAI-Nonce", required = false) String nonce,
                                                   @RequestHeader(value = "X-ReachAI-Signature", required = false) String signature,
                                                   @RequestBody Map<String, Object> body) {
        return capabilityProxyClient.syncCapabilities(projectCode, appKey, timestamp, nonce, signature, body);
    }

    @PostMapping("/projects/{projectCode}/capabilities/diff")
    public ResponseEntity<Object> diffCapabilities(@PathVariable String projectCode,
                                                   @RequestHeader(value = "X-ReachAI-App-Key", required = false) String appKey,
                                                   @RequestHeader(value = "X-ReachAI-Timestamp", required = false) String timestamp,
                                                   @RequestHeader(value = "X-ReachAI-Nonce", required = false) String nonce,
                                                   @RequestHeader(value = "X-ReachAI-Signature", required = false) String signature,
                                                   @RequestBody Map<String, Object> body) {
        return capabilityProxyClient.diffCapabilities(projectCode, appKey, timestamp, nonce, signature, body);
    }

    @PostMapping("/projects/{projectCode}/capabilities/apply")
    public ResponseEntity<Object> applyCapabilities(@PathVariable String projectCode,
                                                    @RequestBody Map<String, Object> body) {
        return capabilityProxyClient.applyCapabilities(projectCode, body);
    }

    @PostMapping("/projects/{projectCode}/agent-graphs/sync")
    public ResponseEntity<Object> syncAgentGraphs(@PathVariable String projectCode,
                                                  @RequestBody Map<String, Object> body) {
        return runtimeProxyClient.syncAgentGraphs(projectCode, body);
    }

    @GetMapping("/projects/{projectCode}/capability-snapshots")
    public ResponseEntity<Object> listCapabilitySnapshots(@PathVariable String projectCode) {
        return capabilityProxyClient.listCapabilitySnapshots(projectCode);
    }

    @GetMapping("/capability-snapshots/{snapshotId}/diff-items")
    public ResponseEntity<Object> listCapabilityDiffItems(@PathVariable Long snapshotId) {
        return capabilityProxyClient.listCapabilityDiffItems(snapshotId);
    }

    @PostMapping("/capability-diff-items/{diffItemId}/review")
    public ResponseEntity<Object> reviewCapabilityDiffItem(@PathVariable Long diffItemId,
                                                           @RequestBody Map<String, Object> body) {
        return capabilityProxyClient.reviewCapabilityDiffItem(diffItemId, body);
    }

    @PostMapping("/capability-diff-items/{diffItemId}/rollback")
    public ResponseEntity<Object> rollbackCapabilityDiffItem(@PathVariable Long diffItemId,
                                                             @RequestBody Map<String, Object> body) {
        return capabilityProxyClient.rollbackCapabilityDiffItem(diffItemId, body);
    }
}

package com.enterprise.ai.control.client.capability;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@FeignClient(name = "reachai-capability-proxy", url = "${services.capability-service.url:http://localhost:18605}")
public interface CapabilityProxyClient {

    @RequestMapping(method = RequestMethod.POST, path = "/api/registry/projects/register")
    ResponseEntity<Map<String, Object>> registerProject(@RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/registry/projects/{projectCode}/instances")
    ResponseEntity<List<Map<String, Object>>> listInstances(@PathVariable("projectCode") String projectCode);

    @RequestMapping(method = RequestMethod.POST, path = "/api/registry/projects/{projectCode}/instances/heartbeat")
    ResponseEntity<Object> heartbeat(@PathVariable("projectCode") String projectCode,
                                     @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/registry/projects/{projectCode}/capabilities/sync")
    ResponseEntity<Object> syncCapabilities(@PathVariable("projectCode") String projectCode,
                                            @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/api/registry/projects/{projectCode}/capabilities/diff")
    ResponseEntity<Object> diffCapabilities(@PathVariable("projectCode") String projectCode,
                                            @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/internal/capability/tools/{qualifiedName}")
    ResponseEntity<Map<String, Object>> getToolDefinition(@PathVariable("qualifiedName") String qualifiedName);

    @RequestMapping(method = RequestMethod.POST, path = "/api/registry/projects/{projectCode}/capabilities/apply")
    ResponseEntity<Object> applyCapabilities(@PathVariable("projectCode") String projectCode,
                                             @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/api/registry/projects/{projectCode}/capability-snapshots")
    ResponseEntity<Object> listCapabilitySnapshots(@PathVariable("projectCode") String projectCode);

    @RequestMapping(method = RequestMethod.GET, path = "/api/registry/capability-snapshots/{snapshotId}/diff-items")
    ResponseEntity<Object> listCapabilityDiffItems(@PathVariable("snapshotId") Long snapshotId);

    @RequestMapping(method = RequestMethod.POST, path = "/api/registry/capability-diff-items/{diffItemId}/review")
    ResponseEntity<Object> reviewCapabilityDiffItem(@PathVariable("diffItemId") Long diffItemId,
                                                    @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.GET, path = "/internal/capability/embed/credentials")
    ResponseEntity<Object> listEmbedCredentialPolicies(@RequestParam(value = "projectCode", required = false) String projectCode,
                                                       @RequestParam(value = "status", required = false) String status,
                                                       @RequestParam("limit") int limit);

    @RequestMapping(method = RequestMethod.PUT, path = "/internal/capability/embed/credentials/{id}/policy")
    ResponseEntity<Object> updateEmbedCredentialPolicy(@PathVariable("id") Long id,
                                                       @RequestBody Map<String, Object> body);

    @RequestMapping(method = RequestMethod.POST, path = "/internal/capability/embed/token/exchange/verify")
    ResponseEntity<Map<String, Object>> verifyEmbedTokenExchange(@org.springframework.web.bind.annotation.RequestHeader("X-ReachAI-App-Key") String appKey,
                                                                 @org.springframework.web.bind.annotation.RequestHeader("X-ReachAI-Timestamp") String timestamp,
                                                                 @org.springframework.web.bind.annotation.RequestHeader("X-ReachAI-Nonce") String nonce,
                                                                 @org.springframework.web.bind.annotation.RequestHeader("X-ReachAI-Signature") String signature,
                                                                 @RequestBody Map<String, Object> body);
}

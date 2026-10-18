package com.enterprise.ai.control.compat;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.common.internalauth.ProjectRequestAuthHeaders;
import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.enterprise.ai.control.knowledge.CapabilityProjectRequestVerificationGateway;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/registry")
@RequiredArgsConstructor
public class RegistryOperationsCompatibilityController {

    private final CapabilityProxyClient capabilityProxyClient;
    private final RuntimeProxyClient runtimeProxyClient;
    private final CapabilityProjectRequestVerificationGateway projectRequestVerifier;
    private final InternalServiceAuthSigner internalSigner;

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

    /**
     * SDK Agent Graph sync creates or overwrites Workflows, so it requires a
     * request-bound project signature (method, path and body digest) verified
     * by Capability before the exact bytes are forwarded to Runtime.
     */
    @PostMapping(value = "/projects/{projectCode}/agent-graphs/sync", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> syncAgentGraphs(HttpServletRequest servletRequest,
                                                  @PathVariable String projectCode,
                                                  @RequestHeader(value = ProjectRequestAuthHeaders.APP_KEY, required = false) String appKey,
                                                  @RequestHeader(value = ProjectRequestAuthHeaders.TIMESTAMP, required = false) String timestamp,
                                                  @RequestHeader(value = ProjectRequestAuthHeaders.NONCE, required = false) String nonce,
                                                  @RequestHeader(value = ProjectRequestAuthHeaders.BODY_SHA256, required = false) String bodySha256,
                                                  @RequestHeader(value = ProjectRequestAuthHeaders.SIGNATURE, required = false) String signature,
                                                  @RequestBody byte[] body) {
        byte[] payload = body == null ? new byte[0] : body;
        if (servletRequest == null || StringUtils.hasText(servletRequest.getQueryString())
                || !StringUtils.hasText(projectCode)) {
            throw unauthorized();
        }
        String digest = InternalServiceHmac.bodySha256Hex(payload);
        if (!InternalServiceHmac.digestEqualsConstantTime(digest, bodySha256)) {
            throw unauthorized();
        }
        String normalizedProjectCode = projectCode.trim();
        CapabilityProjectRequestVerificationGateway.VerifiedProject verified = projectRequestVerifier.verify(
                new CapabilityProjectRequestVerificationGateway.ProjectRequest(
                        normalizedProjectCode,
                        appKey,
                        "POST",
                        servletRequest.getRequestURI(),
                        timestamp,
                        nonce,
                        digest,
                        signature));
        String runtimePath = "/api/registry/projects/" + verified.projectCode() + "/agent-graphs/sync";
        return runtimeProxyClient.syncAgentGraphs(
                verified.projectCode(),
                internalSigner.sign("POST", runtimePath,
                        InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL,
                        verified.projectCode(), verified.internalActorId(), payload),
                payload);
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "project request authentication failed");
    }

}

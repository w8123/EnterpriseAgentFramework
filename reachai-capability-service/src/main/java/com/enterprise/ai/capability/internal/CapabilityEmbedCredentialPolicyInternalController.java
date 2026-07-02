package com.enterprise.ai.capability.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class CapabilityEmbedCredentialPolicyInternalController {

    private final CapabilityEmbedCredentialPolicyInternalService policyService;

    @GetMapping("/internal/capability/embed/credentials")
    public ResponseEntity<List<CapabilityEmbedCredentialPolicyInternalService.EmbedCredentialPolicyView>> listPolicies(
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok(policyService.listPolicies(projectCode, status, limit));
    }

    @PutMapping("/internal/capability/embed/credentials/{id}/policy")
    public ResponseEntity<CapabilityEmbedCredentialPolicyInternalService.EmbedCredentialPolicyView> updatePolicy(
            @PathVariable Long id,
            @RequestBody(required = false) EmbedCredentialPolicyUpdateRequest request) {
        try {
            return ResponseEntity.ok(policyService.updatePolicy(id, request == null ? null : request.toServiceRequest()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @PostMapping("/internal/capability/embed/token/exchange/verify")
    public ResponseEntity<CapabilityEmbedCredentialPolicyInternalService.EmbedTokenExchangeVerificationView> verifyTokenExchange(
            @RequestHeader(value = "X-ReachAI-App-Key", required = false) String reachAiAppKey,
            @RequestHeader(value = "X-EAF-App-Key", required = false) String eafAppKey,
            @RequestHeader(value = "X-ReachAI-Timestamp", required = false) String reachAiTimestamp,
            @RequestHeader(value = "X-EAF-Timestamp", required = false) String eafTimestamp,
            @RequestHeader(value = "X-ReachAI-Nonce", required = false) String reachAiNonce,
            @RequestHeader(value = "X-EAF-Nonce", required = false) String eafNonce,
            @RequestHeader(value = "X-ReachAI-Signature", required = false) String reachAiSignature,
            @RequestHeader(value = "X-EAF-Signature", required = false) String eafSignature,
            @RequestBody(required = false) EmbedTokenExchangeVerificationRequest request) {
        try {
            return ResponseEntity.ok(policyService.verifyTokenExchange(
                    firstText(reachAiAppKey, eafAppKey),
                    firstText(reachAiTimestamp, eafTimestamp),
                    firstText(reachAiNonce, eafNonce),
                    firstText(reachAiSignature, eafSignature),
                    request == null ? null : request.toServiceRequest()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    private String firstText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    public record EmbedCredentialPolicyUpdateRequest(
            List<String> allowedOrigins,
            List<String> allowedAgentIds,
            Integer tokenTtlSeconds,
            String status
    ) {
        CapabilityEmbedCredentialPolicyInternalService.EmbedCredentialPolicyUpdate toServiceRequest() {
            return new CapabilityEmbedCredentialPolicyInternalService.EmbedCredentialPolicyUpdate(
                    allowedOrigins,
                    allowedAgentIds,
                    tokenTtlSeconds,
                    status);
        }
    }

    public record EmbedTokenExchangeVerificationRequest(
            String projectCode,
            String agentId,
            String origin
    ) {
        CapabilityEmbedCredentialPolicyInternalService.EmbedTokenExchangeVerificationRequest toServiceRequest() {
            return new CapabilityEmbedCredentialPolicyInternalService.EmbedTokenExchangeVerificationRequest(
                    projectCode,
                    agentId,
                    origin);
        }
    }
}

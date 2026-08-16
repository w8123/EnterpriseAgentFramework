package com.enterprise.ai.control.client.runtime;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/**
 * Control → Runtime HMAC-authenticated Agent execute with verified identity binding.
 * Isolated from public {@code /api/runtime/agents/execute}.
 */
@FeignClient(name = "reachai-runtime-agent-execution-internal",
        url = "${services.runtime-service.url:http://localhost:18604}")
public interface RuntimeAgentExecutionInternalClient {

    String EXECUTE_PATH = "/internal/runtime/agents/execute";
    String EXECUTE_STREAM_PATH = "/internal/runtime/agents/execute/stream";
    String SESSION_PATH_PREFIX = "/internal/runtime/agents/sessions/";

    /**
     * Prefer {@link RuntimeTrustedAgentExecutionGateway} which signs the exact serialized body bytes.
     * This Feign surface remains for diagnostics; callers must supply Body-SHA256 matching the body.
     */
    @PostMapping(EXECUTE_PATH)
    ResponseEntity<Map<String, Object>> executeTrusted(
            @RequestHeader(InternalServiceAuthHeaders.CALLER) String caller,
            @RequestHeader(InternalServiceAuthHeaders.TIMESTAMP) String timestamp,
            @RequestHeader(InternalServiceAuthHeaders.NONCE) String nonce,
            @RequestHeader(InternalServiceAuthHeaders.IDENTITY_SOURCE) String identitySource,
            @RequestHeader(value = InternalServiceAuthHeaders.IDENTITY_TENANT_ID, required = false) String identityTenantId,
            @RequestHeader(value = InternalServiceAuthHeaders.IDENTITY_USER_ID, required = false) String identityUserId,
            @RequestHeader(InternalServiceAuthHeaders.BODY_SHA256) String bodySha256,
            @RequestHeader(InternalServiceAuthHeaders.SIGNATURE) String signature,
            @RequestBody TrustedAgentExecuteRequest request);

    record TrustedAgentExecuteRequest(
            Map<String, Object> body,
            TrustedIdentityPayload identity
    ) {
    }

    record TrustedIdentityPayload(
            String source,
            String tenantId,
            String userId
    ) {
    }
}

package com.enterprise.ai.control.capability;

import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

/** Exact-byte Control→Runtime boundary for an independently durable Console invocation. */
@Service
@RequiredArgsConstructor
public class RuntimeConsoleCapabilityInvocationGateway {

    private static final String ROOT = "/internal/runtime/console-capability-invocations";
    private final RuntimeProxyClient runtime;
    private final InternalServiceAuthSigner signer;
    private final ObjectMapper objectMapper;

    public ResponseEntity<Object> invoke(ConsoleCapabilityInvocationContracts.InvocationCommand command,
                                         String actorId) {
        byte[] body = serialize(command);
        return runtime.invokeConsoleCapability(headers("POST", ROOT, body, actorId), body);
    }

    public ResponseEntity<Object> get(String invocationId, String actorId) {
        if (!StringUtils.hasText(invocationId) || !invocationId.trim().matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("invocationId must be a UUID");
        }
        String path = ROOT + "/" + invocationId.trim();
        try {
            return runtime.getConsoleCapabilityInvocation(invocationId.trim(), headers("GET", path, new byte[0], actorId));
        } catch (FeignException.NotFound notVisible) {
            // Runtime intentionally hides records owned by another actor. Feign throws on
            // that 404; preserve it without forwarding the upstream body or address.
            return ResponseEntity.notFound().build();
        }
    }

    private Map<String, String> headers(String method, String path, byte[] body, String actorId) {
        if (!StringUtils.hasText(actorId)) throw new IllegalArgumentException("platform actor is required");
        return signer.sign(method, path, InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                "default", actorId.trim(), body);
    }

    private byte[] serialize(Object body) {
        try { return objectMapper.writeValueAsBytes(body); }
        catch (Exception invalid) { throw new IllegalArgumentException("Console invocation cannot be serialized", invalid); }
    }
}

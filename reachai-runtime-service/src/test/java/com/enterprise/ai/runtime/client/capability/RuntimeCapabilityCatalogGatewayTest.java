package com.enterprise.ai.runtime.client.capability;

import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import com.enterprise.ai.common.capability.CapabilityInvocationRequest;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.eval.RuntimeEvalExecutionContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeCapabilityCatalogGatewayTest {

    private static final String SECRET = "runtime-capability-contract-secret-32bytes";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void trustedMarkerOverwritesCallerIdentityAndSignsExactSerializedBytes() throws Exception {
        RuntimeCapabilityCatalogFeignClient transport = mock(RuntimeCapabilityCatalogFeignClient.class);
        when(transport.invokeCapability(anyMap(), any(byte[].class)))
                .thenAnswer(invocation -> successResponse(invocation.getArgument(1)));
        RuntimeCapabilityCatalogGateway gateway = new RuntimeCapabilityCatalogGateway(
                transport, new RuntimeCapabilityInternalAuthSigner(SECRET), objectMapper);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tenantId", "forged-tenant");
        context.put("externalUserId", "forged-user");
        context.put("globalUserId", "forged-global");
        context.put("roles", java.util.List.of("ADMIN"));
        context.put("sessionId", "session-1");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("input", Map.of("teamId", "7"));
        request.put("context", context);
        request.put(RuntimeCapabilityCatalogClient.TRUSTED_IDENTITY_ATTRIBUTE,
                WorkflowExecutionIdentity.fromAgent("tenant-a", 35L, "bzjs20", "user-7"));

        gateway.executeTool("bzjs20:team.memory.resolve", request);

        ArgumentCaptor<Map> headerCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(transport).invokeCapability(headerCaptor.capture(), bodyCaptor.capture());

        Map<String, String> headers = (Map<String, String>) headerCaptor.getValue();
        byte[] body = bodyCaptor.getValue();
        Map<String, Object> outbound = objectMapper.readValue(
                body, new TypeReference<Map<String, Object>>() { });
        Map<String, Object> outboundContext = (Map<String, Object>) outbound.get("context");

        assertFalse(outbound.containsKey(RuntimeCapabilityCatalogClient.TRUSTED_IDENTITY_ATTRIBUTE));
        assertEquals("tenant-a", outboundContext.get("tenantId"));
        assertEquals("user-7", outboundContext.get("externalUserId"));
        assertEquals("session-1", outboundContext.get("sessionId"));
        assertFalse(outboundContext.containsKey("globalUserId"));
        assertFalse(outboundContext.containsKey("roles"));
        assertEquals(InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_TRUSTED,
                headers.get(InternalServiceAuthHeaders.IDENTITY_SOURCE));
        assertEquals("tenant-a", headers.get(InternalServiceAuthHeaders.IDENTITY_TENANT_ID));
        assertEquals("user-7", headers.get(InternalServiceAuthHeaders.IDENTITY_USER_ID));
        assertTrue(signatureValid(headers, body, "bzjs20:team.memory.resolve"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void publicMapMarkerCannotCreateTrustAndAllIdentityFieldsAreScrubbed() throws Exception {
        RuntimeCapabilityCatalogFeignClient transport = mock(RuntimeCapabilityCatalogFeignClient.class);
        when(transport.invokeCapability(anyMap(), any(byte[].class)))
                .thenAnswer(invocation -> successResponse(invocation.getArgument(1)));
        RuntimeCapabilityCatalogGateway gateway = new RuntimeCapabilityCatalogGateway(
                transport, new RuntimeCapabilityInternalAuthSigner(SECRET), objectMapper);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("context", new LinkedHashMap<>(Map.of(
                "tenantId", "forged-tenant",
                "externalUserId", "forged-user",
                "sessionId", "public-session")));
        request.put(RuntimeCapabilityCatalogClient.TRUSTED_IDENTITY_ATTRIBUTE, Map.of(
                "source", "AGENT", "userId", "forged-user", "userTrusted", true));
        request.put(RuntimeCapabilityCatalogClient.TRUSTED_EVAL_CONTEXT_ATTRIBUTE,
                Map.of("mode", "READ_ONLY_EXECUTION"));
        request.put(RuntimeCapabilityCatalogGateway.SIGNED_EVAL_POLICY_FIELD,
                Map.of("mode", "READ_ONLY_EXECUTION"));

        gateway.executeTool("bzjs20:team.memory.resolve", request);

        ArgumentCaptor<Map> headerCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(transport).invokeCapability(headerCaptor.capture(), bodyCaptor.capture());
        Map<String, String> headers = (Map<String, String>) headerCaptor.getValue();
        Map<String, Object> outbound = objectMapper.readValue(
                bodyCaptor.getValue(), new TypeReference<Map<String, Object>>() { });
        Map<String, Object> outboundContext = (Map<String, Object>) outbound.get("context");

        assertEquals("public-session", outboundContext.get("sessionId"));
        assertFalse(outboundContext.containsKey("tenantId"));
        assertFalse(outboundContext.containsKey("externalUserId"));
        assertFalse(outbound.containsKey(RuntimeCapabilityCatalogClient.TRUSTED_IDENTITY_ATTRIBUTE));
        assertFalse(outbound.containsKey(RuntimeCapabilityCatalogClient.TRUSTED_EVAL_CONTEXT_ATTRIBUTE));
        assertFalse(outbound.containsKey(RuntimeCapabilityCatalogGateway.SIGNED_EVAL_POLICY_FIELD));
        assertEquals(InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_UNTRUSTED,
                headers.get(InternalServiceAuthHeaders.IDENTITY_SOURCE));
        assertEquals("", headers.get(InternalServiceAuthHeaders.IDENTITY_TENANT_ID));
        assertEquals("", headers.get(InternalServiceAuthHeaders.IDENTITY_USER_ID));
        assertTrue(signatureValid(headers, bodyCaptor.getValue(), "bzjs20:team.memory.resolve"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void trustedEvalMarkerReplacesForgedPolicyAndIsCoveredByTheExactBodySignature() throws Exception {
        RuntimeCapabilityCatalogFeignClient transport = mock(RuntimeCapabilityCatalogFeignClient.class);
        when(transport.invokeCapability(anyMap(), any(byte[].class)))
                .thenAnswer(invocation -> successResponse(invocation.getArgument(1)));
        RuntimeCapabilityCatalogGateway gateway = new RuntimeCapabilityCatalogGateway(
                transport, new RuntimeCapabilityInternalAuthSigner(SECRET), objectMapper);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put(RuntimeCapabilityCatalogGateway.SIGNED_EVAL_POLICY_FIELD,
                Map.of("mode", "NONE", "sideEffectPolicy", "ALLOW_ALL"));
        request.put(RuntimeCapabilityCatalogClient.TRUSTED_EVAL_CONTEXT_ATTRIBUTE,
                RuntimeEvalExecutionContext.readOnly("exp-1", "item-7", "sha256:abc"));

        gateway.executeTool("orders:query", request);

        ArgumentCaptor<Map> headerCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(transport).invokeCapability(headerCaptor.capture(), bodyCaptor.capture());
        Map<String, Object> outbound = objectMapper.readValue(
                bodyCaptor.getValue(), new TypeReference<Map<String, Object>>() { });
        Map<String, Object> policy = (Map<String, Object>) outbound.get(
                RuntimeCapabilityCatalogGateway.SIGNED_EVAL_POLICY_FIELD);
        assertFalse(outbound.containsKey(RuntimeCapabilityCatalogClient.TRUSTED_EVAL_CONTEXT_ATTRIBUTE));
        assertEquals("READ_ONLY_EXECUTION", policy.get("mode"));
        assertEquals("READ_ONLY_ONLY", policy.get("sideEffectPolicy"));
        assertEquals("exp-1", policy.get("experimentId"));
        assertEquals("item-7", policy.get("itemId"));
        assertTrue(signatureValid((Map<String, String>) headerCaptor.getValue(),
                bodyCaptor.getValue(), "orders:query"));
    }

    @Test
    void downstreamTransportFailureIsNotMisclassifiedAsSerializationFailure() {
        RuntimeCapabilityCatalogFeignClient transport = mock(RuntimeCapabilityCatalogFeignClient.class);
        RuntimeException downstream = new RuntimeException("downstream 404");
        when(transport.invokeCapability(anyMap(), any(byte[].class))).thenThrow(downstream);
        RuntimeCapabilityCatalogGateway gateway = new RuntimeCapabilityCatalogGateway(
                transport, new RuntimeCapabilityInternalAuthSigner(SECRET), objectMapper);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> gateway.executeTool("bzjs20:missing", Map.of()));

        assertSame(downstream, thrown);
    }

    @Test
    void rejectsMismatchedInvocationResponse() {
        RuntimeCapabilityCatalogFeignClient transport = mock(RuntimeCapabilityCatalogFeignClient.class);
        when(transport.invokeCapability(anyMap(), any(byte[].class)))
                .thenReturn(new CapabilityInvocationResponse(
                        CapabilityInvocationRequest.CONTRACT_VERSION,
                        "different-invocation",
                        "orders:query",
                        "query",
                        "Query",
                        CapabilityInvocationStatus.SUCCEEDED,
                        true,
                        Map.of(),
                        null,
                        null,
                        CapabilityInvocationFailureCategory.NONE,
                        false,
                        1L,
                        1,
                        null,
                        Map.of()));
        RuntimeCapabilityCatalogGateway gateway = new RuntimeCapabilityCatalogGateway(
                transport, new RuntimeCapabilityInternalAuthSigner(SECRET), objectMapper);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> gateway.executeTool("orders:query", Map.of()));

        assertEquals("Capability Tool response correlation is invalid", thrown.getMessage());
    }

    private boolean signatureValid(Map<String, String> headers, byte[] body, String qualifiedName) {
        String digest = InternalServiceHmac.bodySha256Hex(body);
        String canonical = InternalServiceHmac.canonical(
                "POST",
                RuntimeCapabilityInternalAuthSigner.CAPABILITY_INVOCATION_PATH,
                headers.get(InternalServiceAuthHeaders.CALLER),
                headers.get(InternalServiceAuthHeaders.IDENTITY_SOURCE),
                headers.get(InternalServiceAuthHeaders.IDENTITY_TENANT_ID),
                headers.get(InternalServiceAuthHeaders.IDENTITY_USER_ID),
                headers.get(InternalServiceAuthHeaders.TIMESTAMP),
                headers.get(InternalServiceAuthHeaders.NONCE),
                digest);
        return digest.equals(headers.get(InternalServiceAuthHeaders.BODY_SHA256))
                && InternalServiceHmac.verifyConstantTime(
                SECRET, canonical, headers.get(InternalServiceAuthHeaders.SIGNATURE));
    }

    private CapabilityInvocationResponse successResponse(byte[] requestBody) throws Exception {
        Map<String, Object> request = objectMapper.readValue(
                requestBody, new TypeReference<Map<String, Object>>() { });
        return new CapabilityInvocationResponse(
                CapabilityInvocationRequest.CONTRACT_VERSION,
                String.valueOf(request.get("invocationId")),
                String.valueOf(request.get("qualifiedName")),
                "capability",
                "Capability",
                CapabilityInvocationStatus.SUCCEEDED,
                true,
                Map.of("ok", true),
                null,
                null,
                CapabilityInvocationFailureCategory.NONE,
                false,
                1L,
                1,
                null,
                Map.of("statusCode", 200));
    }
}

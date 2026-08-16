package com.enterprise.ai.runtime.client.capability;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
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
        when(transport.executeTool(anyString(), anyMap(), any(byte[].class)))
                .thenReturn(Map.of("success", true));
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
        verify(transport).executeTool(
                org.mockito.ArgumentMatchers.eq("bzjs20:team.memory.resolve"),
                headerCaptor.capture(), bodyCaptor.capture());

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
        when(transport.executeTool(anyString(), anyMap(), any(byte[].class)))
                .thenReturn(Map.of("success", true));
        RuntimeCapabilityCatalogGateway gateway = new RuntimeCapabilityCatalogGateway(
                transport, new RuntimeCapabilityInternalAuthSigner(SECRET), objectMapper);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("context", new LinkedHashMap<>(Map.of(
                "tenantId", "forged-tenant",
                "externalUserId", "forged-user",
                "sessionId", "public-session")));
        request.put(RuntimeCapabilityCatalogClient.TRUSTED_IDENTITY_ATTRIBUTE, Map.of(
                "source", "AGENT", "userId", "forged-user", "userTrusted", true));

        gateway.executeTool("bzjs20:team.memory.resolve", request);

        ArgumentCaptor<Map> headerCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(transport).executeTool(anyString(), headerCaptor.capture(), bodyCaptor.capture());
        Map<String, String> headers = (Map<String, String>) headerCaptor.getValue();
        Map<String, Object> outbound = objectMapper.readValue(
                bodyCaptor.getValue(), new TypeReference<Map<String, Object>>() { });
        Map<String, Object> outboundContext = (Map<String, Object>) outbound.get("context");

        assertEquals("public-session", outboundContext.get("sessionId"));
        assertFalse(outboundContext.containsKey("tenantId"));
        assertFalse(outboundContext.containsKey("externalUserId"));
        assertFalse(outbound.containsKey(RuntimeCapabilityCatalogClient.TRUSTED_IDENTITY_ATTRIBUTE));
        assertEquals(InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_UNTRUSTED,
                headers.get(InternalServiceAuthHeaders.IDENTITY_SOURCE));
        assertEquals("", headers.get(InternalServiceAuthHeaders.IDENTITY_TENANT_ID));
        assertEquals("", headers.get(InternalServiceAuthHeaders.IDENTITY_USER_ID));
        assertTrue(signatureValid(headers, bodyCaptor.getValue(), "bzjs20:team.memory.resolve"));
    }

    @Test
    void downstreamTransportFailureIsNotMisclassifiedAsSerializationFailure() {
        RuntimeCapabilityCatalogFeignClient transport = mock(RuntimeCapabilityCatalogFeignClient.class);
        RuntimeException downstream = new RuntimeException("downstream 404");
        when(transport.executeTool(anyString(), anyMap(), any(byte[].class))).thenThrow(downstream);
        RuntimeCapabilityCatalogGateway gateway = new RuntimeCapabilityCatalogGateway(
                transport, new RuntimeCapabilityInternalAuthSigner(SECRET), objectMapper);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> gateway.executeTool("bzjs20:missing", Map.of()));

        assertSame(downstream, thrown);
    }

    private boolean signatureValid(Map<String, String> headers, byte[] body, String qualifiedName) {
        String digest = InternalServiceHmac.bodySha256Hex(body);
        String canonical = InternalServiceHmac.canonical(
                "POST",
                RuntimeCapabilityInternalAuthSigner.toolExecutePath(qualifiedName),
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
}

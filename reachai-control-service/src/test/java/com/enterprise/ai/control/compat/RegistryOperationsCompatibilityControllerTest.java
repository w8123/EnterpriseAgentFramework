package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.enterprise.ai.control.knowledge.CapabilityProjectRequestVerificationGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RegistryOperationsCompatibilityControllerTest {

    @Test
    void keepsSdkRegistryOperationRouteShapeOnControlService() throws Exception {
        RequestMapping controllerMapping =
                RegistryOperationsCompatibilityController.class.getAnnotation(RequestMapping.class);
        Method heartbeat = RegistryOperationsCompatibilityController.class
                .getDeclaredMethod("heartbeat", String.class, String.class, String.class,
                        String.class, String.class, Map.class);
        Method syncCapabilities = RegistryOperationsCompatibilityController.class
                .getDeclaredMethod("syncCapabilities", String.class, String.class, String.class,
                        String.class, String.class, Map.class);
        Method syncAgentGraphs = RegistryOperationsCompatibilityController.class
                .getDeclaredMethod("syncAgentGraphs", jakarta.servlet.http.HttpServletRequest.class, String.class,
                        String.class, String.class, String.class, String.class, String.class, byte[].class);

        assertArrayEquals(new String[] {"/api/registry"}, controllerMapping.value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/instances/heartbeat"},
                heartbeat.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/capabilities/sync"},
                syncCapabilities.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/agent-graphs/sync"},
                syncAgentGraphs.getAnnotation(PostMapping.class).value());
    }

    @Test
    void delegatesSdkRegistryOperationsToCapabilityService() {
        CapabilityProxyClient capabilityProxyClient = mock(CapabilityProxyClient.class);
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        RegistryOperationsCompatibilityController controller =
                new RegistryOperationsCompatibilityController(capabilityProxyClient, runtimeProxyClient,
                        mock(CapabilityProjectRequestVerificationGateway.class), mock(InternalServiceAuthSigner.class));
        Map<String, Object> request = Map.of("syncId", "sync-1");
        ResponseEntity<Object> delegated = ResponseEntity.ok(Map.of("syncId", "sync-1"));

        when(capabilityProxyClient.heartbeat(
                "orders", "key", "123", "nonce", "signature", request)).thenReturn(delegated);
        when(capabilityProxyClient.syncCapabilities(
                "orders", "key", "123", "nonce", "signature", request)).thenReturn(delegated);

        assertEquals(delegated, controller.heartbeat(
                "orders", "key", "123", "nonce", "signature", request));
        assertEquals(delegated, controller.syncCapabilities(
                "orders", "key", "123", "nonce", "signature", request));

        verify(capabilityProxyClient).heartbeat(
                "orders", "key", "123", "nonce", "signature", request);
        verify(capabilityProxyClient).syncCapabilities(
                "orders", "key", "123", "nonce", "signature", request);
    }

    @Test
    void forwardsAgentGraphSyncOnlyAfterProjectSignatureVerification() {
        CapabilityProxyClient capabilityProxyClient = mock(CapabilityProxyClient.class);
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        CapabilityProjectRequestVerificationGateway verifier = mock(CapabilityProjectRequestVerificationGateway.class);
        InternalServiceAuthSigner signer = mock(InternalServiceAuthSigner.class);
        RegistryOperationsCompatibilityController controller =
                new RegistryOperationsCompatibilityController(capabilityProxyClient, runtimeProxyClient, verifier, signer);
        byte[] body = "{\"graphs\":[]}".getBytes(StandardCharsets.UTF_8);
        String digest = InternalServiceHmac.bodySha256Hex(body);
        MockHttpServletRequest servletRequest =
                new MockHttpServletRequest("POST", "/api/registry/projects/orders/agent-graphs/sync");
        ResponseEntity<Object> delegated = ResponseEntity.ok(Map.of("syncId", "sync-1"));
        Map<String, String> internalHeaders = Map.of("X-Internal", "signed");

        when(verifier.verify(any())).thenReturn(
                new CapabilityProjectRequestVerificationGateway.VerifiedProject(7L, "orders", 3L, "hash"));
        when(signer.sign(eq("POST"), eq("/api/registry/projects/orders/agent-graphs/sync"),
                eq(InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL), eq("orders"),
                eq("credential:3"), eq(body))).thenReturn(internalHeaders);
        when(runtimeProxyClient.syncAgentGraphs("orders", internalHeaders, body)).thenReturn(delegated);

        assertEquals(delegated, controller.syncAgentGraphs(servletRequest, "orders",
                "key", "123", "nonce", digest, "signature", body));

        ArgumentCaptor<CapabilityProjectRequestVerificationGateway.ProjectRequest> captor =
                ArgumentCaptor.forClass(CapabilityProjectRequestVerificationGateway.ProjectRequest.class);
        verify(verifier).verify(captor.capture());
        assertEquals("/api/registry/projects/orders/agent-graphs/sync", captor.getValue().path());
        assertEquals(digest, captor.getValue().bodySha256());
    }

    @Test
    void rejectsAgentGraphSyncWhoseBodyDoesNotMatchTheSignedDigest() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        CapabilityProjectRequestVerificationGateway verifier = mock(CapabilityProjectRequestVerificationGateway.class);
        RegistryOperationsCompatibilityController controller = new RegistryOperationsCompatibilityController(
                mock(CapabilityProxyClient.class), runtimeProxyClient, verifier, mock(InternalServiceAuthSigner.class));
        byte[] body = "{\"graphs\":[]}".getBytes(StandardCharsets.UTF_8);

        ResponseStatusException rejected = assertThrows(ResponseStatusException.class, () ->
                controller.syncAgentGraphs(new MockHttpServletRequest("POST", "/api/registry/projects/orders/agent-graphs/sync"),
                        "orders", "key", "123", "nonce", InternalServiceHmac.bodySha256Hex(new byte[0]), "signature", body));

        assertEquals(401, rejected.getStatusCode().value());
        verifyNoInteractions(verifier, runtimeProxyClient);
    }

}

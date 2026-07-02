package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformEmbedCredentialPolicyControllerTest {

    @Test
    void listsCredentialPoliciesThroughCapabilityService() {
        CapabilityProxyClient capabilityProxyClient = mock(CapabilityProxyClient.class);
        PlatformEmbedCredentialPolicyController controller =
                new PlatformEmbedCredentialPolicyController(capabilityProxyClient);
        List<Map<String, Object>> body = List.of(Map.of(
                "id", 9L,
                "projectCode", "bzjs12",
                "appKey", "app-bzjs12",
                "status", "ACTIVE"));
        when(capabilityProxyClient.listEmbedCredentialPolicies("bzjs12", "ACTIVE", 200))
                .thenReturn(ResponseEntity.ok(body));

        ResponseEntity<Object> response = controller.listPolicies("bzjs12", "ACTIVE", 200);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(body, response.getBody());
        verify(capabilityProxyClient).listEmbedCredentialPolicies("bzjs12", "ACTIVE", 200);
    }

    @Test
    void updatesCredentialPolicyThroughCapabilityService() {
        CapabilityProxyClient capabilityProxyClient = mock(CapabilityProxyClient.class);
        PlatformEmbedCredentialPolicyController controller =
                new PlatformEmbedCredentialPolicyController(capabilityProxyClient);
        Map<String, Object> request = Map.of(
                "allowedOrigins", List.of("http://localhost:5200"),
                "allowedAgentIds", List.of("agent-1"),
                "tokenTtlSeconds", 900,
                "status", "ACTIVE");
        Map<String, Object> body = Map.of(
                "id", 9L,
                "allowedOriginsJson", "[\"http://localhost:5200\"]",
                "allowedAgentIdsJson", "[\"agent-1\"]",
                "tokenTtlSeconds", 900,
                "status", "ACTIVE");
        when(capabilityProxyClient.updateEmbedCredentialPolicy(9L, request))
                .thenReturn(ResponseEntity.ok(body));

        ResponseEntity<Object> response = controller.updatePolicy(9L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(body, response.getBody());
        verify(capabilityProxyClient).updateEmbedCredentialPolicy(9L, request);
    }
}

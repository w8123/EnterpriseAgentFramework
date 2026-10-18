package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformEmbedCredentialPolicyControllerTest {

    @Test
    void listsCredentialPoliciesThroughCapabilityService() {
        CapabilityProxyClient capabilityProxyClient = mock(CapabilityProxyClient.class);
        PlatformEmbedCredentialPolicyController controller =
                new PlatformEmbedCredentialPolicyController(capabilityProxyClient, mock(PlatformEmbedOriginPolicy.class),
                        mock(RuntimeProxyClient.class), new ObjectMapper());
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
                new PlatformEmbedCredentialPolicyController(capabilityProxyClient, mock(PlatformEmbedOriginPolicy.class),
                        mock(RuntimeProxyClient.class), new ObjectMapper());
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

    @Test
    void readinessExplainsEachBlockingStepAndPassesWhenConfigured() {
        CapabilityProxyClient capability = mock(CapabilityProxyClient.class);
        RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
        PlatformEmbedOriginPolicy originPolicy = mock(PlatformEmbedOriginPolicy.class);
        PlatformEmbedCredentialPolicyController controller =
                new PlatformEmbedCredentialPolicyController(capability, originPolicy, runtime, new ObjectMapper());
        when(runtime.listAgents(null, "yudao-erp")).thenReturn(ResponseEntity.ok(List.of(
                Map.of("id", "8e747bd7c1f1", "keySlug", "erp-assistant", "enabled", true))));

        when(capability.listEmbedCredentialPolicies("yudao-erp", "ACTIVE", 20)).thenReturn(ResponseEntity.ok(List.of(
                Map.of("projectCode", "yudao-erp", "status", "ACTIVE", "allowedOriginsJson", "[]"))));
        var blocked = (PlatformEmbedCredentialPolicyController.EmbedReadinessView) controller.readiness("yudao-erp", "8e747bd7c1f1", "https://yudao.w8123.com/erp").getBody();
        assertEquals("https://yudao.w8123.com", blocked.origin());
        assertFalse(blocked.ready());
        assertFalse(check(blocked, "origin").ok());
        assertTrue(check(blocked, "agentAllowed").ok());
        assertTrue(check(blocked, "agentAvailable").ok());

        when(capability.listEmbedCredentialPolicies("yudao-erp", "ACTIVE", 20)).thenReturn(ResponseEntity.ok(List.of(
                Map.of("projectCode", "yudao-erp", "status", "ACTIVE",
                        "allowedOriginsJson", "[\"https://yudao.w8123.com\"]",
                        "allowedAgentIdsJson", "[\"erp-assistant\"]"))));
        when(originPolicy.allows("https://yudao.w8123.com")).thenReturn(true);
        var ready = (PlatformEmbedCredentialPolicyController.EmbedReadinessView) controller.readiness("yudao-erp", "8e747bd7c1f1", "https://yudao.w8123.com").getBody();
        assertTrue(ready.ready());

        var otherAgent = (PlatformEmbedCredentialPolicyController.EmbedReadinessView) controller.readiness("yudao-erp", "unknown-agent", "https://yudao.w8123.com").getBody();
        assertFalse(check(otherAgent, "agentAllowed").ok());
        assertFalse(check(otherAgent, "agentAvailable").ok());
    }

    private static PlatformEmbedCredentialPolicyController.EmbedReadinessCheck check(
            PlatformEmbedCredentialPolicyController.EmbedReadinessView view, String key) {
        return view.checks().stream().filter(item -> item.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void readinessWithoutRequiredParametersIsBadRequest() {
        var controller = new PlatformEmbedCredentialPolicyController(mock(CapabilityProxyClient.class),
                mock(PlatformEmbedOriginPolicy.class), mock(RuntimeProxyClient.class), new ObjectMapper());
        assertEquals(400, controller.readiness("yudao-erp", null, "https://yudao.w8123.com").getStatusCode().value());
    }
}

package com.enterprise.ai.capability.internal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityEmbedCredentialPolicyInternalControllerTest {

    @Test
    void listsEmbedCredentialPoliciesForControlService() {
        CapabilityEmbedCredentialPolicyInternalService service =
                mock(CapabilityEmbedCredentialPolicyInternalService.class);
        CapabilityEmbedCredentialPolicyInternalController controller =
                new CapabilityEmbedCredentialPolicyInternalController(service);
        List<CapabilityEmbedCredentialPolicyInternalService.EmbedCredentialPolicyView> policies = List.of(
                new CapabilityEmbedCredentialPolicyInternalService.EmbedCredentialPolicyView(
                        9L,
                        7L,
                        "bzjs12",
                        "app-bzjs12",
                        "[\"http://localhost:5200\"]",
                        "[\"agent-1\"]",
                        900,
                        "ACTIVE"));
        when(service.listPolicies("bzjs12", "ACTIVE", 200)).thenReturn(policies);

        ResponseEntity<List<CapabilityEmbedCredentialPolicyInternalService.EmbedCredentialPolicyView>> response =
                controller.listPolicies("bzjs12", "ACTIVE", 200);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(policies, response.getBody());
        verify(service).listPolicies("bzjs12", "ACTIVE", 200);
    }

    @Test
    void updatesEmbedCredentialPolicyForControlService() {
        CapabilityEmbedCredentialPolicyInternalService service =
                mock(CapabilityEmbedCredentialPolicyInternalService.class);
        CapabilityEmbedCredentialPolicyInternalController controller =
                new CapabilityEmbedCredentialPolicyInternalController(service);
        CapabilityEmbedCredentialPolicyInternalController.EmbedCredentialPolicyUpdateRequest request =
                new CapabilityEmbedCredentialPolicyInternalController.EmbedCredentialPolicyUpdateRequest(
                        List.of("http://localhost:5200"),
                        List.of("agent-1"),
                        900,
                        "ACTIVE");
        CapabilityEmbedCredentialPolicyInternalService.EmbedCredentialPolicyView updated =
                new CapabilityEmbedCredentialPolicyInternalService.EmbedCredentialPolicyView(
                        9L,
                        7L,
                        "bzjs12",
                        "app-bzjs12",
                        "[\"http://localhost:5200\"]",
                        "[\"agent-1\"]",
                        900,
                        "ACTIVE");
        when(service.updatePolicy(9L, request.toServiceRequest())).thenReturn(updated);

        ResponseEntity<CapabilityEmbedCredentialPolicyInternalService.EmbedCredentialPolicyView> response =
                controller.updatePolicy(9L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(updated, response.getBody());
        verify(service).updatePolicy(9L, request.toServiceRequest());
    }

    @Test
    void returnsNotFoundWhenCredentialMissing() {
        CapabilityEmbedCredentialPolicyInternalService service =
                mock(CapabilityEmbedCredentialPolicyInternalService.class);
        CapabilityEmbedCredentialPolicyInternalController controller =
                new CapabilityEmbedCredentialPolicyInternalController(service);
        when(service.updatePolicy(404L, new CapabilityEmbedCredentialPolicyInternalService.EmbedCredentialPolicyUpdate(
                List.of(),
                List.of(),
                600,
                "ACTIVE"))).thenThrow(new IllegalArgumentException("Credential not found: 404"));

        ResponseEntity<CapabilityEmbedCredentialPolicyInternalService.EmbedCredentialPolicyView> response =
                controller.updatePolicy(404L, new CapabilityEmbedCredentialPolicyInternalController.EmbedCredentialPolicyUpdateRequest(
                        List.of(),
                        List.of(),
                        600,
                        "ACTIVE"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void verifiesEmbedTokenExchangeForControlService() {
        CapabilityEmbedCredentialPolicyInternalService service =
                mock(CapabilityEmbedCredentialPolicyInternalService.class);
        CapabilityEmbedCredentialPolicyInternalController controller =
                new CapabilityEmbedCredentialPolicyInternalController(service);
        CapabilityEmbedCredentialPolicyInternalController.EmbedTokenExchangeVerificationRequest request =
                new CapabilityEmbedCredentialPolicyInternalController.EmbedTokenExchangeVerificationRequest(
                        "bzjs12",
                        "orders-bot",
                        "http://localhost:5173");
        CapabilityEmbedCredentialPolicyInternalService.EmbedTokenExchangeVerificationView verified =
                new CapabilityEmbedCredentialPolicyInternalService.EmbedTokenExchangeVerificationView(
                        "bzjs12",
                        "app-bzjs12",
                        900);
        when(service.verifyTokenExchange("app-bzjs12", "1700000000000", "nonce-1", "signature-1", request.toServiceRequest()))
                .thenReturn(verified);

        ResponseEntity<CapabilityEmbedCredentialPolicyInternalService.EmbedTokenExchangeVerificationView> response =
                controller.verifyTokenExchange(
                        "app-bzjs12",
                        null,
                        "1700000000000",
                        null,
                        "nonce-1",
                        null,
                        "signature-1",
                        null,
                        request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(verified, response.getBody());
        verify(service).verifyTokenExchange("app-bzjs12", "1700000000000", "nonce-1", "signature-1", request.toServiceRequest());
    }
}

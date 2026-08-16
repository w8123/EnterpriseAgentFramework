package com.enterprise.ai.control.knowledge;

import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeProjectIngressControllerTest {

    private final CapabilityProjectRequestVerificationGateway credentialGateway =
            mock(CapabilityProjectRequestVerificationGateway.class);
    private final KnowledgeBizIndexGateway knowledgeGateway = mock(KnowledgeBizIndexGateway.class);
    private final PlatformAuthAuditService auditService = mock(PlatformAuthAuditService.class);
    private final KnowledgeProjectIngressController controller = new KnowledgeProjectIngressController(
            credentialGateway, knowledgeGateway, auditService, new ObjectMapper(), 1_048_576);

    @Test
    void verifiesExactPublicRequestThenForwardsProjectIdentityToKnowledge() {
        String publicPath = "/api/knowledge-ingress/projects/orders/biz-index/orders_idx/batch";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", publicPath);
        byte[] body = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
        String digest = InternalServiceHmac.bodySha256Hex(body);
        when(credentialGateway.verify(any())).thenReturn(
                new CapabilityProjectRequestVerificationGateway.VerifiedProject(
                        9L, "orders", 3L, "hash"));
        when(knowledgeGateway.exchangeProject(
                eq("POST"),
                eq(KnowledgeBizIndexGateway.PROJECT_INTERNAL_ROOT
                        + "/orders/biz-index/orders_idx/batch"),
                eq("orders"), eq("credential:3"), eq(body), eq("application/json")))
                .thenReturn(new KnowledgeBizIndexGateway.GatewayResponse(
                        200, "application/json", "{}".getBytes(StandardCharsets.UTF_8)));

        var response = controller.batch(
                request, "orders", "orders_idx",
                "rak_orders", "1700000000000", "nonce", digest, "f".repeat(64), body);

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void rejectsBodyDigestMismatchBeforeCredentialOwnerCall() {
        String publicPath = "/api/knowledge-ingress/projects/orders/biz-index/orders_idx/upsert";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", publicPath);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> controller.upsert(request, "orders", "orders_idx",
                        "rak_orders", "1700000000000", "nonce", "0".repeat(64),
                        "f".repeat(64), body));

        assertEquals(401, failure.getStatusCode().value());
        verifyNoInteractions(credentialGateway, knowledgeGateway);
    }
}

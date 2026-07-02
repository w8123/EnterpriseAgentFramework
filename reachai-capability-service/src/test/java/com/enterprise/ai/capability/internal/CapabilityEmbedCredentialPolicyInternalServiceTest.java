package com.enterprise.ai.capability.internal;

import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistryCredentialMapper;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityEmbedCredentialPolicyInternalServiceTest {

    @Test
    void verifiesRegistrySignatureAndEmbedPolicyForTokenExchange() throws Exception {
        RegistryCredentialMapper credentialMapper = mock(RegistryCredentialMapper.class);
        RegistrySecurityService registrySecurityService = mock(RegistrySecurityService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        CapabilityEmbedCredentialPolicyInternalService service =
                new CapabilityEmbedCredentialPolicyInternalService(credentialMapper, objectMapper, registrySecurityService);
        RegistryCredentialEntity credential = credential(
                "bzjs12",
                "app-bzjs12",
                objectMapper.writeValueAsString(List.of("http://localhost:5173")),
                objectMapper.writeValueAsString(List.of("orders-bot")),
                900);
        when(registrySecurityService.verifyRequired(
                "bzjs12",
                new RegistrySecurityService.RegistrySignatureHeaders(
                        "app-bzjs12",
                        "1700000000000",
                        "nonce-1",
                        "signature-1"))).thenReturn(credential);

        CapabilityEmbedCredentialPolicyInternalService.EmbedTokenExchangeVerificationView view =
                service.verifyTokenExchange(
                        "app-bzjs12",
                        "1700000000000",
                        "nonce-1",
                        "signature-1",
                        new CapabilityEmbedCredentialPolicyInternalService.EmbedTokenExchangeVerificationRequest(
                                "bzjs12",
                                "orders-bot",
                                "http://localhost:5173"));

        assertEquals("bzjs12", view.projectCode());
        assertEquals("app-bzjs12", view.appKey());
        assertEquals(900, view.tokenTtlSeconds());
    }

    @Test
    void rejectsOriginOutsideEmbedPolicy() throws Exception {
        RegistrySecurityService registrySecurityService = mock(RegistrySecurityService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        CapabilityEmbedCredentialPolicyInternalService service =
                new CapabilityEmbedCredentialPolicyInternalService(
                        mock(RegistryCredentialMapper.class),
                        objectMapper,
                        registrySecurityService);
        RegistryCredentialEntity credential = credential(
                "bzjs12",
                "app-bzjs12",
                objectMapper.writeValueAsString(List.of("https://*.example.com")),
                objectMapper.writeValueAsString(List.of("orders-bot")),
                900);
        when(registrySecurityService.verifyRequired(
                "bzjs12",
                new RegistrySecurityService.RegistrySignatureHeaders(
                        "app-bzjs12",
                        "1700000000000",
                        "nonce-1",
                        "signature-1"))).thenReturn(credential);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.verifyTokenExchange(
                "app-bzjs12",
                "1700000000000",
                "nonce-1",
                "signature-1",
                new CapabilityEmbedCredentialPolicyInternalService.EmbedTokenExchangeVerificationRequest(
                        "bzjs12",
                        "orders-bot",
                        "http://localhost:5173")));

        assertEquals("embed origin is not allowed: http://localhost:5173", ex.getMessage());
    }

    private RegistryCredentialEntity credential(String projectCode,
                                                String appKey,
                                                String allowedOriginsJson,
                                                String allowedAgentIdsJson,
                                                Integer ttlSeconds) {
        RegistryCredentialEntity entity = new RegistryCredentialEntity();
        entity.setProjectCode(projectCode);
        entity.setAppKey(appKey);
        entity.setAllowedOriginsJson(allowedOriginsJson);
        entity.setAllowedAgentIdsJson(allowedAgentIdsJson);
        entity.setTokenTtlSeconds(ttlSeconds);
        entity.setStatus("ACTIVE");
        return entity;
    }
}

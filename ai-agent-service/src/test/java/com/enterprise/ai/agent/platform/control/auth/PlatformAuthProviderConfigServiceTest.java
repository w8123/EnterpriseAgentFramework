package com.enterprise.ai.agent.platform.control.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformAuthProviderConfigServiceTest {

    @Test
    void listProvidersMasksSecretConfigValues() {
        PlatformAuthProviderConfigMapper mapper = mock(PlatformAuthProviderConfigMapper.class);
        PlatformAuthProviderConfigService service = new PlatformAuthProviderConfigService(mapper);
        PlatformAuthProviderConfigEntity provider = new PlatformAuthProviderConfigEntity();
        provider.setId(1L);
        provider.setProviderCode("OIDC");
        provider.setProviderName("Enterprise OIDC");
        provider.setProviderType("OIDC");
        provider.setStatus("ACTIVE");
        provider.setConfigJson("{\"issuerUri\":\"https://iam.example.com\",\"clientSecret\":\"plain-secret\"}");
        when(mapper.selectList(any())).thenReturn(List.of(provider));

        List<PlatformAuthProviderConfigService.ProviderView> views = service.listProviders();

        assertEquals(1, views.size());
        assertTrue(views.get(0).configJson().contains("\"clientSecret\":\"******\""));
        assertTrue(views.get(0).configJson().contains("\"issuerUri\":\"https://iam.example.com\""));
    }
}

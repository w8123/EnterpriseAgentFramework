package com.enterprise.ai.agent.capability.catalog.controller;

import com.enterprise.ai.agent.capability.catalog.controller.AiRegistryController;
import com.enterprise.ai.agent.registry.AiRegistryService;
import com.enterprise.ai.agent.platform.control.identity.PageActionCatalogService;
import com.enterprise.ai.agent.registry.RegistryContracts.ProjectRegisterRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.RegistryProjectResponse;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiRegistryControllerSignatureHeadersTest {

    @Test
    void registerProjectPrefersReachAiSignatureHeaders() {
        AiRegistryService registryService = mock(AiRegistryService.class);
        RegistrySecurityService securityService = mock(RegistrySecurityService.class);
        PageActionCatalogService pageActionCatalogService = mock(PageActionCatalogService.class);
        AiRegistryController controller = new AiRegistryController(registryService, securityService, pageActionCatalogService);

        ProjectRegisterRequest request = new ProjectRegisterRequest(
                "demo",
                "Demo",
                "dev",
                "owner",
                "PRIVATE",
                "http://demo.local",
                "",
                "body-key",
                "body-secret",
                null,
                null,
                null,
                Map.of());
        when(registryService.registerProject(request))
                .thenReturn(new RegistryProjectResponse(1L, "demo", "Demo", "dev", "PRIVATE"));

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-ReachAI-App-Key", "reach-key");
        headers.add("X-ReachAI-Timestamp", "1700000000000");
        headers.add("X-ReachAI-Nonce", "nonce-1");
        headers.add("X-ReachAI-Signature", "reach-signature");
        headers.add("X-EAF-App-Key", "legacy-key");
        headers.add("X-EAF-Timestamp", "legacy-ts");
        headers.add("X-EAF-Nonce", "legacy-nonce");
        headers.add("X-EAF-Signature", "legacy-signature");

        assertEquals(200, controller.registerProject(request, headers).getStatusCode().value());
        verify(securityService).verifyIfConfigured(eq("demo"), argThat(signature ->
                "reach-key".equals(signature.appKey())
                        && "1700000000000".equals(signature.timestamp())
                        && "nonce-1".equals(signature.nonce())
                        && "reach-signature".equals(signature.signature())));
    }
}

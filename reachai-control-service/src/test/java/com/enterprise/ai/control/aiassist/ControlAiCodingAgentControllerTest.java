package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.identity.ControlAiCodingAccessGuard;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ControlAiCodingAgentControllerTest {

    @Test
    void projectKeyProtectsAgentAuthoringEndpoints() throws Exception {
        CapabilityProjectOnboardingClient capabilityClient = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlAiCodingAgentAuthoringService service = mock(ControlAiCodingAgentAuthoringService.class);
        ControlAiCodingAccessGuard guard = new ControlAiCodingAccessGuard(capabilityClient, runtimeClient);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ControlAiCodingAgentController(service))
                .addInterceptors(new ControlAiCodingAccessInterceptor(guard))
                .build();

        mvc.perform(get("/api/ai-coding/projects/7/agents"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void validProjectKeyCanReadAgentAuthoringCatalog() throws Exception {
        CapabilityProjectOnboardingClient capabilityClient = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlAiCodingAgentAuthoringService service = mock(ControlAiCodingAgentAuthoringService.class);
        when(capabilityClient.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "projectCode", "orders",
                "aiCodingAccess", Map.of("enabled", true, "accessKey", "rac_secret")));
        when(service.listAgents(7L)).thenReturn(Map.of(
                "schema", "agent-ai-coding-list.v1",
                "agents", List.of()));
        ControlAiCodingAccessGuard guard = new ControlAiCodingAccessGuard(capabilityClient, runtimeClient);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ControlAiCodingAgentController(service))
                .addInterceptors(new ControlAiCodingAccessInterceptor(guard))
                .build();

        mvc.perform(get("/api/ai-coding/projects/7/agents")
                        .header(ControlAiCodingAccessGuard.AI_CODING_HEADER, "rac_secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema").value("agent-ai-coding-list.v1"));
    }
}

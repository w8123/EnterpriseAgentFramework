package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.compat.RuntimeCompatibilityController;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ControlAiCodingAccessMvcTest {

    @Test
    void projectAiCodingRouteRejectsMissingKeyBeforeControllerRuns() throws Exception {
        CapabilityProjectOnboardingClient capabilityClient = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        MockMvc mockMvc = projectMockMvc(capabilityClient, runtimeClient);

        mockMvc.perform(get("/api/ai-coding/projects/7/manifest"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(capabilityClient, runtimeClient);
    }

    @Test
    void projectAiCodingRouteAllowsValidKey() throws Exception {
        CapabilityProjectOnboardingClient capabilityClient = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        when(capabilityClient.getOnboardingProjectById(7L)).thenReturn(project(7L, true, "rac_secret"));
        MockMvc mockMvc = projectMockMvc(capabilityClient, runtimeClient);

        mockMvc.perform(get("/api/ai-coding/projects/7/manifest")
                        .header(ControlAiCodingAccessGuard.AI_CODING_HEADER, "rac_secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auth.headerName").value(ControlAiCodingAccessGuard.AI_CODING_HEADER));
    }

    @Test
    void workflowAiCodingRouteRejectsMissingKeyBeforeRuntimeLookup() throws Exception {
        CapabilityProjectOnboardingClient capabilityClient = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        MockMvc mockMvc = workflowMockMvc(capabilityClient, runtimeClient);

        mockMvc.perform(post("/api/workflows/wf-1/ai-coding/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"CURRENT\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(capabilityClient, runtimeClient);
    }

    @Test
    void workflowAiCodingRouteAllowsValidKey() throws Exception {
        CapabilityProjectOnboardingClient capabilityClient = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        when(runtimeClient.workflowAiCodingContext("wf-1")).thenReturn(ResponseEntity.ok(Map.of(
                "workflow", Map.of("id", "wf-1", "projectId", 7L)
        )));
        when(capabilityClient.getOnboardingProjectById(7L)).thenReturn(project(7L, true, "rac_secret"));
        when(runtimeClient.validateWorkflowAiCoding("wf-1", Map.of("mode", "CURRENT")))
                .thenReturn(ResponseEntity.ok(Map.of("valid", true)));
        MockMvc mockMvc = workflowMockMvc(capabilityClient, runtimeClient);

        mockMvc.perform(post("/api/workflows/wf-1/ai-coding/validate")
                        .header(ControlAiCodingAccessGuard.AI_CODING_HEADER, "rac_secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"CURRENT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void workflowAiCodingCreateRouteChecksProjectKeyFromBody() throws Exception {
        CapabilityProjectOnboardingClient capabilityClient = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        when(capabilityClient.getOnboardingProjectById(7L)).thenReturn(project(7L, true, "rac_secret"));
        when(runtimeClient.createWorkflowAiCodingWorkflow(Map.of("projectId", 7, "name", "Draft")))
                .thenReturn(ResponseEntity.ok(Map.of("workflowId", "wf-1")));
        ControlAiCodingAccessGuard guard = new ControlAiCodingAccessGuard(capabilityClient, runtimeClient);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new RuntimeCompatibilityController(runtimeClient, guard))
                .addInterceptors(new ControlAiCodingAccessInterceptor(guard))
                .build();

        mockMvc.perform(post("/api/workflows/ai-coding/workflows")
                        .header(ControlAiCodingAccessGuard.AI_CODING_HEADER, "rac_secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":7,\"name\":\"Draft\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value("wf-1"));
    }

    private static MockMvc projectMockMvc(CapabilityProjectOnboardingClient capabilityClient,
                                          RuntimeProxyClient runtimeClient) {
        ControlAiCodingAccessGuard guard = new ControlAiCodingAccessGuard(capabilityClient, runtimeClient);
        return MockMvcBuilders
                .standaloneSetup(new ControlAiCodingProjectController(capabilityClient, runtimeClient))
                .addInterceptors(new ControlAiCodingAccessInterceptor(guard))
                .build();
    }

    private static MockMvc workflowMockMvc(CapabilityProjectOnboardingClient capabilityClient,
                                           RuntimeProxyClient runtimeClient) {
        ControlAiCodingAccessGuard guard = new ControlAiCodingAccessGuard(capabilityClient, runtimeClient);
        return MockMvcBuilders
                .standaloneSetup(new RuntimeCompatibilityController(runtimeClient, null))
                .addInterceptors(new ControlAiCodingAccessInterceptor(guard))
                .build();
    }

    private static Map<String, Object> project(Long projectId, boolean enabled, String key) {
        return Map.of(
                "id", projectId,
                "name", "Orders",
                "projectCode", "orders",
                "projectKind", "REGISTERED",
                "environment", "dev",
                "aiCodingAccess", Map.of("enabled", enabled, "accessKey", key));
    }
}

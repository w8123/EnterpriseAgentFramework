package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigVersionView;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentConfigControllerTest {

    @Test
    void legacyWritesRefuseWithoutCallingTheOwner() throws Exception {
        RuntimeAgentConfigService service = mock(RuntimeAgentConfigService.class);
        RuntimeAgentConfigController controller = new RuntimeAgentConfigController(service);
        Method route = RuntimeAgentConfigController.class
                .getDeclaredMethod("copyToDraft", String.class, Long.class);
        assertArrayEquals(
                new String[] {"/api/agents/{agentId}/config-versions/{configVersionId}/copy-to-draft"},
                route.getAnnotation(PostMapping.class).value());
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        for (var command : java.util.List.of(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/agents/agent-1/config-versions/draft"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agents/agent-1/config-versions/31/publish"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agents/agent-1/config-versions/31/copy-to-draft"))) {
            mvc.perform(command.accept("application/json").contentType("application/json").content("not-json"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("AGENT_DRAFT_REVISION_REQUIRED"));
        }
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    private AgentConfigVersionView view(Long id, int versionNo, String status) {
        LocalDateTime now = LocalDateTime.now();
        return new AgentConfigVersionView(
                id, "agent-1", versionNo, status, "AGENTSCOPE", "prompt", "model-1",
                6, 4, 2, 300_000, 180_000, 30_000, true,
                "DEV_ALLOW_ALL", "ALLOW_LIST", null, null,
                null, now, now, List.of());
    }
}

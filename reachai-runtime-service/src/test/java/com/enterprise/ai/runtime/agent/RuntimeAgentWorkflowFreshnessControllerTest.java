package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowFreshnessViews.WorkflowAgentReferenceView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowFreshnessViews.WorkflowAgentReferencesView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RuntimeAgentWorkflowFreshnessControllerTest {

    private RuntimeAgentWorkflowFreshnessService freshness;
    private RuntimeAgentConfigService configs;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        freshness = mock(RuntimeAgentWorkflowFreshnessService.class);
        configs = mock(RuntimeAgentConfigService.class);
        mvc = MockMvcBuilders.standaloneSetup(new RuntimeAgentWorkflowFreshnessController(freshness, configs)).build();
    }

    @Test
    void listsWorkflowAgentReferences() throws Exception {
        when(freshness.workflowReferences("wf-1")).thenReturn(new WorkflowAgentReferencesView("wf-1", 20L, "1.0.2", 1,
                List.of(new WorkflowAgentReferenceView("agent-1", "订单助手", "orders-agent", 7L, "orders", true,
                        5L, 3, "orders_lookup", 10L, "1.0.1", 20L, "1.0.2", true))));

        mvc.perform(get("/api/workflows/wf-1/agent-references").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestWorkflowVersion").value("1.0.2"))
                .andExpect(jsonPath("$.agents[0].pinnedWorkflowVersion").value("1.0.1"))
                .andExpect(jsonPath("$.agents[0].stale").value(true));
    }

    @Test
    void publishLatestForwardsExpectedActiveConfigAndMapsConflictTo409() throws Exception {
        when(configs.publishLatestWorkflowVersions("agent-1", 5L, "admin-ui"))
                .thenThrow(new RuntimeAgentConfigConflictException("changed"));

        mvc.perform(post("/api/agents/agent-1/workflow-freshness/publish-latest")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedActiveConfigVersionId\":5,\"publishedBy\":\"admin-ui\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(RuntimeAgentConfigConflictException.CODE));
        verify(configs).publishLatestWorkflowVersions("agent-1", 5L, "admin-ui");
    }

    @Test
    void invalidRequestsAndRiskDowngradesAreBadRequests() throws Exception {
        when(configs.publishLatestWorkflowVersions("agent-1", null, null))
                .thenThrow(new IllegalArgumentException("expectedActiveConfigVersionId is required"));
        when(configs.publishLatestWorkflowVersions("agent-2", 1L, null))
                .thenThrow(new RuntimeAgentConfigService.HttpApiRiskDowngradeException());

        mvc.perform(post("/api/agents/agent-1/workflow-freshness/publish-latest").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_WORKFLOW_FRESHNESS_INVALID"));
        mvc.perform(post("/api/agents/agent-2/workflow-freshness/publish-latest")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedActiveConfigVersionId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HTTP_API_WORKFLOW_RISK_DOWNGRADE"));
    }
}

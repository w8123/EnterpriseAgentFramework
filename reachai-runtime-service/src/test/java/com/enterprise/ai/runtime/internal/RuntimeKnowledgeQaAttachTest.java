package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigService;
import com.enterprise.ai.runtime.agent.RuntimeAgentService;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowFreshnessService;
import com.enterprise.ai.runtime.internal.RuntimeKnowledgeQaWorkflowService.AttachKnowledgeRequest;
import com.enterprise.ai.runtime.internal.RuntimeKnowledgeQaWorkflowService.AttachView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowAgentAttachmentPort;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowManagementService;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeKnowledgeQaAttachTest {

    private final RuntimeAgentService agentService = mock(RuntimeAgentService.class);
    private final RuntimeWorkflowManagementService workflows = mock(RuntimeWorkflowManagementService.class);
    private final RuntimeWorkflowAgentAttachmentPort attachmentPort = mock(RuntimeWorkflowAgentAttachmentPort.class);
    private final RuntimeKnowledgeQaWorkflowService service = new RuntimeKnowledgeQaWorkflowService(
            agentService,
            mock(RuntimeAgentConfigService.class),
            mock(RuntimeAgentWorkflowFreshnessService.class),
            workflows,
            mock(RuntimeWorkflowAiCodingService.class),
            attachmentPort,
            new ObjectMapper());

    @Test
    void preparingForTheDraftLeavesTheAgentConfigAlone() {
        when(agentService.findById("agent-1")).thenReturn(Optional.of(new RuntimeAgentView(
                "agent-1", 7L, "yudao-erp", "erp-assistant", "ERP 助手", null, "PROJECT", null, true,
                null, null, null, "DRAFT", null, 0, null, null)));
        when(workflows.findByKeySlug("kb-qa-yudao-erp-erp_policy")).thenReturn(Optional.of(
                RuntimeWorkflowDefinitionView.builder()
                        .id("wf-kb").keySlug("kb-qa-yudao-erp-erp_policy").name("知识问答：ERP 制度")
                        .projectId(7L).status("ACTIVE").build()));

        AttachView view = service.attach("agent-1",
                new AttachKnowledgeRequest("erp_policy", "ERP 制度", "model-1", "tester", false));

        assertEquals("wf-kb", view.workflowId());
        assertEquals("kb-qa-yudao-erp-erp_policy", view.workflowKeySlug());
        assertFalse(view.workflowCreated());
        assertNull(view.agentConfigVersion());
        assertTrue(view.toolDescription().contains("ERP 制度"));
        verify(attachmentPort, never()).attachInKnownProject(anyLong(), anyString(), any());
    }
}

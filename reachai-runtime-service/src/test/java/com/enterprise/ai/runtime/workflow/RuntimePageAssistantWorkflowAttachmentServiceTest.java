package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.workflow.aicoding.AiCodingAttachmentException;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeAgentSupervisorWorkflowAttachmentService;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeAgentSupervisorWorkflowAttachmentService.ActiveConfigRef;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeAgentSupervisorWorkflowAttachmentService.AgentRef;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeAgentSupervisorWorkflowAttachmentService.AttachmentResult;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeAgentSupervisorWorkflowAttachmentService.PageAssistantAttachRequest;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeAgentSupervisorWorkflowAttachmentService.WorkflowRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimePageAssistantWorkflowAttachmentServiceTest {

    @Test
    void delegatesPageAssistantAttachToGenericService() {
        RuntimeAgentSupervisorWorkflowAttachmentService generic =
                mock(RuntimeAgentSupervisorWorkflowAttachmentService.class);
        RuntimePageAssistantWorkflowAttachmentService service =
                new RuntimePageAssistantWorkflowAttachmentService(generic);
        when(generic.attachPageAssistantOnly(eq("wf-1"), any(PageAssistantAttachRequest.class)))
                .thenReturn(new AttachmentResult(
                        "workflow-tool-attachment.v1",
                        7L,
                        "orders",
                        new AgentRef("agent-1", "orders-page-copilot"),
                        new WorkflowRef("wf-1", "orders-page-assistant", "PAGE_ASSISTANT"),
                        new ActiveConfigRef(21L, 2, "ACTIVE"),
                        "orders_page_assistant",
                        true,
                        false));

        RuntimePageAssistantWorkflowAttachment result = service.attachPublishedPageWorkflow("wf-1",
                new RuntimePageAssistantWorkflowAttachRequest(
                        null, "orders", "agent-1", "model-1", "wizard"));

        assertEquals("agent-1", result.agentId());
        assertEquals("orders-page-copilot", result.agentKeySlug());
        assertEquals("wf-1", result.workflowId());
        assertEquals("orders_page_assistant", result.toolName());
        assertEquals(21L, result.configVersionId());
        assertEquals(2, result.configVersionNo());
        assertEquals("ACTIVE", result.configStatus());
        assertTrue(result.published());
        verify(generic).attachPageAssistantOnly(eq("wf-1"), any(PageAssistantAttachRequest.class));
    }

    @Test
    void rejectsNonPageAssistantThroughGenericService() {
        RuntimeAgentSupervisorWorkflowAttachmentService generic =
                mock(RuntimeAgentSupervisorWorkflowAttachmentService.class);
        RuntimePageAssistantWorkflowAttachmentService service =
                new RuntimePageAssistantWorkflowAttachmentService(generic);
        when(generic.attachPageAssistantOnly(eq("wf-1"), any(PageAssistantAttachRequest.class)))
                .thenThrow(new AiCodingAttachmentException(
                        "WORKFLOW_KIND_NOT_SUPPORTED",
                        "Page Assistant attach endpoint only accepts PAGE_ASSISTANT, got: GENERAL"));

        AiCodingAttachmentException ex = assertThrows(AiCodingAttachmentException.class,
                () -> service.attachPublishedPageWorkflow("wf-1",
                        new RuntimePageAssistantWorkflowAttachRequest(
                                null, "orders", "agent-1", "model-1", "wizard")));
        assertEquals("WORKFLOW_KIND_NOT_SUPPORTED", ex.code());
    }
}

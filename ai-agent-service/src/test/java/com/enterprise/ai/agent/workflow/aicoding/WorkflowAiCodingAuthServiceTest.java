package com.enterprise.ai.agent.workflow.aicoding;

import com.enterprise.ai.agent.platform.auth.AiCodingKeyContext;
import com.enterprise.ai.agent.scan.ScanProjectEntity;
import com.enterprise.ai.agent.scan.ScanProjectService;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowAiCodingAuthServiceTest {

    private static final String TEST_KEY = "rac_test";

    @AfterEach
    void clearContext() {
        AiCodingKeyContext.clear();
    }

    @Test
    void requireProjectCodeMatchesAcceptsCaseInsensitiveMatch() {
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("Orders");
        when(scanProjectService.getById(7L)).thenReturn(project);

        WorkflowAiCodingAuthService service = new WorkflowAiCodingAuthService(scanProjectService);
        service.requireProjectCodeMatches(7L, "orders");
    }

    @Test
    void requireProjectCodeMatchesRejectsMismatch() {
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        when(scanProjectService.getById(7L)).thenReturn(project);

        WorkflowAiCodingAuthService service = new WorkflowAiCodingAuthService(scanProjectService);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.requireProjectCodeMatches(7L, "other-project"));
        assertEquals("projectCode does not match projectId", error.getMessage());
    }

    @Test
    void auditActorLabelNeverIncludesRawKey() {
        AiCodingKeyContext.set(TEST_KEY);
        WorkflowAiCodingAuthService service = new WorkflowAiCodingAuthService(mock(ScanProjectService.class));

        String label = service.auditActorLabel(7L);

        assertEquals("aiCodingKey:7", label);
        org.junit.jupiter.api.Assertions.assertFalse(label.contains(TEST_KEY));
    }

    @Test
    void requireAiCodingKeyForWorkflowUsesProjectIdOnly() {
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        when(scanProjectService.matchesAiCodingAccessKey(7L, TEST_KEY)).thenReturn(true);
        AiCodingKeyContext.set(TEST_KEY);
        WorkflowAiCodingAuthService service = new WorkflowAiCodingAuthService(scanProjectService);

        WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
        workflow.setId("wf-1");
        workflow.setProjectId(7L);
        workflow.setProjectCode("orders");

        service.requireAiCodingKeyForWorkflow(workflow);
    }
}

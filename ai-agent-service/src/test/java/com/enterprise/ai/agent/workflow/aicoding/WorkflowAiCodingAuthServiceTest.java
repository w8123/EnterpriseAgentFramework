package com.enterprise.ai.agent.workflow.aicoding;

import com.enterprise.ai.agent.aicoding.AiCodingAccessDeniedException;
import com.enterprise.ai.agent.aicoding.AiCodingAccessGuard;
import com.enterprise.ai.agent.aicoding.AiCodingUnauthorizedException;
import com.enterprise.ai.agent.platform.control.auth.AiCodingKeyContext;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectService;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

        WorkflowAiCodingAuthService service = new WorkflowAiCodingAuthService(
                scanProjectService,
                mock(AiCodingAccessGuard.class));
        service.requireProjectCodeMatches(7L, "orders");
    }

    @Test
    void requireProjectCodeMatchesRejectsMismatch() {
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        when(scanProjectService.getById(7L)).thenReturn(project);

        WorkflowAiCodingAuthService service = new WorkflowAiCodingAuthService(
                scanProjectService,
                mock(AiCodingAccessGuard.class));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.requireProjectCodeMatches(7L, "other-project"));
        assertEquals("projectCode does not match projectId", error.getMessage());
    }

    @Test
    void auditActorLabelNeverIncludesRawKey() {
        AiCodingKeyContext.set(TEST_KEY);
        WorkflowAiCodingAuthService service = new WorkflowAiCodingAuthService(
                mock(ScanProjectService.class),
                mock(AiCodingAccessGuard.class));

        String label = service.auditActorLabel(7L);

        assertEquals("aiCodingKey:7", label);
        org.junit.jupiter.api.Assertions.assertFalse(label.contains(TEST_KEY));
    }

    @Test
    void requireAiCodingKeyForWorkflowUsesProjectIdOnly() {
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        when(scanProjectService.matchesAiCodingAccessKey(7L, TEST_KEY)).thenReturn(true);
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        when(scanProjectService.getById(7L)).thenReturn(project);
        AiCodingKeyContext.set(TEST_KEY);
        WorkflowAiCodingAuthService service = new WorkflowAiCodingAuthService(
                scanProjectService,
                new AiCodingAccessGuard(scanProjectService));

        WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
        workflow.setId("wf-1");
        workflow.setProjectId(7L);
        workflow.setProjectCode("orders");

        service.requireAiCodingKeyForWorkflow(workflow);
    }

    @Test
    void requireAiCodingKeyForProjectDelegatesToSharedAiCodingAccessGuard() {
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        AiCodingAccessGuard accessGuard = mock(AiCodingAccessGuard.class);
        WorkflowAiCodingAuthService service = new WorkflowAiCodingAuthService(scanProjectService, accessGuard);
        AiCodingKeyContext.set(TEST_KEY);

        service.requireAiCodingKeyForProject(7L);

        verify(accessGuard).requireProjectAccess(7L);
    }

    @Test
    void requireAiCodingKeyForProjectLetsSharedGuardHandleMissingKey() {
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        AiCodingAccessGuard accessGuard = mock(AiCodingAccessGuard.class);
        when(accessGuard.requireProjectAccess(7L))
                .thenThrow(new AiCodingUnauthorizedException("aiCodingKey is required"));
        WorkflowAiCodingAuthService service = new WorkflowAiCodingAuthService(scanProjectService, accessGuard);

        WorkflowAiCodingUnauthorizedException error = assertThrows(
                WorkflowAiCodingUnauthorizedException.class,
                () -> service.requireAiCodingKeyForProject(7L));

        assertEquals("aiCodingKey is required", error.getMessage());
        verify(accessGuard).requireProjectAccess(7L);
    }

    @Test
    void requireAiCodingKeyForProjectKeepsWorkflowUnauthorizedContract() {
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        AiCodingAccessGuard accessGuard = mock(AiCodingAccessGuard.class);
        when(accessGuard.requireProjectAccess(7L))
                .thenThrow(new AiCodingUnauthorizedException("aiCodingKey is required"));
        WorkflowAiCodingAuthService service = new WorkflowAiCodingAuthService(scanProjectService, accessGuard);
        AiCodingKeyContext.set(TEST_KEY);

        WorkflowAiCodingUnauthorizedException error = assertThrows(
                WorkflowAiCodingUnauthorizedException.class,
                () -> service.requireAiCodingKeyForProject(7L));

        assertEquals("aiCodingKey is required", error.getMessage());
    }

    @Test
    void requireAiCodingKeyForProjectKeepsWorkflowForbiddenContract() {
        ScanProjectService scanProjectService = mock(ScanProjectService.class);
        AiCodingAccessGuard accessGuard = mock(AiCodingAccessGuard.class);
        when(accessGuard.requireProjectAccess(7L))
                .thenThrow(new AiCodingAccessDeniedException("invalid AI Coding access key for project"));
        WorkflowAiCodingAuthService service = new WorkflowAiCodingAuthService(scanProjectService, accessGuard);
        AiCodingKeyContext.set(TEST_KEY);

        WorkflowAccessDeniedException error = assertThrows(
                WorkflowAccessDeniedException.class,
                () -> service.requireAiCodingKeyForProject(7L));

        assertEquals("invalid AI Coding access key for workflow project", error.getMessage());
    }
}

package com.enterprise.ai.agent.workflow.aicoding;

import com.enterprise.ai.agent.aicoding.AiCodingAccessDeniedException;
import com.enterprise.ai.agent.aicoding.AiCodingAccessGuard;
import com.enterprise.ai.agent.aicoding.AiCodingUnauthorizedException;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectService;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class WorkflowAiCodingAuthService {

    private final ScanProjectService scanProjectService;
    private final AiCodingAccessGuard aiCodingAccessGuard;

    public void requireAiCodingKeyForProject(Long projectId) {
        try {
            aiCodingAccessGuard.requireProjectAccess(projectId);
        } catch (AiCodingUnauthorizedException ex) {
            throw new WorkflowAiCodingUnauthorizedException(messageOrDefault(ex, "aiCodingKey is required"));
        } catch (AiCodingAccessDeniedException ex) {
            throw new WorkflowAccessDeniedException("invalid AI Coding access key for workflow project");
        }
    }

    public void requireAiCodingKeyForWorkflow(WorkflowDefinitionEntity workflow) {
        if (workflow == null) {
            throw new IllegalArgumentException("workflow is required");
        }
        requireAiCodingKeyForProject(workflow.getProjectId());
    }

    public void requireProjectCodeMatches(Long projectId, String projectCode) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        if (!StringUtils.hasText(projectCode)) {
            throw new IllegalArgumentException("projectCode is required");
        }
        ScanProjectEntity project = scanProjectService.getById(projectId);
        String actualProjectCode = project.getProjectCode();
        if (!StringUtils.hasText(actualProjectCode)
                || !actualProjectCode.trim().equalsIgnoreCase(projectCode.trim())) {
            throw new IllegalArgumentException("projectCode does not match projectId");
        }
    }

    public String auditActorLabel(Long projectId) {
        return projectId == null ? "aiCodingKey" : "aiCodingKey:" + projectId;
    }

    private String messageOrDefault(RuntimeException ex, String fallback) {
        return ex.getMessage() == null ? fallback : ex.getMessage();
    }
}

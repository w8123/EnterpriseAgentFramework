package com.enterprise.ai.agent.workflow.aicoding;

import com.enterprise.ai.agent.platform.auth.AiCodingKeyContext;
import com.enterprise.ai.agent.scan.ScanProjectEntity;
import com.enterprise.ai.agent.scan.ScanProjectService;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class WorkflowAiCodingAuthService {

    private final ScanProjectService scanProjectService;

    public void requireAiCodingKeyForProject(Long projectId) {
        String accessKey = AiCodingKeyContext.get();
        if (!StringUtils.hasText(accessKey)) {
            throw new WorkflowAiCodingUnauthorizedException("aiCodingKey is required");
        }
        if (projectId == null || !scanProjectService.matchesAiCodingAccessKey(projectId, accessKey)) {
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
}

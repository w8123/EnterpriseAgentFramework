package com.enterprise.ai.agent.aicoding;

import com.enterprise.ai.agent.platform.control.auth.AiCodingKeyContext;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AiCodingAccessGuard {

    private final ScanProjectService scanProjectService;

    public ScanProjectEntity requireProjectAccess(Long projectId) {
        return requireProjectAccess(projectId, AiCodingKeyContext.get());
    }

    public ScanProjectEntity requireProjectAccess(Long projectId, String accessKey) {
        if (!StringUtils.hasText(accessKey)) {
            throw new AiCodingUnauthorizedException("aiCodingKey is required");
        }
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        String normalizedAccessKey = accessKey.trim();
        if (!scanProjectService.matchesAiCodingAccessKey(projectId, normalizedAccessKey)) {
            throw new AiCodingAccessDeniedException("invalid AI Coding access key for project");
        }
        ScanProjectEntity project = scanProjectService.getById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("project not found: " + projectId);
        }
        return project;
    }

    public String auditActorLabel(Long projectId) {
        return projectId == null ? "aiCodingKey" : "aiCodingKey:" + projectId;
    }
}

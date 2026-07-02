package com.enterprise.ai.capability.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CapabilityProjectLookupService {

    private final ScanProjectMapper scanProjectMapper;

    public Map<String, Object> getProject(String projectCode) {
        if (!StringUtils.hasText(projectCode)) {
            throw new IllegalArgumentException("projectCode is required");
        }
        String normalized = projectCode.trim();
        ScanProjectEntity project = scanProjectMapper.selectOne(Wrappers.<ScanProjectEntity>lambdaQuery()
                .eq(ScanProjectEntity::getProjectCode, normalized)
                .last("LIMIT 1"));
        if (project == null) {
            throw new IllegalArgumentException("Project not found: " + normalized);
        }
        return toSummary(project);
    }

    public Map<String, Object> getProjectById(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        ScanProjectEntity project = scanProjectMapper.selectById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }
        return toSummary(project);
    }

    private Map<String, Object> toSummary(ScanProjectEntity project) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", project.getId());
        body.put("projectCode", project.getProjectCode());
        body.put("name", project.getName());
        body.put("environment", project.getEnvironment());
        body.put("visibility", project.getVisibility());
        return body;
    }
}

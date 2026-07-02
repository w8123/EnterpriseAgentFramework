package com.enterprise.ai.capability.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistryCredentialMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CapabilityProjectOnboardingInternalService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ScanProjectMapper scanProjectMapper;
    private final RegistryCredentialMapper registryCredentialMapper;

    public Map<String, Object> getOnboardingProjectById(Long projectId) {
        ScanProjectEntity project = requireProject(projectId);
        RegistryCredentialEntity credential = primaryCredential(project.getProjectCode());
        Map<String, Object> body = projectBody(project, credential);
        body.put("aiCodingAccess", aiCodingAccessBody(project));
        return body;
    }

    public Map<String, Object> updateAiCodingAccess(Long projectId, Boolean enabledValue, String requestedAccessKey) {
        ScanProjectEntity project = requireProject(projectId);
        boolean enabled = Boolean.TRUE.equals(enabledValue);
        String accessKey = requestedAccessKey == null ? "" : requestedAccessKey.trim();
        if (enabled && !StringUtils.hasText(accessKey)) {
            accessKey = generateAiCodingAccessKey();
        }
        scanProjectMapper.update(null, Wrappers.<ScanProjectEntity>lambdaUpdate()
                .eq(ScanProjectEntity::getId, projectId)
                .set(ScanProjectEntity::getAiCodingAccessEnabled, enabled)
                .set(ScanProjectEntity::getAiCodingAccessKey, enabled ? accessKey : null));
        project.setAiCodingAccessEnabled(enabled);
        project.setAiCodingAccessKey(enabled ? accessKey : null);
        return aiCodingAccessBody(project);
    }

    private ScanProjectEntity requireProject(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        ScanProjectEntity project = scanProjectMapper.selectById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }
        return project;
    }

    private RegistryCredentialEntity primaryCredential(String projectCode) {
        if (!StringUtils.hasText(projectCode)) {
            return null;
        }
        return registryCredentialMapper.selectOne(Wrappers.<RegistryCredentialEntity>lambdaQuery()
                .eq(RegistryCredentialEntity::getProjectCode, projectCode.trim())
                .eq(RegistryCredentialEntity::getStatus, "ACTIVE")
                .orderByDesc(RegistryCredentialEntity::getUpdatedAt)
                .last("limit 1"));
    }

    private Map<String, Object> projectBody(ScanProjectEntity project, RegistryCredentialEntity credential) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", project.getId());
        body.put("name", project.getName());
        body.put("projectCode", project.getProjectCode());
        body.put("projectKind", project.getProjectKind());
        body.put("environment", project.getEnvironment());
        body.put("baseUrl", project.getBaseUrl());
        body.put("contextPath", project.getContextPath());
        body.put("registryAppKey", credential == null ? null : credential.getAppKey());
        body.put("registryCredentialConfigured", credential != null);
        return body;
    }

    private Map<String, Object> aiCodingAccessBody(ScanProjectEntity project) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", Boolean.TRUE.equals(project.getAiCodingAccessEnabled()));
        body.put("accessKey", Boolean.TRUE.equals(project.getAiCodingAccessEnabled())
                ? project.getAiCodingAccessKey()
                : null);
        return body;
    }

    private String generateAiCodingAccessKey() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        return "aic_" + HexFormat.of().formatHex(bytes);
    }
}

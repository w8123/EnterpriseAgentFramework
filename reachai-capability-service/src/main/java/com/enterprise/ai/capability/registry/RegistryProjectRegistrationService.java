package com.enterprise.ai.capability.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.registry.RegistryContracts.ProjectRegisterRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.RegistryProjectResponse;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.agent.registry.RegistryEnrollmentService;
import com.enterprise.ai.agent.registry.RegistryEnrollmentService.RegistryCredential;
import com.enterprise.ai.capability.aicoding.AiCodingAccessKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;

/** Owns project enrollment and registration metadata inside the registry transaction. */
@Service
@RequiredArgsConstructor
public class RegistryProjectRegistrationService {
    private final ScanProjectMapper scanProjectMapper;
    private final RegistrySecurityService registrySecurityService;
    private final RegistryEnrollmentService registryEnrollmentService;

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public RegistryProjectResponse registerProject(ProjectRegisterRequest request,
                                                   String enrollmentToken,
                                                   RegistrySecurityService.RegistrySignatureHeaders signatureHeaders) {
        validateProjectRequest(request);
        String projectCode = RegistryEnrollmentService.normalizeProjectCode(request.projectCode());
        ScanProjectEntity project = findProject(projectCode);
        boolean enrollmentRequested = StringUtils.hasText(enrollmentToken);
        RegistryCredential issuedCredential = null;
        if (project == null) {
            if (!enrollmentRequested) {
                throw new IllegalArgumentException("first registry registration requires a one-time enrollment token");
            }
            registryEnrollmentService.consume(enrollmentToken, projectCode);
            issuedCredential = registryEnrollmentService.issueCredential(null, projectCode);
        } else {
            registrySecurityService.verifyRequired(projectCode, signatureHeaders);
        }
        boolean inserting = project == null;
        boolean registrationChanged = inserting || registrationChanged(project, projectCode, request);
        if (project == null) {
            project = new ScanProjectEntity();
            project.setCreateTime(LocalDateTime.now());
            project.setAiCodingAccessKey(AiCodingAccessKeys.generate());
            project.setAiCodingAccessEnabled(true);
        }
        project.setName(request.name());
        project.setProjectCode(projectCode);
        project.setProjectKind("REGISTERED");
        project.setEnvironment(defaultString(request.environment(), "default"));
        project.setOwner(request.owner());
        project.setVisibility(defaultString(request.visibility(), "PRIVATE"));
        project.setBaseUrl(request.baseUrl());
        project.setContextPath(defaultString(request.contextPath(), ""));
        project.setScanPath("");
        project.setScanType("auto");
        if (registrationChanged) {
            project.setUpdateTime(LocalDateTime.now());
        }
        if (inserting) {
            try {
                scanProjectMapper.insert(project);
            } catch (DuplicateKeyException conflict) {
                // The database owns code uniqueness across all enrollment tokens and project writers.
                throw new IllegalArgumentException("registry project registration conflicts with an existing project");
            }
        } else if (registrationChanged) {
            // Registration owns only these fields; do not write back a stale platform configuration.
            scanProjectMapper.update(null, Wrappers.<ScanProjectEntity>lambdaUpdate()
                    .eq(ScanProjectEntity::getId, project.getId())
                    .set(ScanProjectEntity::getName, project.getName())
                    .set(ScanProjectEntity::getProjectCode, project.getProjectCode())
                    .set(ScanProjectEntity::getProjectKind, project.getProjectKind())
                    .set(ScanProjectEntity::getEnvironment, project.getEnvironment())
                    .set(ScanProjectEntity::getOwner, project.getOwner())
                    .set(ScanProjectEntity::getVisibility, project.getVisibility())
                    .set(ScanProjectEntity::getBaseUrl, project.getBaseUrl())
                    .set(ScanProjectEntity::getContextPath, project.getContextPath())
                    .set(ScanProjectEntity::getScanPath, project.getScanPath())
                    .set(ScanProjectEntity::getScanType, project.getScanType())
                    .set(ScanProjectEntity::getUpdateTime, project.getUpdateTime()));
        }
        if (issuedCredential != null) {
            registrySecurityService.savePrimaryCredential(project.getId(), project.getProjectCode(),
                    issuedCredential.appKey(), issuedCredential.appSecret());
        }
        registrySecurityService.updateEmbedPolicy(
                project.getProjectCode(),
                issuedCredential == null ? signatureHeaders.appKey() : issuedCredential.appKey(),
                request.allowedOrigins(),
                request.allowedAgentIds(),
                request.tokenTtlSeconds());
        return toProjectResponse(project, issuedCredential);
    }

    private boolean registrationChanged(ScanProjectEntity project,
                                        String projectCode,
                                        ProjectRegisterRequest request) {
        return !Objects.equals(project.getName(), request.name())
                || !Objects.equals(project.getProjectCode(), projectCode)
                || !Objects.equals(project.getProjectKind(), "REGISTERED")
                || !Objects.equals(project.getEnvironment(), defaultString(request.environment(), "default"))
                || !Objects.equals(project.getOwner(), request.owner())
                || !Objects.equals(project.getVisibility(), defaultString(request.visibility(), "PRIVATE"))
                || !Objects.equals(project.getBaseUrl(), request.baseUrl())
                || !Objects.equals(project.getContextPath(), defaultString(request.contextPath(), ""))
                || !Objects.equals(project.getScanPath(), "")
                || !Objects.equals(project.getScanType(), "auto");
    }

    private ScanProjectEntity findProject(String projectCode) {
        return scanProjectMapper.selectOne(Wrappers.<ScanProjectEntity>lambdaQuery()
                .eq(ScanProjectEntity::getProjectCode, projectCode)
                .last("limit 1"));
    }

    private RegistryProjectResponse toProjectResponse(ScanProjectEntity project, RegistryCredential issuedCredential) {
        return new RegistryProjectResponse(project.getId(), project.getProjectCode(), project.getName(),
                project.getEnvironment(), project.getVisibility(),
                issuedCredential == null ? null : issuedCredential.appKey(),
                issuedCredential == null ? null : issuedCredential.appSecret());
    }

    private void validateProjectRequest(ProjectRegisterRequest request) {
        if (request == null || !StringUtils.hasText(request.projectCode())) {
            throw new IllegalArgumentException("projectCode 不能为空");
        }
        if (!StringUtils.hasText(request.name())) {
            throw new IllegalArgumentException("项目名称不能为空");
        }
        if (!StringUtils.hasText(request.baseUrl())) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
    }

    private String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}

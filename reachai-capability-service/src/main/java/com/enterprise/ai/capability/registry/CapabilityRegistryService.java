package com.enterprise.ai.capability.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.registry.CapabilityApplyRecordEntity;
import com.enterprise.ai.agent.registry.CapabilityApplyRecordMapper;
import com.enterprise.ai.agent.registry.CapabilityDiffItemEntity;
import com.enterprise.ai.agent.registry.CapabilityDiffItemMapper;
import com.enterprise.ai.agent.registry.CapabilitySnapshotEntity;
import com.enterprise.ai.agent.registry.CapabilitySnapshotMapper;
import com.enterprise.ai.agent.registry.CapabilitySyncLogEntity;
import com.enterprise.ai.agent.registry.CapabilitySyncLogMapper;
import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.ProjectInstanceMapper;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityDiffItem;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityDiffItemDTO;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityRegistration;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityReviewRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySnapshotDTO;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySyncRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySyncResponse;
import com.enterprise.ai.agent.registry.RegistryContracts.FieldDiff;
import com.enterprise.ai.agent.registry.RegistryContracts.InstanceHeartbeatRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.InstanceHeartbeatResponse;
import com.enterprise.ai.agent.registry.RegistryContracts.ProjectRegisterRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.RegistryProjectResponse;
import com.enterprise.ai.agent.registry.RegistryContracts.SdkCapabilityDescriptionSettings;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.agent.registry.RegistryEnrollmentService;
import com.enterprise.ai.agent.registry.RegistryEnrollmentService.RegistryCredential;
import com.enterprise.ai.capability.aicoding.AiCodingAccessKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CapabilityRegistryService {

    private final ScanProjectMapper scanProjectMapper;
    private final ScanProjectToolMapper scanProjectToolMapper;
    private final ToolDefinitionMapper toolDefinitionMapper;
    private final ProjectInstanceMapper instanceMapper;
    private final CapabilitySyncLogMapper syncLogMapper;
    private final CapabilitySnapshotMapper snapshotMapper;
    private final CapabilityDiffItemMapper diffItemMapper;
    private final CapabilityApplyRecordMapper applyRecordMapper;
    private final RegistrySecurityService registrySecurityService;
    private final RegistryEnrollmentService registryEnrollmentService;
    private final ObjectMapper objectMapper;

    @Transactional
    public RegistryProjectResponse registerProject(ProjectRegisterRequest request,
                                                   String enrollmentToken,
                                                   RegistrySecurityService.RegistrySignatureHeaders signatureHeaders) {
        validateProjectRequest(request);
        String projectCode = normalizeCode(request.projectCode());
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
            scanProjectMapper.insert(project);
        } else if (registrationChanged) {
            scanProjectMapper.updateById(project);
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

    @Transactional
    public InstanceHeartbeatResponse heartbeat(String projectCode, InstanceHeartbeatRequest request) {
        ScanProjectEntity project = getProject(projectCode);
        if (request == null || !StringUtils.hasText(request.instanceId())) {
            throw new IllegalArgumentException("instanceId 不能为空");
        }
        ProjectInstanceEntity entity = instanceMapper.selectOne(Wrappers.<ProjectInstanceEntity>lambdaQuery()
                .eq(ProjectInstanceEntity::getProjectCode, project.getProjectCode())
                .eq(ProjectInstanceEntity::getInstanceId, request.instanceId())
                .last("limit 1"));
        if (entity == null) {
            entity = new ProjectInstanceEntity();
            entity.setProjectId(project.getId());
            entity.setProjectCode(project.getProjectCode());
            entity.setInstanceId(request.instanceId());
            entity.setCreatedAt(LocalDateTime.now());
        }
        entity.setBaseUrl(firstText(request.baseUrl(), project.getBaseUrl()));
        entity.setHost(request.host());
        entity.setPort(request.port());
        entity.setAppVersion(request.appVersion());
        entity.setSdkVersion(request.sdkVersion());
        String currentStatus = entity.getStatus();
        entity.setStatus("DISABLED".equalsIgnoreCase(currentStatus) ? "DISABLED" : "ONLINE");
        entity.setMetadataJson(writeJson(request.metadata()));
        entity.setLastHeartbeatAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        if (entity.getId() == null) {
            instanceMapper.insert(entity);
        } else {
            instanceMapper.updateById(entity);
        }
        return new InstanceHeartbeatResponse(entity);
    }

    public List<ProjectInstanceEntity> listInstances(String projectCode) {
        ScanProjectEntity project = getProject(projectCode);
        return instanceMapper.selectList(Wrappers.<ProjectInstanceEntity>lambdaQuery()
                .eq(ProjectInstanceEntity::getProjectId, project.getId())
                .orderByDesc(ProjectInstanceEntity::getLastHeartbeatAt));
    }

    public SdkCapabilityDescriptionSettings getSdkCapabilityDescriptionSettings(String projectCode) {
        ScanProjectEntity project = getProject(projectCode);
        JsonNode root = readSettings(project.getScanSettings());
        List<String> descOrder = filterSdkDescriptionOrder(readStringList(root, "descriptionSourceOrder"));
        List<String> paramOrder = filterSdkParamOrder(readStringList(root, "paramDescriptionSourceOrder"));
        if (descOrder.isEmpty()) {
            descOrder = List.of("SWAGGER_API_OPERATION", "OPENAPI_OPERATION", "METHOD_NAME");
        }
        if (paramOrder.isEmpty()) {
            paramOrder = List.of("SCHEMA_ANNO", "PARAMETER_ANNO", "FIELD_NAME");
        }
        return new SdkCapabilityDescriptionSettings(
                descOrder,
                paramOrder,
                filterEnabledMap(readBooleanMap(root, "descriptionSourceEnabled"), descOrder),
                filterEnabledMap(readBooleanMap(root, "paramDescriptionSourceEnabled"), paramOrder));
    }

    @Transactional
    public void offline(String projectCode, String instanceId) {
        ScanProjectEntity project = getProject(projectCode);
        ProjectInstanceEntity entity = instanceMapper.selectOne(Wrappers.<ProjectInstanceEntity>lambdaQuery()
                .eq(ProjectInstanceEntity::getProjectCode, project.getProjectCode())
                .eq(ProjectInstanceEntity::getInstanceId, instanceId)
                .last("limit 1"));
        if (entity != null) {
            entity.setStatus("OFFLINE");
            entity.setLastHeartbeatAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            instanceMapper.updateById(entity);
        }
    }

    @Transactional
    public ProjectInstanceEntity updateInstanceStatus(String projectCode, String instanceId, String status) {
        ScanProjectEntity project = getProject(projectCode);
        if (!StringUtils.hasText(instanceId)) {
            throw new IllegalArgumentException("instanceId 不能为空");
        }
        String normalizedStatus = normalizeInstanceStatus(status);
        ProjectInstanceEntity entity = getInstance(project, instanceId);
        entity.setStatus(normalizedStatus);
        entity.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(entity);
        return entity;
    }

    @Transactional
    public int purgeOfflineInstances(String projectCode, int minIdleMinutes) {
        ScanProjectEntity project = getProject(projectCode);
        int minutes = Math.max(0, minIdleMinutes);
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(minutes);
        List<ProjectInstanceEntity> targets = instanceMapper.selectList(Wrappers.<ProjectInstanceEntity>lambdaQuery()
                .eq(ProjectInstanceEntity::getProjectCode, project.getProjectCode())
                .in(ProjectInstanceEntity::getStatus, List.of("OFFLINE", "STALE"))
                .and(q -> q.isNull(ProjectInstanceEntity::getLastHeartbeatAt)
                        .or()
                        .lt(ProjectInstanceEntity::getLastHeartbeatAt, cutoff)));
        for (ProjectInstanceEntity entity : targets) {
            instanceMapper.deleteById(entity.getId());
        }
        return targets.size();
    }

    @Transactional
    public CapabilitySyncResponse diff(String projectCode, CapabilitySyncRequest request) {
        return syncInternal(projectCode, request, false);
    }

    @Transactional
    public CapabilitySyncResponse sync(String projectCode, CapabilitySyncRequest request) {
        boolean apply = request == null || request.apply() == null || Boolean.TRUE.equals(request.apply());
        return syncInternal(projectCode, request, apply);
    }

    @Transactional
    public CapabilitySyncResponse apply(String projectCode, CapabilitySyncRequest request) {
        return syncInternal(projectCode, request, true);
    }

    private CapabilitySyncResponse syncInternal(String projectCode, CapabilitySyncRequest request, boolean apply) {
        ScanProjectEntity project = getProject(projectCode);
        List<CapabilityRegistration> capabilities = request == null || request.capabilities() == null
                ? List.of()
                : request.capabilities();
        String syncId = StringUtils.hasText(request == null ? null : request.syncId())
                ? request.syncId().trim()
                : UUID.randomUUID().toString();

        int added = 0;
        int changed = 0;
        int unchanged = 0;
        int applied = 0;
        List<CapabilityDiffItem> items = new ArrayList<>();
        CapabilitySnapshotEntity snapshot = createSnapshot(project, syncId, request, capabilities);
        Set<String> reportedQualifiedNames = capabilities.stream()
                .map(registration -> project.getProjectCode() + ":" + normalizeCapabilityName(registration.name()))
                .collect(Collectors.toSet());
        for (CapabilityRegistration registration : capabilities) {
            String capabilityName = normalizeCapabilityName(registration.name());
            String storageName = storageName(project.getProjectCode(), capabilityName);
            String qualifiedName = project.getProjectCode() + ":" + capabilityName;
            String sdkLocation = "sdk:" + project.getProjectCode().trim() + ":" + capabilityName;

            ScanProjectToolEntity catalogRow = scanProjectToolMapper.selectOne(Wrappers.<ScanProjectToolEntity>lambdaQuery()
                    .eq(ScanProjectToolEntity::getProjectId, project.getId())
                    .eq(ScanProjectToolEntity::getSourceLocation, sdkLocation)
                    .last("limit 1"));
            if (catalogRow == null) {
                catalogRow = scanProjectToolMapper.selectOne(Wrappers.<ScanProjectToolEntity>lambdaQuery()
                        .eq(ScanProjectToolEntity::getProjectId, project.getId())
                        .eq(ScanProjectToolEntity::getName, storageName)
                        .last("limit 1"));
            }
            ToolDefinitionEntity existingTool = null;
            if (catalogRow == null) {
                existingTool = toolDefinitionMapper.selectOne(Wrappers.<ToolDefinitionEntity>lambdaQuery()
                        .eq(ToolDefinitionEntity::getQualifiedName, qualifiedName)
                        .last("limit 1"));
                if (existingTool == null) {
                    existingTool = toolDefinitionMapper.selectOne(Wrappers.<ToolDefinitionEntity>lambdaQuery()
                            .eq(ToolDefinitionEntity::getName, storageName)
                            .last("limit 1"));
                }
            }

            List<FieldDiff> fieldDiffs;
            Long existingToolId;
            if (catalogRow != null) {
                fieldDiffs = fieldDiffsFromCatalog(catalogRow, registration);
                existingToolId = catalogRow.getGlobalToolDefinitionId();
            } else if (existingTool != null) {
                fieldDiffs = fieldDiffsFromDefinition(existingTool, registration);
                existingToolId = existingTool.getId();
            } else {
                fieldDiffs = List.of();
                existingToolId = null;
            }

            String changeType;
            if (catalogRow == null && existingTool == null) {
                changeType = "ADDED";
                added++;
            } else if (fieldDiffs.isEmpty()) {
                changeType = "UNCHANGED";
                unchanged++;
            } else {
                changeType = "CHANGED";
                changed++;
            }
            Map<String, Object> impact = capabilityLocalImpact();
            items.add(new CapabilityDiffItem(qualifiedName, capabilityName, changeType,
                    existingToolId, storageName, fieldDiffs, impact));
            ToolDefinitionEntity beforeGlobalTool = catalogRow == null
                    ? existingTool
                    : findGlobalTool(catalogRow, qualifiedName, existingToolId);
            CapabilityDiffItemEntity diffItem = insertDiffItem(snapshot, project, syncId, qualifiedName, capabilityName, storageName,
                    changeType, existingToolId, fieldDiffs, impact, captureCatalogState(catalogRow, beforeGlobalTool));
            if (apply) {
                applySdkCapabilityCatalogRow(project, registration, storageName, qualifiedName, capabilityName);
                if (!"UNCHANGED".equals(changeType)) {
                    diffItem.setReviewStatus("APPLIED");
                    diffItem.setUpdatedAt(LocalDateTime.now());
                    diffItemMapper.updateById(diffItem);
                    recordReviewDecision(snapshot.getId(), diffItem.getId(), syncId, project, qualifiedName,
                            "APPLY", "SUCCESS", "SYNC", "API目录与可执行 Tool 已更新");
                    applied++;
                }
            }
        }

        int deleted = 0;
        String sdkPrefix = "sdk:" + project.getProjectCode().trim() + ":";
        for (ScanProjectToolEntity row : scanProjectToolMapper.selectList(Wrappers.<ScanProjectToolEntity>lambdaQuery()
                .eq(ScanProjectToolEntity::getProjectId, project.getId()))) {
            String sourceLocation = row.getSourceLocation();
            if (!StringUtils.hasText(sourceLocation) || !sourceLocation.startsWith(sdkPrefix)) {
                continue;
            }
            String capabilityName = sourceLocation.substring(sdkPrefix.length()).trim();
            if (!StringUtils.hasText(capabilityName)) {
                continue;
            }
            String qualifiedName = project.getProjectCode().trim() + ":" + capabilityName;
            if (reportedQualifiedNames.contains(qualifiedName)) {
                continue;
            }
            deleted++;
            Map<String, Object> impact = capabilityLocalImpact();
            items.add(new CapabilityDiffItem(qualifiedName, capabilityName, "DELETED",
                    row.getGlobalToolDefinitionId(), row.getName(), List.of(), impact));
            CapabilityDiffItemEntity diffItem = insertDiffItem(snapshot, project, syncId, qualifiedName,
                    capabilityName, row.getName(), "DELETED", row.getGlobalToolDefinitionId(), List.of(), impact,
                    captureCatalogState(row, findGlobalTool(row, qualifiedName, row.getGlobalToolDefinitionId())));
            if (apply) {
                markCatalogRowRemoved(project, diffItem, row);
                diffItem.setReviewStatus("APPLIED");
                diffItem.setUpdatedAt(LocalDateTime.now());
                diffItemMapper.updateById(diffItem);
                recordReviewDecision(snapshot.getId(), diffItem.getId(), syncId, project, qualifiedName,
                        "CATALOG_REMOVED", "SUCCESS", "SYNC", "SDK 已不再上报，API 目录已标记移除");
                applied++;
            }
        }
        updateSnapshotSummary(snapshot, capabilities.size(), added, changed, unchanged, deleted, apply, applied);
        CapabilitySyncResponse response = new CapabilitySyncResponse(syncId, project.getId(), project.getProjectCode(),
                capabilities.size(), added, changed, unchanged, applied, items);
        writeSyncLog(project, syncId, request == null ? null : request.source(), apply ? "APPLIED" : "DIFFED", response, null);
        return response;
    }

    public List<CapabilitySnapshotDTO> listSnapshots(String projectCode) {
        ScanProjectEntity project = getProject(projectCode);
        return snapshotMapper.selectList(Wrappers.<CapabilitySnapshotEntity>lambdaQuery()
                        .eq(CapabilitySnapshotEntity::getProjectId, project.getId())
                        .orderByDesc(CapabilitySnapshotEntity::getCreatedAt))
                .stream()
                .map(this::toSnapshotDto)
                .toList();
    }

    public List<CapabilityDiffItemDTO> listDiffItems(Long snapshotId) {
        return diffItemMapper.selectList(Wrappers.<CapabilityDiffItemEntity>lambdaQuery()
                        .eq(CapabilityDiffItemEntity::getSnapshotId, snapshotId)
                        .orderByAsc(CapabilityDiffItemEntity::getId))
                .stream()
                .map(this::toDiffItemDto)
                .toList();
    }

    @Transactional
    public CapabilityDiffItemDTO reviewDiffItem(Long diffItemId, CapabilityReviewRequest request) {
        CapabilityDiffItemEntity item = diffItemMapper.selectById(diffItemId);
        if (item == null) {
            throw new IllegalArgumentException("评审项不存在: " + diffItemId);
        }
        CapabilitySnapshotEntity snapshot = snapshotMapper.selectById(item.getSnapshotId());
        if (snapshot == null) {
            throw new IllegalArgumentException("快照不存在: " + item.getSnapshotId());
        }
        ScanProjectEntity project = getProject(item.getProjectCode());
        String action = request == null || !StringUtils.hasText(request.action())
                ? "APPLY"
                : request.action().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("APPLY", "IGNORE").contains(action)) {
            throw new IllegalArgumentException("不支持的评审动作: " + action);
        }
        if (!"PENDING".equalsIgnoreCase(item.getReviewStatus())) {
            throw new IllegalArgumentException("只有待评审的差异项可以应用或忽略");
        }
        if (!"IGNORE".equals(action)) {
            assertCatalogStateUnchanged(project, item);
            if ("DELETED".equalsIgnoreCase(item.getChangeType())) {
                markCatalogRowRemoved(project, item);
                item.setReviewStatus("APPLIED");
                item.setReviewNote(request == null ? null : request.note());
                item.setUpdatedAt(LocalDateTime.now());
                diffItemMapper.updateById(item);
                recordReviewDecision(snapshot.getId(), item.getId(), item.getSyncId(), project, item.getQualifiedName(),
                        "CATALOG_REMOVED", "SUCCESS", defaultString(request == null ? null : request.operator(), "system"),
                        request == null ? null : request.note());
                refreshSnapshotReviewStatus(snapshot);
                return toDiffItemDto(item);
            }
            CapabilityRegistration registration = findRegistration(snapshot, item.getQualifiedName());
            if (registration == null) {
                throw new IllegalArgumentException("快照中找不到能力: " + item.getQualifiedName());
            }
            String capabilityName = normalizeCapabilityName(registration.name());
            String storageName = StringUtils.hasText(item.getStorageName())
                    ? item.getStorageName()
                    : storageName(project.getProjectCode(), capabilityName);
            applySdkCapabilityCatalogRow(project, registration, storageName, item.getQualifiedName(), capabilityName);
            item.setReviewStatus("APPLIED");
            item.setReviewNote(request == null ? null : request.note());
            item.setUpdatedAt(LocalDateTime.now());
            diffItemMapper.updateById(item);
            recordReviewDecision(snapshot.getId(), item.getId(), item.getSyncId(), project, item.getQualifiedName(),
                    "APPLY", "SUCCESS", defaultString(request == null ? null : request.operator(), "system"),
                    request == null ? null : request.note());
            refreshSnapshotReviewStatus(snapshot);
            return toDiffItemDto(item);
        }
        String operator = defaultString(request == null ? null : request.operator(), "system");
        String note = request == null ? null : request.note();
        item.setReviewStatus("IGNORED");
        item.setReviewNote(note);
        item.setUpdatedAt(LocalDateTime.now());
        diffItemMapper.updateById(item);
        recordReviewDecision(snapshot.getId(), item.getId(), item.getSyncId(), project, item.getQualifiedName(),
                "IGNORE", "SUCCESS", operator, note);
        refreshSnapshotReviewStatus(snapshot);
        return toDiffItemDto(item);
    }

    @Transactional
    public CapabilityDiffItemDTO rollbackDiffItem(Long diffItemId, CapabilityReviewRequest request) {
        CapabilityDiffItemEntity item = diffItemMapper.selectById(diffItemId);
        if (item == null) {
            throw new IllegalArgumentException("评审项不存在: " + diffItemId);
        }
        if (!"APPLIED".equalsIgnoreCase(item.getReviewStatus())) {
            throw new IllegalArgumentException("只有已应用的评审项可以回滚");
        }
        if (!StringUtils.hasText(item.getBeforeStateJson())) {
            throw new IllegalArgumentException("该评审项创建时未保存回滚状态，请重新生成差异后再应用");
        }
        CapabilityDiffItemEntity latestApplied = diffItemMapper.selectOne(
                Wrappers.<CapabilityDiffItemEntity>lambdaQuery()
                        .eq(CapabilityDiffItemEntity::getQualifiedName, item.getQualifiedName())
                        .eq(CapabilityDiffItemEntity::getReviewStatus, "APPLIED")
                        .orderByDesc(CapabilityDiffItemEntity::getId)
                        .last("limit 1"));
        if (latestApplied != null && !Objects.equals(latestApplied.getId(), item.getId())) {
            throw new IllegalArgumentException("该能力已有更新的已应用变更，不能覆盖式回滚旧快照");
        }

        CapabilitySnapshotEntity snapshot = snapshotMapper.selectById(item.getSnapshotId());
        if (snapshot == null) {
            throw new IllegalArgumentException("快照不存在: " + item.getSnapshotId());
        }
        ScanProjectEntity project = getProject(item.getProjectCode());
        restoreCatalogState(project, item);

        String operator = defaultString(request == null ? null : request.operator(), "system");
        String note = request == null ? null : request.note();
        item.setReviewStatus("ROLLED_BACK");
        item.setReviewNote(note);
        item.setUpdatedAt(LocalDateTime.now());
        diffItemMapper.updateById(item);
        recordReviewDecision(snapshot.getId(), item.getId(), item.getSyncId(), project, item.getQualifiedName(),
                "ROLLBACK", "SUCCESS", operator, note);
        refreshSnapshotReviewStatus(snapshot);
        return toDiffItemDto(item);
    }

    private ScanProjectEntity getProject(String projectCode) {
        String normalized = normalizeCode(projectCode);
        ScanProjectEntity project = findProject(normalized);
        if (project == null) {
            throw new IllegalArgumentException("项目不存在: " + normalized);
        }
        return project;
    }

    private ScanProjectEntity findProject(String projectCode) {
        return scanProjectMapper.selectOne(Wrappers.<ScanProjectEntity>lambdaQuery()
                .eq(ScanProjectEntity::getProjectCode, projectCode)
                .last("limit 1"));
    }

    private ProjectInstanceEntity getInstance(ScanProjectEntity project, String instanceId) {
        ProjectInstanceEntity entity = instanceMapper.selectOne(Wrappers.<ProjectInstanceEntity>lambdaQuery()
                .eq(ProjectInstanceEntity::getProjectCode, project.getProjectCode())
                .eq(ProjectInstanceEntity::getInstanceId, instanceId)
                .last("limit 1"));
        if (entity == null) {
            throw new IllegalArgumentException("实例不存在: " + instanceId);
        }
        return entity;
    }

    private RegistryProjectResponse toProjectResponse(ScanProjectEntity project, RegistryCredential issuedCredential) {
        return new RegistryProjectResponse(project.getId(), project.getProjectCode(), project.getName(),
                project.getEnvironment(), project.getVisibility(),
                issuedCredential == null ? null : issuedCredential.appKey(),
                issuedCredential == null ? null : issuedCredential.appSecret());
    }

    private CapabilitySnapshotDTO toSnapshotDto(CapabilitySnapshotEntity entity) {
        return new CapabilitySnapshotDTO(entity.getId(), entity.getProjectId(), entity.getProjectCode(),
                entity.getSyncId(), entity.getSource(), entity.getStatus(), entity.getReceived(), entity.getAdded(),
                entity.getChanged(), entity.getUnchanged(), entity.getDeleted(), String.valueOf(entity.getCreatedAt()),
                String.valueOf(entity.getUpdatedAt()));
    }

    private CapabilityDiffItemDTO toDiffItemDto(CapabilityDiffItemEntity entity) {
        return new CapabilityDiffItemDTO(entity.getId(), entity.getSnapshotId(), entity.getSyncId(),
                entity.getProjectCode(), entity.getQualifiedName(), entity.getName(), entity.getStorageName(),
                entity.getChangeType(), entity.getExistingToolId(), entity.getFieldDiffJson(), entity.getImpactJson(),
                entity.getReviewStatus(), entity.getReviewNote(), StringUtils.hasText(entity.getBeforeStateJson()));
    }

    private void refreshSnapshotReviewStatus(CapabilitySnapshotEntity snapshot) {
        List<CapabilityDiffItemEntity> items = diffItemMapper.selectList(
                Wrappers.<CapabilityDiffItemEntity>lambdaQuery()
                        .eq(CapabilityDiffItemEntity::getSnapshotId, snapshot.getId()));
        if (items == null || items.isEmpty()) {
            return;
        }
        long applied = items.stream().filter(item -> "APPLIED".equalsIgnoreCase(item.getReviewStatus())).count();
        long ignored = items.stream().filter(item -> "IGNORED".equalsIgnoreCase(item.getReviewStatus())).count();
        long pending = items.stream().filter(item -> "PENDING".equalsIgnoreCase(item.getReviewStatus())).count();
        String status;
        if (applied == items.size()) {
            status = "APPLIED";
        } else if (ignored == items.size()) {
            status = "IGNORED";
        } else if (pending == items.size()) {
            status = "PENDING";
        } else {
            status = "PARTIAL";
        }
        if (!Objects.equals(snapshot.getStatus(), status)) {
            snapshot.setStatus(status);
            snapshot.setUpdatedAt(LocalDateTime.now());
            snapshotMapper.updateById(snapshot);
        }
    }

    private CapabilitySnapshotEntity createSnapshot(ScanProjectEntity project,
                                                    String syncId,
                                                    CapabilitySyncRequest request,
                                                    List<CapabilityRegistration> capabilities) {
        CapabilitySnapshotEntity snapshot = new CapabilitySnapshotEntity();
        snapshot.setProjectId(project.getId());
        snapshot.setProjectCode(project.getProjectCode());
        snapshot.setSyncId(syncId);
        snapshot.setSource(defaultString(request == null ? null : request.source(), "SDK"));
        snapshot.setStatus("PENDING");
        snapshot.setPayloadJson(writeJson(new CapabilitySyncRequest(syncId,
                request == null ? null : request.source(),
                Boolean.FALSE,
                capabilities)));
        snapshot.setReceived(capabilities.size());
        snapshot.setAdded(0);
        snapshot.setChanged(0);
        snapshot.setUnchanged(0);
        snapshot.setDeleted(0);
        snapshot.setCreatedAt(LocalDateTime.now());
        snapshot.setUpdatedAt(LocalDateTime.now());
        snapshotMapper.insert(snapshot);
        return snapshot;
    }

    private CapabilityDiffItemEntity insertDiffItem(CapabilitySnapshotEntity snapshot,
                                                    ScanProjectEntity project,
                                                    String syncId,
                                                    String qualifiedName,
                                                    String name,
                                                    String storageName,
                                                    String changeType,
                                                    Long existingToolId,
                                                    List<FieldDiff> fieldDiffs,
                                                    Map<String, Object> impact,
                                                    String beforeStateJson) {
        CapabilityDiffItemEntity item = new CapabilityDiffItemEntity();
        item.setSnapshotId(snapshot.getId());
        item.setSyncId(syncId);
        item.setProjectId(project.getId());
        item.setProjectCode(project.getProjectCode());
        item.setQualifiedName(qualifiedName);
        item.setName(name);
        item.setStorageName(storageName);
        item.setChangeType(changeType);
        item.setExistingToolId(existingToolId);
        item.setFieldDiffJson(writeJson(fieldDiffs));
        item.setImpactJson(writeJson(impact));
        item.setBeforeStateJson(beforeStateJson);
        item.setReviewStatus("UNCHANGED".equals(changeType) ? "APPLIED" : "PENDING");
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        diffItemMapper.insert(item);
        return item;
    }

    private void updateSnapshotSummary(CapabilitySnapshotEntity snapshot,
                                       int received,
                                       int added,
                                       int changed,
                                       int unchanged,
                                       int deleted,
                                       boolean apply,
                                       int applied) {
        snapshot.setReceived(received);
        snapshot.setAdded(added);
        snapshot.setChanged(changed);
        snapshot.setUnchanged(unchanged);
        snapshot.setDeleted(deleted);
        snapshot.setStatus(apply && applied >= added + changed + deleted ? "APPLIED" : "PENDING");
        snapshot.setUpdatedAt(LocalDateTime.now());
        snapshotMapper.updateById(snapshot);
    }

    private void applySdkCapabilityCatalogRow(ScanProjectEntity project,
                                              CapabilityRegistration registration,
                                              String storageName,
                                              String qualifiedName,
                                              String capabilityName) {
        String sourceLocation = "sdk:" + project.getProjectCode().trim() + ":" + capabilityName;
        ScanProjectToolEntity existing = scanProjectToolMapper.selectOne(Wrappers.<ScanProjectToolEntity>lambdaQuery()
                .eq(ScanProjectToolEntity::getProjectId, project.getId())
                .eq(ScanProjectToolEntity::getSourceLocation, sourceLocation)
                .last("limit 1"));
        if (existing == null) {
            existing = scanProjectToolMapper.selectOne(Wrappers.<ScanProjectToolEntity>lambdaQuery()
                    .eq(ScanProjectToolEntity::getProjectId, project.getId())
                    .eq(ScanProjectToolEntity::getName, storageName)
                    .last("limit 1"));
        }
        boolean inserting = existing == null;
        ScanProjectToolEntity row = inserting ? new ScanProjectToolEntity() : existing;
        if (inserting) {
            row.setProjectId(project.getId());
            row.setModuleId(null);
            row.setName(storageName);
            row.setGlobalToolDefinitionId(null);
            row.setCreateTime(LocalDateTime.now());
        }
        row.setTitle(firstText(registration.title(), registration.name()));
        row.setDescription(firstText(registration.description(), registration.title(), registration.name()));
        row.setParametersJson(writeJson(registration.parameters()));
        row.setSource("scanner");
        row.setSourceLocation(sourceLocation);
        row.setHttpMethod(firstText(registration.httpMethod(), "POST"));
        row.setBaseUrl(firstText(registration.baseUrl(), project.getBaseUrl()));
        row.setContextPath(firstText(registration.contextPath(), project.getContextPath()));
        row.setEndpointPath(registration.endpointPath());
        row.setRequestBodyType(registration.requestBodyType());
        row.setResponseType(registration.responseType());
        row.setEnabled(registration.enabled() == null || Boolean.TRUE.equals(registration.enabled()));
        row.setCapabilityMetadataJson(writeJson(mergeSdkMetadata(registration)));
        row.setRemovedFromSource(false);
        row.setRemovedAt(null);
        row.setUpdateTime(LocalDateTime.now());
        if (inserting) {
            scanProjectToolMapper.insert(row);
        } else {
            scanProjectToolMapper.updateById(row);
        }
        ensureSdkCapabilityGlobalTool(project, row, registration, qualifiedName);
    }

    /**
     * SDK 同步的 apply 语义不能只停留在扫描目录。已应用的 SDK 能力必须同时具备可执行的
     * ToolDefinition，否则 Workflow / Agent 会把“已上报”误当成“可运行”。
     */
    private void ensureSdkCapabilityGlobalTool(ScanProjectEntity project,
                                                ScanProjectToolEntity scanTool,
                                                CapabilityRegistration registration,
                                                String qualifiedName) {
        ToolDefinitionEntity globalTool = scanTool.getGlobalToolDefinitionId() == null
                ? null
                : toolDefinitionMapper.selectById(scanTool.getGlobalToolDefinitionId());
        if (globalTool == null) {
            globalTool = toolDefinitionMapper.selectOne(Wrappers.<ToolDefinitionEntity>lambdaQuery()
                    .eq(ToolDefinitionEntity::getQualifiedName, qualifiedName)
                    .last("limit 1"));
        }
        if (globalTool == null) {
            globalTool = toolDefinitionMapper.selectOne(Wrappers.<ToolDefinitionEntity>lambdaQuery()
                    .eq(ToolDefinitionEntity::getProjectId, project.getId())
                    .eq(ToolDefinitionEntity::getSourceLocation, scanTool.getSourceLocation())
                    .last("limit 1"));
        }

        boolean inserting = globalTool == null;
        if (inserting) {
            globalTool = new ToolDefinitionEntity();
            globalTool.setCreateTime(LocalDateTime.now());
        }
        applySdkCapabilityToGlobalTool(project, scanTool, registration, qualifiedName, globalTool);
        globalTool.setUpdateTime(LocalDateTime.now());
        if (inserting) {
            toolDefinitionMapper.insert(globalTool);
        } else {
            toolDefinitionMapper.updateById(globalTool);
        }

        if (!Objects.equals(scanTool.getGlobalToolDefinitionId(), globalTool.getId())) {
            scanTool.setGlobalToolDefinitionId(globalTool.getId());
            scanTool.setUpdateTime(LocalDateTime.now());
            scanProjectToolMapper.updateById(scanTool);
        }
    }

    private void applySdkCapabilityToGlobalTool(ScanProjectEntity project,
                                                 ScanProjectToolEntity scanTool,
                                                 CapabilityRegistration registration,
                                                 String qualifiedName,
                                                 ToolDefinitionEntity globalTool) {
        globalTool.setName(scanTool.getName());
        globalTool.setTitle(scanTool.getTitle());
        globalTool.setDescription(scanTool.getDescription());
        globalTool.setAiDescription(scanTool.getAiDescription());
        globalTool.setCapabilityMetadataJson(scanTool.getCapabilityMetadataJson());
        globalTool.setParametersJson(scanTool.getParametersJson());
        globalTool.setSource("scanner");
        globalTool.setSourceLocation(scanTool.getSourceLocation());
        globalTool.setHttpMethod(scanTool.getHttpMethod());
        globalTool.setBaseUrl(scanTool.getBaseUrl());
        globalTool.setContextPath(scanTool.getContextPath());
        globalTool.setEndpointPath(scanTool.getEndpointPath());
        globalTool.setRequestBodyType(scanTool.getRequestBodyType());
        globalTool.setResponseType(scanTool.getResponseType());
        globalTool.setProjectId(project.getId());
        globalTool.setProjectCode(project.getProjectCode());
        globalTool.setQualifiedName(qualifiedName);
        globalTool.setModuleId(scanTool.getModuleId());
        globalTool.setEnabled(Boolean.TRUE.equals(scanTool.getEnabled()));
        globalTool.setSideEffect(sdkSideEffect(registration.sideEffect()));
    }

    private String sdkSideEffect(String value) {
        if (!StringUtils.hasText(value)) {
            return "WRITE";
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "READ", "READ_ONLY", "NONE" -> "READ_ONLY";
            case "IDEMPOTENT_WRITE" -> "IDEMPOTENT_WRITE";
            case "IRREVERSIBLE" -> "IRREVERSIBLE";
            default -> "WRITE";
        };
    }

    private void markCatalogRowRemoved(ScanProjectEntity project, CapabilityDiffItemEntity item) {
        markCatalogRowRemoved(project, item, null);
    }

    private void markCatalogRowRemoved(ScanProjectEntity project,
                                       CapabilityDiffItemEntity item,
                                       ScanProjectToolEntity knownRow) {
        ScanProjectToolEntity row = knownRow == null ? findCatalogRow(project, item) : knownRow;
        if (row == null) {
            return;
        }
        row.setEnabled(false);
        row.setRemovedFromSource(true);
        row.setRemovedAt(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        scanProjectToolMapper.updateById(row);

        ToolDefinitionEntity globalTool = findGlobalTool(row, item.getQualifiedName(), item.getExistingToolId());
        if (globalTool != null && !Boolean.FALSE.equals(globalTool.getEnabled())) {
            globalTool.setEnabled(false);
            globalTool.setUpdateTime(LocalDateTime.now());
            toolDefinitionMapper.updateById(globalTool);
        }
    }

    private ScanProjectToolEntity findCatalogRow(ScanProjectEntity project, CapabilityDiffItemEntity item) {
        String capabilityName = StringUtils.hasText(item.getName()) ? item.getName().trim() : null;
        String sourceLocation = StringUtils.hasText(capabilityName)
                ? "sdk:" + project.getProjectCode().trim() + ":" + capabilityName
                : null;
        ScanProjectToolEntity row = null;
        if (StringUtils.hasText(sourceLocation)) {
            row = scanProjectToolMapper.selectOne(Wrappers.<ScanProjectToolEntity>lambdaQuery()
                    .eq(ScanProjectToolEntity::getProjectId, project.getId())
                    .eq(ScanProjectToolEntity::getSourceLocation, sourceLocation)
                    .last("limit 1"));
        }
        if (row == null && StringUtils.hasText(item.getStorageName())) {
            row = scanProjectToolMapper.selectOne(Wrappers.<ScanProjectToolEntity>lambdaQuery()
                    .eq(ScanProjectToolEntity::getProjectId, project.getId())
                    .eq(ScanProjectToolEntity::getName, item.getStorageName())
                    .last("limit 1"));
        }
        return row;
    }

    private ToolDefinitionEntity findGlobalTool(ScanProjectToolEntity row,
                                                 String qualifiedName,
                                                 Long fallbackToolId) {
        Long toolId = row != null && row.getGlobalToolDefinitionId() != null
                ? row.getGlobalToolDefinitionId()
                : fallbackToolId;
        ToolDefinitionEntity tool = toolId == null ? null : toolDefinitionMapper.selectById(toolId);
        if (tool == null && StringUtils.hasText(qualifiedName)) {
            tool = toolDefinitionMapper.selectOne(Wrappers.<ToolDefinitionEntity>lambdaQuery()
                    .eq(ToolDefinitionEntity::getQualifiedName, qualifiedName)
                    .last("limit 1"));
        }
        return tool;
    }

    private String captureCatalogState(ScanProjectToolEntity scanTool, ToolDefinitionEntity globalTool) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("scanTool", scanTool == null ? null : scanToolState(scanTool));
        state.put("globalTool", globalTool == null ? null : globalToolState(globalTool));
        return writeJson(state);
    }

    private void assertCatalogStateUnchanged(ScanProjectEntity project, CapabilityDiffItemEntity item) {
        if (!StringUtils.hasText(item.getBeforeStateJson())) {
            return;
        }
        JsonNode expected;
        JsonNode current;
        try {
            expected = objectMapper.readTree(item.getBeforeStateJson());
            ScanProjectToolEntity currentScan = findCatalogRow(project, item);
            ToolDefinitionEntity currentGlobal = findGlobalTool(
                    currentScan,
                    item.getQualifiedName(),
                    item.getExistingToolId());
            current = objectMapper.readTree(captureCatalogState(currentScan, currentGlobal));
        } catch (Exception ex) {
            throw new IllegalArgumentException("评审项应用前状态无法校验", ex);
        }
        if (!Objects.equals(expected, current)) {
            throw new IllegalArgumentException("能力目录在生成差异后已变化，请刷新 SDK 快照后重新评审");
        }
    }

    private Map<String, Object> scanToolState(ScanProjectToolEntity row) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", row.getId());
        state.put("projectId", row.getProjectId());
        state.put("moduleId", row.getModuleId());
        state.put("name", row.getName());
        state.put("title", row.getTitle());
        state.put("description", row.getDescription());
        state.put("parametersJson", row.getParametersJson());
        state.put("source", row.getSource());
        state.put("sourceLocation", row.getSourceLocation());
        state.put("httpMethod", row.getHttpMethod());
        state.put("baseUrl", row.getBaseUrl());
        state.put("contextPath", row.getContextPath());
        state.put("endpointPath", row.getEndpointPath());
        state.put("requestBodyType", row.getRequestBodyType());
        state.put("responseType", row.getResponseType());
        state.put("aiDescription", row.getAiDescription());
        state.put("capabilityMetadataJson", row.getCapabilityMetadataJson());
        state.put("sensitiveDataJson", row.getSensitiveDataJson());
        state.put("enabled", row.getEnabled());
        state.put("globalToolDefinitionId", row.getGlobalToolDefinitionId());
        state.put("removedFromSource", row.getRemovedFromSource());
        state.put("removedAt", row.getRemovedAt() == null ? null : row.getRemovedAt().toString());
        return state;
    }

    private Map<String, Object> globalToolState(ToolDefinitionEntity tool) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", tool.getId());
        state.put("name", tool.getName());
        state.put("title", tool.getTitle());
        state.put("description", tool.getDescription());
        state.put("aiDescription", tool.getAiDescription());
        state.put("capabilityMetadataJson", tool.getCapabilityMetadataJson());
        state.put("parametersJson", tool.getParametersJson());
        state.put("source", tool.getSource());
        state.put("sourceLocation", tool.getSourceLocation());
        state.put("httpMethod", tool.getHttpMethod());
        state.put("baseUrl", tool.getBaseUrl());
        state.put("contextPath", tool.getContextPath());
        state.put("endpointPath", tool.getEndpointPath());
        state.put("requestBodyType", tool.getRequestBodyType());
        state.put("responseType", tool.getResponseType());
        state.put("projectId", tool.getProjectId());
        state.put("projectCode", tool.getProjectCode());
        state.put("qualifiedName", tool.getQualifiedName());
        state.put("moduleId", tool.getModuleId());
        state.put("enabled", tool.getEnabled());
        state.put("sideEffect", tool.getSideEffect());
        return state;
    }

    private void restoreCatalogState(ScanProjectEntity project, CapabilityDiffItemEntity item) {
        JsonNode root;
        try {
            root = objectMapper.readTree(item.getBeforeStateJson());
        } catch (Exception ex) {
            throw new IllegalArgumentException("评审项回滚状态无法解析", ex);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("评审项回滚状态无法解析");
        }

        ScanProjectToolEntity currentScan = findCatalogRow(project, item);
        ToolDefinitionEntity currentGlobal = findGlobalTool(currentScan, item.getQualifiedName(), item.getExistingToolId());
        JsonNode globalState = root.get("globalTool");
        if (globalState != null && globalState.isObject()) {
            ToolDefinitionEntity restored = currentGlobal == null ? new ToolDefinitionEntity() : currentGlobal;
            restoreGlobalTool(restored, globalState);
            restored.setUpdateTime(LocalDateTime.now());
            if (restored.getId() == null) {
                restored.setCreateTime(LocalDateTime.now());
                toolDefinitionMapper.insert(restored);
            } else {
                toolDefinitionMapper.updateById(restored);
            }
        } else if (currentGlobal != null) {
            currentGlobal.setEnabled(false);
            currentGlobal.setUpdateTime(LocalDateTime.now());
            toolDefinitionMapper.updateById(currentGlobal);
        }

        JsonNode scanState = root.get("scanTool");
        if (scanState != null && scanState.isObject()) {
            ScanProjectToolEntity restored = currentScan == null ? new ScanProjectToolEntity() : currentScan;
            restoreScanTool(restored, scanState);
            restored.setUpdateTime(LocalDateTime.now());
            if (restored.getId() == null) {
                restored.setCreateTime(LocalDateTime.now());
                scanProjectToolMapper.insert(restored);
            } else {
                scanProjectToolMapper.updateById(restored);
            }
        } else if (currentScan != null) {
            currentScan.setEnabled(false);
            currentScan.setRemovedFromSource(true);
            currentScan.setRemovedAt(LocalDateTime.now());
            currentScan.setUpdateTime(LocalDateTime.now());
            scanProjectToolMapper.updateById(currentScan);
        }
    }

    private void restoreScanTool(ScanProjectToolEntity row, JsonNode state) {
        row.setId(nullableLong(state, "id"));
        row.setProjectId(nullableLong(state, "projectId"));
        row.setModuleId(nullableLong(state, "moduleId"));
        row.setName(nullableText(state, "name"));
        row.setTitle(nullableText(state, "title"));
        row.setDescription(nullableText(state, "description"));
        row.setParametersJson(nullableText(state, "parametersJson"));
        row.setSource(nullableText(state, "source"));
        row.setSourceLocation(nullableText(state, "sourceLocation"));
        row.setHttpMethod(nullableText(state, "httpMethod"));
        row.setBaseUrl(nullableText(state, "baseUrl"));
        row.setContextPath(nullableText(state, "contextPath"));
        row.setEndpointPath(nullableText(state, "endpointPath"));
        row.setRequestBodyType(nullableText(state, "requestBodyType"));
        row.setResponseType(nullableText(state, "responseType"));
        row.setAiDescription(nullableText(state, "aiDescription"));
        row.setCapabilityMetadataJson(nullableText(state, "capabilityMetadataJson"));
        row.setSensitiveDataJson(nullableText(state, "sensitiveDataJson"));
        row.setEnabled(nullableBoolean(state, "enabled"));
        row.setGlobalToolDefinitionId(nullableLong(state, "globalToolDefinitionId"));
        row.setRemovedFromSource(nullableBoolean(state, "removedFromSource"));
        row.setRemovedAt(nullableDateTime(state, "removedAt"));
    }

    private void restoreGlobalTool(ToolDefinitionEntity tool, JsonNode state) {
        tool.setId(nullableLong(state, "id"));
        tool.setName(nullableText(state, "name"));
        tool.setTitle(nullableText(state, "title"));
        tool.setDescription(nullableText(state, "description"));
        tool.setAiDescription(nullableText(state, "aiDescription"));
        tool.setCapabilityMetadataJson(nullableText(state, "capabilityMetadataJson"));
        tool.setParametersJson(nullableText(state, "parametersJson"));
        tool.setSource(nullableText(state, "source"));
        tool.setSourceLocation(nullableText(state, "sourceLocation"));
        tool.setHttpMethod(nullableText(state, "httpMethod"));
        tool.setBaseUrl(nullableText(state, "baseUrl"));
        tool.setContextPath(nullableText(state, "contextPath"));
        tool.setEndpointPath(nullableText(state, "endpointPath"));
        tool.setRequestBodyType(nullableText(state, "requestBodyType"));
        tool.setResponseType(nullableText(state, "responseType"));
        tool.setProjectId(nullableLong(state, "projectId"));
        tool.setProjectCode(nullableText(state, "projectCode"));
        tool.setQualifiedName(nullableText(state, "qualifiedName"));
        tool.setModuleId(nullableLong(state, "moduleId"));
        tool.setEnabled(nullableBoolean(state, "enabled"));
        tool.setSideEffect(nullableText(state, "sideEffect"));
    }

    private String nullableText(JsonNode state, String field) {
        JsonNode value = state.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Long nullableLong(JsonNode state, String field) {
        JsonNode value = state.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private Boolean nullableBoolean(JsonNode state, String field) {
        JsonNode value = state.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    private LocalDateTime nullableDateTime(JsonNode state, String field) {
        String value = nullableText(state, field);
        return StringUtils.hasText(value) ? LocalDateTime.parse(value) : null;
    }

    private List<FieldDiff> fieldDiffsFromCatalog(ScanProjectToolEntity row, CapabilityRegistration registration) {
        List<FieldDiff> diffs = new ArrayList<>();
        addDiff(diffs, "title", row.getTitle(), firstText(registration.title(), registration.name()));
        addDiff(diffs, "description", row.getDescription(), firstText(registration.description(), registration.title(), registration.name()));
        addDiff(diffs, "httpMethod", row.getHttpMethod(), firstText(registration.httpMethod(), "POST"));
        addDiff(diffs, "baseUrl", row.getBaseUrl(), registration.baseUrl());
        addDiff(diffs, "contextPath", row.getContextPath(), registration.contextPath());
        addDiff(diffs, "endpointPath", row.getEndpointPath(), registration.endpointPath());
        addDiff(diffs, "requestBodyType", row.getRequestBodyType(), registration.requestBodyType());
        addDiff(diffs, "responseType", row.getResponseType(), registration.responseType());
        addDiff(diffs, "enabled", row.getEnabled(), registration.enabled());
        addDiff(diffs, "parameters", row.getParametersJson(), writeJson(registration.parameters()));
        addDiff(diffs, "metadata", row.getCapabilityMetadataJson(), writeJson(mergeSdkMetadata(registration)));
        return diffs;
    }

    private List<FieldDiff> fieldDiffsFromDefinition(ToolDefinitionEntity entity, CapabilityRegistration registration) {
        List<FieldDiff> diffs = new ArrayList<>();
        addDiff(diffs, "title", entity.getTitle(), firstText(registration.title(), registration.name()));
        addDiff(diffs, "description", entity.getDescription(), firstText(registration.description(), registration.title(), registration.name()));
        addDiff(diffs, "httpMethod", entity.getHttpMethod(), firstText(registration.httpMethod(), "POST"));
        addDiff(diffs, "baseUrl", entity.getBaseUrl(), registration.baseUrl());
        addDiff(diffs, "contextPath", entity.getContextPath(), registration.contextPath());
        addDiff(diffs, "endpointPath", entity.getEndpointPath(), registration.endpointPath());
        addDiff(diffs, "requestBodyType", entity.getRequestBodyType(), registration.requestBodyType());
        addDiff(diffs, "responseType", entity.getResponseType(), registration.responseType());
        addDiff(diffs, "sideEffect", entity.getSideEffect(), firstText(registration.sideEffect(), entity.getSideEffect()));
        addDiff(diffs, "enabled", entity.getEnabled(), registration.enabled());
        addDiff(diffs, "parameters", entity.getParametersJson(), writeJson(registration.parameters()));
        addDiff(diffs, "metadata", entity.getCapabilityMetadataJson(), writeJson(registration.metadata()));
        return diffs;
    }

    private Object mergeSdkMetadata(CapabilityRegistration registration) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (registration.metadata() != null) {
            metadata.putAll(registration.metadata());
        }
        if (StringUtils.hasText(registration.sideEffect())) {
            metadata.put("sideEffect", registration.sideEffect().trim());
        }
        return metadata.isEmpty() ? null : metadata;
    }

    private void addDiff(List<FieldDiff> diffs, String field, Object oldValue, Object newValue) {
        if (newValue == null) {
            return;
        }
        if (!Objects.equals(String.valueOf(oldValue), String.valueOf(newValue))) {
            diffs.add(new FieldDiff(field, oldValue, newValue));
        }
    }

    private Map<String, Object> capabilityLocalImpact() {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("agents", List.of());
        impact.put("aclRuleIds", List.of());
        impact.put("mcp", "MCP visibility is resolved by Control service");
        impact.put("a2a", "A2A impact is resolved by Control service");
        return impact;
    }

    private void writeSyncLog(ScanProjectEntity project,
                              String syncId,
                              String source,
                              String status,
                              CapabilitySyncResponse response,
                              String error) {
        CapabilitySyncLogEntity log = new CapabilitySyncLogEntity();
        log.setProjectId(project.getId());
        log.setProjectCode(project.getProjectCode());
        log.setSyncId(syncId);
        log.setSource(defaultString(source, "SDK"));
        log.setStatus(status);
        log.setSummaryJson(writeJson(response));
        log.setErrorMessage(error);
        log.setCreatedAt(LocalDateTime.now());
        syncLogMapper.insert(log);
    }

    private CapabilityRegistration findRegistration(CapabilitySnapshotEntity snapshot, String qualifiedName) {
        try {
            CapabilitySyncRequest request = readSnapshotSyncRequest(snapshot);
            if (request.capabilities() == null) {
                return null;
            }
            return request.capabilities().stream()
                    .filter(registration -> (snapshot.getProjectCode() + ":" + normalizeCapabilityName(registration.name()))
                            .equals(qualifiedName))
                    .findFirst()
                    .orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }

    private CapabilitySyncRequest readSnapshotSyncRequest(CapabilitySnapshotEntity snapshot) {
        if (snapshot == null || !StringUtils.hasText(snapshot.getPayloadJson())) {
            throw new IllegalArgumentException("SDK 同步快照无法解析");
        }
        try {
            CapabilitySyncRequest request = objectMapper.readValue(snapshot.getPayloadJson(), CapabilitySyncRequest.class);
            if (request == null) {
                throw new IllegalArgumentException("SDK 同步快照无法解析");
            }
            return request;
        } catch (Exception ex) {
            throw new IllegalArgumentException("SDK 同步快照无法解析", ex);
        }
    }

    private void recordReviewDecision(Long snapshotId,
                                      Long diffItemId,
                                      String syncId,
                                      ScanProjectEntity project,
                                      String qualifiedName,
                                      String action,
                                      String status,
                                      String operator,
                                      String message) {
        CapabilityApplyRecordEntity record = new CapabilityApplyRecordEntity();
        record.setSnapshotId(snapshotId);
        record.setDiffItemId(diffItemId);
        record.setSyncId(syncId);
        record.setProjectId(project.getId());
        record.setProjectCode(project.getProjectCode());
        record.setQualifiedName(qualifiedName);
        record.setAction(action);
        record.setStatus(status);
        record.setOperator(operator);
        record.setMessage(message);
        record.setCreatedAt(LocalDateTime.now());
        applyRecordMapper.insert(record);
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

    private JsonNode readSettings(String json) {
        if (!StringUtils.hasText(json)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private List<String> readStringList(JsonNode root, String fieldName) {
        JsonNode node = root == null ? null : root.get(fieldName);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && StringUtils.hasText(item.asText())) {
                values.add(item.asText().trim());
            }
        }
        return values;
    }

    private Map<String, Boolean> readBooleanMap(JsonNode root, String fieldName) {
        JsonNode node = root == null ? null : root.get(fieldName);
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, Boolean> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (StringUtils.hasText(entry.getKey()) && value != null && value.isBoolean()) {
                values.put(entry.getKey(), value.asBoolean());
            }
        });
        return values;
    }

    private List<String> filterSdkDescriptionOrder(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String value : raw) {
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            if (!"JAVADOC".equals(normalized) && !"SRC_JAVADOC".equals(normalized)) {
                values.add(value.trim());
            }
        }
        return values;
    }

    private List<String> filterSdkParamOrder(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String value : raw) {
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            if (!"JAVADOC_PARAM".equals(normalized) && !"PS_JD".equals(normalized)) {
                values.add(value.trim());
            }
        }
        return values;
    }

    private Map<String, Boolean> filterEnabledMap(Map<String, Boolean> raw, List<String> order) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        for (String key : order) {
            Boolean enabled = raw == null ? null : raw.get(key);
            values.put(key, enabled != Boolean.FALSE);
        }
        return values;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private String normalizeCode(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("projectCode 不能为空");
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private String normalizeCapabilityName(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("能力名称不能为空");
        }
        return value.trim();
    }

    private String storageName(String projectCode, String capabilityName) {
        return (projectCode + "_" + capabilityName)
                .replaceAll("[^A-Za-z0-9_]+", "_")
                .replaceAll("_+", "_");
    }

    private String firstText(String... values) {
        String fallback = null;
        for (String value : values) {
            if (value != null) {
                fallback = value;
            }
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return fallback;
    }

    private String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String normalizeInstanceStatus(String status) {
        if (!StringUtils.hasText(status)) {
            throw new IllegalArgumentException("status 不能为空");
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ONLINE", "OFFLINE", "DISABLED", "STALE" -> normalized;
            default -> throw new IllegalArgumentException("不支持的实例状态: " + status);
        };
    }
}

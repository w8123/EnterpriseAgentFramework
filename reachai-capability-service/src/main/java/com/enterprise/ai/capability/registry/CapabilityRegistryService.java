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
import com.enterprise.ai.agent.registry.RegistryContracts.RuntimeGovernancePolicy;
import com.enterprise.ai.agent.registry.RegistryContracts.RuntimeGovernancePolicyUpdateRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.SdkCapabilityDescriptionSettings;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
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
    private final ObjectMapper objectMapper;

    @Transactional
    public RegistryProjectResponse registerProject(ProjectRegisterRequest request) {
        validateProjectRequest(request);
        String projectCode = normalizeCode(request.projectCode());
        ScanProjectEntity project = findProject(projectCode);
        if (project == null) {
            project = new ScanProjectEntity();
            project.setCreateTime(LocalDateTime.now());
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
        project.setUpdateTime(LocalDateTime.now());
        if (project.getId() == null) {
            scanProjectMapper.insert(project);
        } else {
            scanProjectMapper.updateById(project);
        }
        registrySecurityService.upsertCredential(project.getId(), project.getProjectCode(),
                request.appKey(), request.appSecret());
        registrySecurityService.updateEmbedPolicy(
                project.getProjectCode(),
                request.appKey(),
                request.allowedOrigins(),
                request.allowedAgentIds(),
                request.tokenTtlSeconds());
        return toProjectResponse(project);
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
        return new InstanceHeartbeatResponse(entity, governancePolicy(entity));
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
            throw new IllegalArgumentException("instanceId 涓嶈兘涓虹┖");
        }
        String normalizedStatus = normalizeInstanceStatus(status);
        ProjectInstanceEntity entity = getInstance(project, instanceId);
        entity.setStatus(normalizedStatus);
        entity.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(entity);
        return entity;
    }

    @Transactional
    public ProjectInstanceEntity updateInstanceGovernancePolicy(String projectCode,
                                                               RuntimeGovernancePolicyUpdateRequest request) {
        ScanProjectEntity project = getProject(projectCode);
        if (request == null || !StringUtils.hasText(request.instanceId())) {
            throw new IllegalArgumentException("instanceId 涓嶈兘涓虹┖");
        }
        ProjectInstanceEntity entity = getInstance(project, request.instanceId());
        RuntimeGovernancePolicy current = governancePolicy(entity);
        boolean disabled = request.disabled() == null ? current.disabled() : request.disabled();
        RuntimeGovernancePolicy merged = new RuntimeGovernancePolicy(
                disabled,
                disabled ? "DISABLED" : defaultString(entity.getStatus(), "ONLINE"),
                blankToNull(firstText(request.minSdkVersion(), current.minSdkVersion())),
                request.allowEmbeddedExecution() == null ? current.allowEmbeddedExecution() : request.allowEmbeddedExecution(),
                request.allowHybridExecution() == null ? current.allowHybridExecution() : request.allowHybridExecution(),
                blankToNull(firstText(request.message(), current.message()))
        );
        entity.setGovernancePolicyJson(writeJson(merged));
        if (disabled) {
            entity.setStatus("DISABLED");
        } else if ("DISABLED".equalsIgnoreCase(entity.getStatus())) {
            entity.setStatus("ONLINE");
        }
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
            CapabilityDiffItemEntity diffItem = insertDiffItem(snapshot, project, syncId, qualifiedName, capabilityName, storageName,
                    changeType, existingToolId, fieldDiffs, impact);
            if (apply && !"UNCHANGED".equals(changeType)) {
                applySdkCapabilityCatalogRow(project, registration, storageName, qualifiedName, capabilityName);
                diffItem.setReviewStatus("APPLIED");
                diffItem.setUpdatedAt(LocalDateTime.now());
                diffItemMapper.updateById(diffItem);
                recordReviewDecision(snapshot.getId(), diffItem.getId(), syncId, project, qualifiedName,
                        "APPLY", "SUCCESS", "SYNC", "API目录已更新");
                applied++;
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
                    capabilityName, row.getName(), "DELETED", row.getGlobalToolDefinitionId(), List.of(), impact);
            if (apply) {
                row.setRemovedFromSource(true);
                row.setRemovedAt(LocalDateTime.now());
                scanProjectToolMapper.updateById(row);
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
        if (!"IGNORE".equals(action)) {
            if ("DELETED".equalsIgnoreCase(item.getChangeType())) {
                markCatalogRowRemoved(project, item);
                item.setReviewStatus("APPLIED");
                item.setReviewNote(request == null ? null : request.note());
                item.setUpdatedAt(LocalDateTime.now());
                diffItemMapper.updateById(item);
                recordReviewDecision(snapshot.getId(), item.getId(), item.getSyncId(), project, item.getQualifiedName(),
                        "CATALOG_REMOVED", "SUCCESS", defaultString(request == null ? null : request.operator(), "system"),
                        request == null ? null : request.note());
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
        return toDiffItemDto(item);
    }

    RuntimeGovernancePolicy governancePolicy(ProjectInstanceEntity entity) {
        RuntimeGovernancePolicy stored = parseGovernancePolicy(entity == null ? null : entity.getGovernancePolicyJson());
        boolean disabled = entity != null && "DISABLED".equalsIgnoreCase(entity.getStatus());
        String status = entity == null ? "UNKNOWN" : defaultString(entity.getStatus(), "UNKNOWN");
        return new RuntimeGovernancePolicy(
                disabled || (stored != null && stored.disabled()),
                status,
                stored == null ? null : stored.minSdkVersion(),
                stored == null || stored.allowEmbeddedExecution() == null ? Boolean.TRUE : stored.allowEmbeddedExecution(),
                stored == null || stored.allowHybridExecution() == null ? Boolean.TRUE : stored.allowHybridExecution(),
                firstText(
                        stored == null ? null : stored.message(),
                        disabled ? "该业务系统 Runtime 已被中台禁用，SDK 应停止接受新的本地 Agent 执行。" : "ok"
                )
        );
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
            throw new IllegalArgumentException("瀹炰緥涓嶅瓨鍦? " + instanceId);
        }
        return entity;
    }

    private RegistryProjectResponse toProjectResponse(ScanProjectEntity project) {
        return new RegistryProjectResponse(project.getId(), project.getProjectCode(), project.getName(),
                project.getEnvironment(), project.getVisibility());
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
                entity.getReviewStatus(), entity.getReviewNote());
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
                                                    Map<String, Object> impact) {
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
        row.setAgentVisible(registration.agentVisible() == null || Boolean.TRUE.equals(registration.agentVisible()));
        row.setLightweightEnabled(Boolean.TRUE.equals(registration.lightweightEnabled()));
        row.setCapabilityMetadataJson(writeJson(mergeSdkMetadata(registration)));
        row.setRemovedFromSource(false);
        row.setRemovedAt(null);
        row.setUpdateTime(LocalDateTime.now());
        if (inserting) {
            scanProjectToolMapper.insert(row);
        } else {
            scanProjectToolMapper.updateById(row);
        }
    }

    private void markCatalogRowRemoved(ScanProjectEntity project, CapabilityDiffItemEntity item) {
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
        if (row != null) {
            row.setRemovedFromSource(true);
            row.setRemovedAt(LocalDateTime.now());
            scanProjectToolMapper.updateById(row);
        }
    }

    private List<FieldDiff> fieldDiffsFromCatalog(ScanProjectToolEntity row, CapabilityRegistration registration) {
        List<FieldDiff> diffs = new ArrayList<>();
        addDiff(diffs, "description", row.getDescription(), firstText(registration.description(), registration.title(), registration.name()));
        addDiff(diffs, "httpMethod", row.getHttpMethod(), firstText(registration.httpMethod(), "POST"));
        addDiff(diffs, "baseUrl", row.getBaseUrl(), registration.baseUrl());
        addDiff(diffs, "contextPath", row.getContextPath(), registration.contextPath());
        addDiff(diffs, "endpointPath", row.getEndpointPath(), registration.endpointPath());
        addDiff(diffs, "requestBodyType", row.getRequestBodyType(), registration.requestBodyType());
        addDiff(diffs, "responseType", row.getResponseType(), registration.responseType());
        addDiff(diffs, "enabled", row.getEnabled(), registration.enabled());
        addDiff(diffs, "agentVisible", row.getAgentVisible(), registration.agentVisible());
        addDiff(diffs, "lightweightEnabled", row.getLightweightEnabled(), registration.lightweightEnabled());
        addDiff(diffs, "parameters", row.getParametersJson(), writeJson(registration.parameters()));
        addDiff(diffs, "metadata", row.getCapabilityMetadataJson(), writeJson(mergeSdkMetadata(registration)));
        return diffs;
    }

    private List<FieldDiff> fieldDiffsFromDefinition(ToolDefinitionEntity entity, CapabilityRegistration registration) {
        List<FieldDiff> diffs = new ArrayList<>();
        addDiff(diffs, "description", entity.getDescription(), firstText(registration.description(), registration.title(), registration.name()));
        addDiff(diffs, "httpMethod", entity.getHttpMethod(), firstText(registration.httpMethod(), "POST"));
        addDiff(diffs, "baseUrl", entity.getBaseUrl(), registration.baseUrl());
        addDiff(diffs, "contextPath", entity.getContextPath(), registration.contextPath());
        addDiff(diffs, "endpointPath", entity.getEndpointPath(), registration.endpointPath());
        addDiff(diffs, "requestBodyType", entity.getRequestBodyType(), registration.requestBodyType());
        addDiff(diffs, "responseType", entity.getResponseType(), registration.responseType());
        addDiff(diffs, "sideEffect", entity.getSideEffect(), firstText(registration.sideEffect(), entity.getSideEffect()));
        addDiff(diffs, "visibility", entity.getVisibility(), registration.visibility());
        addDiff(diffs, "enabled", entity.getEnabled(), registration.enabled());
        addDiff(diffs, "agentVisible", entity.getAgentVisible(), registration.agentVisible());
        addDiff(diffs, "lightweightEnabled", entity.getLightweightEnabled(), registration.lightweightEnabled());
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
        impact.put("skills", List.of());
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
            CapabilitySyncRequest request = objectMapper.readValue(snapshot.getPayloadJson(), CapabilitySyncRequest.class);
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

    private RuntimeGovernancePolicy parseGovernancePolicy(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, RuntimeGovernancePolicy.class);
        } catch (Exception ex) {
            return null;
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

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeInstanceStatus(String status) {
        if (!StringUtils.hasText(status)) {
            throw new IllegalArgumentException("status 涓嶈兘涓虹┖");
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ONLINE", "OFFLINE", "DISABLED", "STALE" -> normalized;
            default -> throw new IllegalArgumentException("涓嶆敮鎸佺殑瀹炰緥鐘舵€? " + status);
        };
    }
}

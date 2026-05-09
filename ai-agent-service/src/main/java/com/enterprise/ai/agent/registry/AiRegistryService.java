package com.enterprise.ai.agent.registry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.acl.ToolAclEntity;
import com.enterprise.ai.agent.acl.ToolAclMapper;
import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.agent.AgentDefinitionService;
import com.enterprise.ai.agent.scan.ScanProjectEntity;
import com.enterprise.ai.agent.scan.ScanProjectMapper;
import com.enterprise.ai.agent.scan.ScanProjectService;
import com.enterprise.ai.agent.scan.ScanModuleService;
import com.enterprise.ai.agent.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.scan.ScanProjectToolService;
import com.enterprise.ai.agent.scan.ScanSettings;
import com.enterprise.ai.agent.scan.ScanSettingsJson;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionUpsertRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityDiffItem;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityRegistration;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityDiffItemDTO;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityReviewRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySnapshotDTO;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySyncRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySyncResponse;
import com.enterprise.ai.agent.registry.RegistryContracts.FieldDiff;
import com.enterprise.ai.agent.registry.RegistryContracts.InstanceHeartbeatRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.ProjectRegisterRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.RegistryProjectResponse;
import com.enterprise.ai.agent.registry.RegistryContracts.SdkCapabilityDescriptionSettings;
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
import java.util.stream.Collectors;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiRegistryService {

    private final ScanProjectService scanProjectService;
    private final ScanProjectMapper scanProjectMapper;
    private final ScanProjectToolService scanProjectToolService;
    private final ScanModuleService scanModuleService;
    private final ProjectInstanceMapper instanceMapper;
    private final CapabilitySyncLogMapper syncLogMapper;
    private final CapabilitySnapshotMapper snapshotMapper;
    private final CapabilityDiffItemMapper diffItemMapper;
    private final CapabilityApplyRecordMapper applyRecordMapper;
    private final ToolDefinitionService toolDefinitionService;
    private final AgentDefinitionService agentDefinitionService;
    private final ToolAclMapper toolAclMapper;
    private final RegistrySecurityService registrySecurityService;
    private final ObjectMapper objectMapper;

    @Transactional
    public RegistryProjectResponse registerProject(ProjectRegisterRequest request) {
        validateProjectRequest(request);
        String projectCode = normalizeCode(request.projectCode());
        ScanProjectEntity project = scanProjectService.findByProjectCode(projectCode).orElse(null);
        ScanProjectService.ScanProjectUpsertRequest upsert = new ScanProjectService.ScanProjectUpsertRequest(
                request.name(),
                projectCode,
                "REGISTERED",
                defaultString(request.environment(), "default"),
                request.owner(),
                defaultString(request.visibility(), "PRIVATE"),
                request.baseUrl(),
                defaultString(request.contextPath(), ""),
                "",
                "auto",
                null
        );
        if (project == null) {
            project = scanProjectService.create(upsert);
        } else {
            project = scanProjectService.update(project.getId(), upsert);
        }
        registrySecurityService.upsertCredential(project.getId(), project.getProjectCode(),
                request.appKey(), request.appSecret());
        return toProjectResponse(project);
    }

    /**
     * 供业务 SDK 拉取：与 scan_settings 一致，但剔除运行时不可用的 Javadoc 类源。
     */
    public SdkCapabilityDescriptionSettings getSdkCapabilityDescriptionSettings(String projectCode) {
        ScanProjectEntity project = getProject(projectCode);
        ScanSettings full = ScanSettingsJson.parseOrDefault(project.getScanSettings(), objectMapper);
        return toSdkFilteredSettings(full);
    }

    private SdkCapabilityDescriptionSettings toSdkFilteredSettings(ScanSettings full) {
        List<String> descOrder = filterSdkDescriptionOrder(full.getDescriptionSourceOrder());
        List<String> paramOrder = filterSdkParamOrder(full.getParamDescriptionSourceOrder());
        if (descOrder.isEmpty()) {
            descOrder = List.of("SWAGGER_API_OPERATION", "OPENAPI_OPERATION", "METHOD_NAME");
        }
        if (paramOrder.isEmpty()) {
            paramOrder = List.of("SCHEMA_ANNO", "PARAMETER_ANNO", "FIELD_NAME");
        }
        Map<String, Boolean> descEn = filterEnabledMap(full.getDescriptionSourceEnabled(), descOrder);
        Map<String, Boolean> paramEn = filterEnabledMap(full.getParamDescriptionSourceEnabled(), paramOrder);
        return new SdkCapabilityDescriptionSettings(descOrder, paramOrder, descEn, paramEn);
    }

    private static List<String> filterSdkDescriptionOrder(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String k : raw) {
            if (k == null || k.isBlank()) {
                continue;
            }
            String u = k.trim().toUpperCase(Locale.ROOT);
            if ("JAVADOC".equals(u) || "SRC_JAVADOC".equals(u)) {
                continue;
            }
            out.add(k.trim());
        }
        return out;
    }

    private static List<String> filterSdkParamOrder(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String k : raw) {
            if (k == null || k.isBlank()) {
                continue;
            }
            String u = k.trim().toUpperCase(Locale.ROOT);
            if ("JAVADOC_PARAM".equals(u) || "PS_JD".equals(u)) {
                continue;
            }
            out.add(k.trim());
        }
        return out;
    }

    private static Map<String, Boolean> filterEnabledMap(Map<String, Boolean> raw, List<String> order) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String k : order) {
            Boolean v = raw == null ? null : raw.get(k);
            out.put(k, v != Boolean.FALSE);
        }
        return out;
    }

    @Transactional
    public ProjectInstanceEntity heartbeat(String projectCode, InstanceHeartbeatRequest request) {
        ScanProjectEntity project = getProject(projectCode);
        if (request == null || !StringUtils.hasText(request.instanceId())) {
            throw new IllegalArgumentException("instanceId 不能为空");
        }
        ProjectInstanceEntity entity = instanceMapper.selectOne(new LambdaQueryWrapper<ProjectInstanceEntity>()
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
        entity.setStatus("ONLINE");
        entity.setMetadataJson(writeJson(request.metadata()));
        entity.setLastHeartbeatAt(LocalDateTime.now());
        if (entity.getId() == null) {
            instanceMapper.insert(entity);
        } else {
            instanceMapper.updateById(entity);
        }
        return entity;
    }

    @Transactional
    public void offline(String projectCode, String instanceId) {
        ScanProjectEntity project = getProject(projectCode);
        ProjectInstanceEntity entity = instanceMapper.selectOne(new LambdaQueryWrapper<ProjectInstanceEntity>()
                .eq(ProjectInstanceEntity::getProjectCode, project.getProjectCode())
                .eq(ProjectInstanceEntity::getInstanceId, instanceId)
                .last("limit 1"));
        if (entity != null) {
            entity.setStatus("OFFLINE");
            entity.setLastHeartbeatAt(LocalDateTime.now());
            instanceMapper.updateById(entity);
        }
    }

    public CapabilitySyncResponse diff(String projectCode, CapabilitySyncRequest request) {
        return sync(projectCode, new CapabilitySyncRequest(
                request == null ? null : request.syncId(),
                request == null ? null : request.source(),
                false,
                request == null ? List.of() : request.capabilities()
        ));
    }

    @Transactional
    public CapabilitySyncResponse sync(String projectCode, CapabilitySyncRequest request) {
        ScanProjectEntity project = getProject(projectCode);
        List<CapabilityRegistration> capabilities = request == null || request.capabilities() == null
                ? List.of()
                : request.capabilities();
        boolean apply = request == null || request.apply() == null || Boolean.TRUE.equals(request.apply());
        String syncId = StringUtils.hasText(request == null ? null : request.syncId())
                ? request.syncId().trim()
                : UUID.randomUUID().toString();

        // 将存量仅存在于 tool_definition 的 SDK 能力补齐到 API 目录行，便于删除对账与差异基准一致
        scanProjectToolService.ensureSdkMirrors(project.getId());

        int added = 0;
        int changed = 0;
        int unchanged = 0;
        int applied = 0;
        int deleted = 0;
        List<CapabilityDiffItem> items = new ArrayList<>();
        CapabilitySnapshotEntity snapshot = createSnapshot(project, syncId, request, capabilities);
        Set<String> reportedQualifiedNames = capabilities.stream()
                .map(c -> project.getProjectCode() + ":" + normalizeCapabilityName(c.name()))
                .collect(Collectors.toSet());
        for (CapabilityRegistration c : capabilities) {
            String capabilityName = normalizeCapabilityName(c.name());
            String storageName = storageName(project.getProjectCode(), capabilityName);
            String qualifiedName = project.getProjectCode() + ":" + capabilityName;
            String sdkLoc = "sdk:" + project.getProjectCode().trim() + ":" + capabilityName;

            ScanProjectToolEntity catalogRow = scanProjectToolService.findByProjectAndSourceLocation(project.getId(), sdkLoc)
                    .or(() -> scanProjectToolService.findByProjectAndName(project.getId(), storageName))
                    .orElse(null);
            ToolDefinitionEntity legacyGlobal = null;
            if (catalogRow == null) {
                legacyGlobal = toolDefinitionService.findByQualifiedName(qualifiedName)
                        .or(() -> toolDefinitionService.findByName(storageName))
                        .orElse(null);
            }

            List<FieldDiff> fieldDiffs;
            Long existingToolIdForDiff;
            if (catalogRow != null) {
                fieldDiffs = fieldDiffsFromCatalog(catalogRow, c);
                existingToolIdForDiff = catalogRow.getGlobalToolDefinitionId();
            } else if (legacyGlobal != null) {
                fieldDiffs = fieldDiffs(legacyGlobal, c);
                existingToolIdForDiff = legacyGlobal.getId();
            } else {
                fieldDiffs = List.of();
                existingToolIdForDiff = null;
            }

            String changeType;
            if (catalogRow == null && legacyGlobal == null) {
                changeType = "ADDED";
            } else {
                changeType = fieldDiffs.isEmpty() ? "UNCHANGED" : "CHANGED";
            }

            if ("ADDED".equals(changeType)) {
                added++;
            } else if ("CHANGED".equals(changeType)) {
                changed++;
            } else {
                unchanged++;
            }
            Map<String, Object> impact = analyzeImpact(project, capabilityName, storageName, qualifiedName);
            items.add(new CapabilityDiffItem(qualifiedName, capabilityName, changeType,
                    existingToolIdForDiff, storageName, fieldDiffs, impact));
            CapabilityDiffItemEntity diffItem = insertDiffItem(snapshot, project, syncId, qualifiedName, capabilityName,
                    storageName, changeType, existingToolIdForDiff, fieldDiffs, impact);
            if (apply && !"UNCHANGED".equals(changeType)) {
                applySdkCapabilityCatalogOnly(project, snapshot.getId(), diffItem.getId(), syncId, c,
                        storageName, qualifiedName, capabilityName, "SYNC");
                diffItem.setReviewStatus("APPLIED");
                diffItem.setUpdatedAt(LocalDateTime.now());
                diffItemMapper.updateById(diffItem);
                applied++;
            }
        }

        String sdkPrefix = "sdk:" + project.getProjectCode().trim() + ":";
        for (ScanProjectToolEntity row : scanProjectToolService.listByProject(project.getId())) {
            String sl = row.getSourceLocation();
            if (!StringUtils.hasText(sl) || !sl.startsWith(sdkPrefix)) {
                continue;
            }
            String capSuffix = sl.substring(sdkPrefix.length()).trim();
            if (!StringUtils.hasText(capSuffix)) {
                continue;
            }
            String qn = project.getProjectCode().trim() + ":" + capSuffix;
            if (reportedQualifiedNames.contains(qn)) {
                continue;
            }
            deleted++;
            Map<String, Object> impact = analyzeImpact(project, capSuffix, row.getName(), qn);
            Long existingId = row.getGlobalToolDefinitionId();
            items.add(new CapabilityDiffItem(qn, capSuffix, "DELETED",
                    existingId, row.getName(), List.of(), impact));
            CapabilityDiffItemEntity diffItem = insertDiffItem(snapshot, project, syncId, qn,
                    capSuffix, row.getName(), "DELETED", existingId, List.of(), impact);
            if (apply) {
                scanProjectToolService.markCatalogRowRemoved(row);
                recordApply(snapshot.getId(), diffItem.getId(), syncId, project, qn,
                        "CATALOG_REMOVED", "SUCCESS", "SYNC", "SDK 已不再上报，API 目录已标记移除");
                diffItem.setReviewStatus("APPLIED");
                diffItem.setUpdatedAt(LocalDateTime.now());
                diffItemMapper.updateById(diffItem);
                applied++;
            }
        }

        if (apply) {
            scanModuleService.bootstrapFromTools(project.getId());
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
        String operator = defaultString(request == null ? null : request.operator(), "system");
        if ("IGNORE".equals(action)) {
            item.setReviewStatus("IGNORED");
            item.setReviewNote(request == null ? null : request.note());
            item.setUpdatedAt(LocalDateTime.now());
            diffItemMapper.updateById(item);
            recordApply(snapshot.getId(), item.getId(), item.getSyncId(), project, item.getQualifiedName(),
                    "IGNORE", "SUCCESS", operator, request == null ? null : request.note());
            return toDiffItemDto(item);
        }
        CapabilityRegistration registration = findRegistration(snapshot, item.getQualifiedName());
        if ("DELETED".equals(item.getChangeType())) {
            scanProjectToolService.tombstoneSdkCatalogByQualifiedName(project.getId(), project, item.getName());
            recordApply(snapshot.getId(), item.getId(), item.getSyncId(), project, item.getQualifiedName(),
                    "CATALOG_REMOVED", "SUCCESS", operator, "API目录已标记移除；全局 Tool 请按需人工下架");
        } else {
            if (registration == null) {
                throw new IllegalArgumentException("快照中找不到能力: " + item.getQualifiedName());
            }
            applySdkCapabilityCatalogOnly(project, snapshot.getId(), item.getId(), item.getSyncId(), registration,
                    item.getStorageName(), item.getQualifiedName(),
                    normalizeCapabilityName(registration.name()), operator);
        }
        scanModuleService.bootstrapFromTools(project.getId());
        item.setReviewStatus("APPLIED");
        item.setReviewNote(request == null ? null : request.note());
        item.setUpdatedAt(LocalDateTime.now());
        diffItemMapper.updateById(item);
        refreshSnapshotStatus(snapshot.getId());
        return toDiffItemDto(item);
    }

    public List<ProjectInstanceEntity> listInstances(String projectCode) {
        ScanProjectEntity project = getProject(projectCode);
        return instanceMapper.selectList(Wrappers.<ProjectInstanceEntity>lambdaQuery()
                .eq(ProjectInstanceEntity::getProjectId, project.getId())
                .orderByDesc(ProjectInstanceEntity::getLastHeartbeatAt));
    }

    @Transactional
    public int markStaleInstancesOffline(int heartbeatTtlSeconds) {
        int ttl = Math.max(30, heartbeatTtlSeconds);
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(ttl);
        List<ProjectInstanceEntity> stale = instanceMapper.selectList(Wrappers.<ProjectInstanceEntity>lambdaQuery()
                .eq(ProjectInstanceEntity::getStatus, "ONLINE")
                .lt(ProjectInstanceEntity::getLastHeartbeatAt, cutoff));
        for (ProjectInstanceEntity entity : stale) {
            entity.setStatus("OFFLINE");
            instanceMapper.updateById(entity);
        }
        return stale.size();
    }

    private CapabilitySnapshotEntity createSnapshot(ScanProjectEntity project, String syncId,
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
                request == null ? null : request.apply(),
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

    private void refreshSnapshotStatus(Long snapshotId) {
        List<CapabilityDiffItemEntity> items = diffItemMapper.selectList(Wrappers.<CapabilityDiffItemEntity>lambdaQuery()
                .eq(CapabilityDiffItemEntity::getSnapshotId, snapshotId));
        boolean hasPending = items.stream().anyMatch(i -> "PENDING".equalsIgnoreCase(i.getReviewStatus()));
        boolean hasApplied = items.stream().anyMatch(i -> "APPLIED".equalsIgnoreCase(i.getReviewStatus()));
        CapabilitySnapshotEntity snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot != null) {
            snapshot.setStatus(hasPending ? (hasApplied ? "PARTIAL" : "PENDING") : "APPLIED");
            snapshot.setUpdatedAt(LocalDateTime.now());
            snapshotMapper.updateById(snapshot);
        }
    }

    private void applySdkCapabilityCatalogOnly(ScanProjectEntity project,
                                               Long snapshotId,
                                               Long diffItemId,
                                               String syncId,
                                               CapabilityRegistration c,
                                               String storageName,
                                               String qualifiedName,
                                               String capabilityName,
                                               String operator) {
        ToolDefinitionUpsertRequest upsert = toSdkCatalogUpsert(project, c, storageName, qualifiedName, capabilityName);
        scanProjectToolService.upsertSdkCapabilityCatalogRow(project.getId(), upsert);
        recordApply(snapshotId, diffItemId, syncId, project, qualifiedName,
                "APPLY", "SUCCESS", operator, "API目录已更新");
    }

    private void recordApply(Long snapshotId,
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

    private List<FieldDiff> fieldDiffsFromCatalog(ScanProjectToolEntity row, CapabilityRegistration c) {
        if (row == null) {
            return List.of();
        }
        List<FieldDiff> diffs = new ArrayList<>();
        addDiff(diffs, "description", row.getDescription(), firstText(c.description(), c.title(), c.name()));
        addDiff(diffs, "httpMethod", row.getHttpMethod(), firstText(c.httpMethod(), "POST"));
        addDiff(diffs, "baseUrl", row.getBaseUrl(), c.baseUrl());
        addDiff(diffs, "contextPath", row.getContextPath(), c.contextPath());
        addDiff(diffs, "endpointPath", row.getEndpointPath(), c.endpointPath());
        addDiff(diffs, "requestBodyType", row.getRequestBodyType(), c.requestBodyType());
        addDiff(diffs, "responseType", row.getResponseType(), c.responseType());
        addDiff(diffs, "enabled", row.getEnabled(), c.enabled());
        addDiff(diffs, "agentVisible", row.getAgentVisible(), c.agentVisible());
        addDiff(diffs, "lightweightEnabled", row.getLightweightEnabled(), c.lightweightEnabled());
        addDiff(diffs, "parameters", row.getParametersJson(), writeJson(c.parameters()));
        addDiff(diffs, "metadata", row.getCapabilityMetadataJson(), writeJson(mergeSdkMetadata(c)));
        return diffs;
    }

    private Object mergeSdkMetadata(CapabilityRegistration c) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (c.metadata() != null) {
            m.putAll(c.metadata());
        }
        if (StringUtils.hasText(c.sideEffect())) {
            m.put("sideEffect", c.sideEffect().trim());
        }
        return m.isEmpty() ? null : m;
    }

    private List<FieldDiff> fieldDiffs(ToolDefinitionEntity existing, CapabilityRegistration c) {
        if (existing == null) {
            return List.of();
        }
        List<FieldDiff> diffs = new ArrayList<>();
        addDiff(diffs, "description", existing.getDescription(), firstText(c.description(), c.title(), c.name()));
        addDiff(diffs, "httpMethod", existing.getHttpMethod(), firstText(c.httpMethod(), "POST"));
        addDiff(diffs, "baseUrl", existing.getBaseUrl(), c.baseUrl());
        addDiff(diffs, "contextPath", existing.getContextPath(), c.contextPath());
        addDiff(diffs, "endpointPath", existing.getEndpointPath(), c.endpointPath());
        addDiff(diffs, "requestBodyType", existing.getRequestBodyType(), c.requestBodyType());
        addDiff(diffs, "responseType", existing.getResponseType(), c.responseType());
        addDiff(diffs, "sideEffect", existing.getSideEffect(), firstText(c.sideEffect(), existing.getSideEffect()));
        addDiff(diffs, "visibility", existing.getVisibility(), c.visibility());
        addDiff(diffs, "enabled", existing.getEnabled(), c.enabled());
        addDiff(diffs, "agentVisible", existing.getAgentVisible(), c.agentVisible());
        addDiff(diffs, "lightweightEnabled", existing.getLightweightEnabled(), c.lightweightEnabled());
        addDiff(diffs, "parameters", existing.getParametersJson(), writeJson(c.parameters()));
        addDiff(diffs, "metadata", existing.getCapabilityMetadataJson(), writeJson(c.metadata()));
        return diffs;
    }

    private void addDiff(List<FieldDiff> diffs, String field, Object oldValue, Object newValue) {
        if (newValue == null) {
            return;
        }
        if (!Objects.equals(String.valueOf(oldValue), String.valueOf(newValue))) {
            diffs.add(new FieldDiff(field, oldValue, newValue));
        }
    }

    private Map<String, Object> analyzeImpact(ScanProjectEntity project,
                                              String capabilityName,
                                              String storageName,
                                              String qualifiedName) {
        List<String> candidateNames = List.of(
                defaultString(storageName, ""),
                defaultString(capabilityName, ""),
                defaultString(qualifiedName, "")
        );
        List<AgentDefinition> affectedAgents = agentDefinitionService.list(project.getId()).stream()
                .filter(agent -> containsAny(agent.getTools(), candidateNames) || containsAny(agent.getSkills(), candidateNames)
                        || (agent.getCanvasJson() != null && candidateNames.stream().anyMatch(agent.getCanvasJson()::contains)))
                .toList();
        List<ToolDefinitionEntity> affectedSkills = toolDefinitionService.listByProjectId(project.getId()).stream()
                .filter(tool -> "SKILL".equalsIgnoreCase(tool.getKind()))
                .filter(skill -> skill.getSpecJson() != null && candidateNames.stream().anyMatch(skill.getSpecJson()::contains))
                .toList();
        List<ToolAclEntity> affectedAcl = toolAclMapper.selectList(Wrappers.<ToolAclEntity>lambdaQuery()
                .eq(ToolAclEntity::getProjectId, project.getId())
                .and(q -> q.in(ToolAclEntity::getTargetName, candidateNames)
                        .or()
                        .eq(ToolAclEntity::getTargetName, "*")));
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("agents", affectedAgents.stream().map(AgentDefinition::getId).toList());
        impact.put("skills", affectedSkills.stream().map(ToolDefinitionEntity::getName).toList());
        impact.put("aclRuleIds", affectedAcl.stream().map(ToolAclEntity::getId).toList());
        impact.put("mcp", "MCP 可见性按 tool name 运行时解析，发布前需重新检查");
        impact.put("a2a", "A2A 通过 Agent 版本间接受影响");
        return impact;
    }

    private boolean containsAny(List<String> values, List<String> candidates) {
        return values != null && values.stream().anyMatch(candidates::contains);
    }

    private CapabilityRegistration findRegistration(CapabilitySnapshotEntity snapshot, String qualifiedName) {
        try {
            CapabilitySyncRequest request = objectMapper.readValue(snapshot.getPayloadJson(), CapabilitySyncRequest.class);
            if (request.capabilities() == null) {
                return null;
            }
            return request.capabilities().stream()
                    .filter(c -> (snapshot.getProjectCode() + ":" + normalizeCapabilityName(c.name())).equals(qualifiedName))
                    .findFirst()
                    .orElse(null);
        } catch (Exception ex) {
            return null;
        }
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

    private ToolDefinitionUpsertRequest toSdkCatalogUpsert(ScanProjectEntity project, CapabilityRegistration c,
                                                           String storageName, String qualifiedName,
                                                           String capabilityName) {
        Object meta = mergeSdkMetadata(c);
        return new ToolDefinitionUpsertRequest(
                storageName,
                "TOOL",
                firstText(c.description(), c.title(), c.name()),
                c.parameters() == null ? List.of() : c.parameters(),
                "scanner",
                "sdk:" + project.getProjectCode().trim() + ":" + capabilityName,
                firstText(c.httpMethod(), "POST"),
                firstText(c.baseUrl(), project.getBaseUrl()),
                firstText(c.contextPath(), project.getContextPath()),
                c.endpointPath(),
                c.requestBodyType(),
                c.responseType(),
                project.getId(),
                project.getProjectCode(),
                firstText(c.visibility(), project.getVisibility(), "PRIVATE"),
                qualifiedName,
                c.enabled() == null || Boolean.TRUE.equals(c.enabled()),
                c.agentVisible() == null || Boolean.TRUE.equals(c.agentVisible()),
                Boolean.TRUE.equals(c.lightweightEnabled()),
                StringUtils.hasText(c.sideEffect()) ? c.sideEffect().trim() : null,
                null,
                null,
                false,
                meta
        );
    }

    /**
     * 当前项目最近一次能力快照中，仍处于待评审（PENDING）的差异项 qualifiedName 集合，
     * 用于管理端统一展示「SDK 变更待应用/待忽略」。
     */
    public Set<String> pendingCapabilityQualifiedNames(long projectId) {
        CapabilitySnapshotEntity latest = snapshotMapper.selectOne(Wrappers.<CapabilitySnapshotEntity>lambdaQuery()
                .eq(CapabilitySnapshotEntity::getProjectId, projectId)
                .orderByDesc(CapabilitySnapshotEntity::getId)
                .last("LIMIT 1"));
        if (latest == null) {
            return Set.of();
        }
        return diffItemMapper.selectList(Wrappers.<CapabilityDiffItemEntity>lambdaQuery()
                        .eq(CapabilityDiffItemEntity::getSnapshotId, latest.getId())
                        .eq(CapabilityDiffItemEntity::getReviewStatus, "PENDING")
                        .ne(CapabilityDiffItemEntity::getChangeType, "UNCHANGED"))
                .stream()
                .map(CapabilityDiffItemEntity::getQualifiedName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private void writeSyncLog(ScanProjectEntity project, String syncId, String source, String status,
                              CapabilitySyncResponse response, String error) {
        CapabilitySyncLogEntity log = new CapabilitySyncLogEntity();
        log.setProjectId(project.getId());
        log.setProjectCode(project.getProjectCode());
        log.setSyncId(syncId);
        log.setSource(defaultString(source, "SDK"));
        log.setStatus(status);
        log.setSummaryJson(writeJson(response));
        log.setErrorMessage(error);
        syncLogMapper.insert(log);
    }

    private ScanProjectEntity getProject(String projectCode) {
        String normalized = normalizeCode(projectCode);
        return scanProjectService.findByProjectCode(normalized)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + normalized));
    }

    private RegistryProjectResponse toProjectResponse(ScanProjectEntity project) {
        return new RegistryProjectResponse(project.getId(), project.getProjectCode(), project.getName(),
                project.getEnvironment(), project.getVisibility());
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

    private String normalizeCode(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("projectCode 不能为空");
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private String normalizeCapabilityName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("能力名不能为空");
        }
        return name.trim();
    }

    private String storageName(String projectCode, String capabilityName) {
        return projectCode + "__" + capabilityName.replaceAll("[^A-Za-z0-9_]+", "_");
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String firstText(String... values) {
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                return v.trim();
            }
        }
        return null;
    }

    private String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}

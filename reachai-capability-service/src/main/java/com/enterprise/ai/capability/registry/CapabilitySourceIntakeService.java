package com.enterprise.ai.capability.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.registry.CapabilityDiffItemEntity;
import com.enterprise.ai.agent.registry.CapabilityDiffItemMapper;
import com.enterprise.ai.agent.registry.CapabilitySnapshotEntity;
import com.enterprise.ai.agent.registry.CapabilitySnapshotMapper;
import com.enterprise.ai.agent.registry.CapabilitySyncLogEntity;
import com.enterprise.ai.agent.registry.CapabilitySyncLogMapper;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityDiffItem;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityRegistration;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySyncRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySyncResponse;
import com.enterprise.ai.agent.registry.RegistryContracts.FieldDiff;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Receives complete source or diagnostic inventories within the project-locked Registry transaction. */
@Service
@RequiredArgsConstructor
public class CapabilitySourceIntakeService {
    private final ScanProjectMapper scanProjectMapper;
    private final ScanProjectToolMapper scanProjectToolMapper;
    private final ToolDefinitionMapper toolDefinitionMapper;
    private final CapabilitySyncLogMapper syncLogMapper;
    private final CapabilitySnapshotMapper snapshotMapper;
    private final CapabilityDiffItemMapper diffItemMapper;
    private final ObjectMapper objectMapper;
    private final CapabilityChangePolicy changePolicy;
    private final CapabilityChangeLifecycle changeLifecycle;
    private final CapabilityCatalogProjectionStore catalogProjectionStore;
    private final CapabilityReviewEvidenceStore reviewEvidence;

    @Transactional(propagation = Propagation.MANDATORY)
    public CapabilitySyncResponse receiveSource(ScanProjectEntity project, CapabilitySyncRequest request) {
        return syncInternal(project, request, "SOURCE");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CapabilitySyncResponse diagnose(ScanProjectEntity project, CapabilitySyncRequest request) {
        return syncInternal(project, request, "DIAGNOSTIC");
    }

    private CapabilitySyncResponse syncInternal(ScanProjectEntity project, CapabilitySyncRequest request, String intakeMode) {
        scanProjectMapper.lockCapabilityChanges(project.getId());
        List<CapabilityRegistration> capabilities = changePolicy.normalize(project,
                request == null ? null : request.capabilities());
        String syncId = StringUtils.hasText(request == null ? null : request.syncId())
                ? request.syncId().trim()
                : UUID.randomUUID().toString();

        CapabilitySnapshotEntity repeated = changeLifecycle.findRepeat(project.getId(), syncId, intakeMode, capabilities);
        if (repeated != null) return repeatedSnapshot(project, repeated);

        int added = 0;
        int changed = 0;
        int unchanged = 0;
        int applied = 0;
        List<CapabilityDiffItem> items = new ArrayList<>();
        CapabilitySnapshotEntity snapshot = createSnapshot(project, syncId, request, capabilities);
        snapshot.setIntakeMode(intakeMode);
        snapshot.setContentHash(changePolicy.hash(capabilities));
        snapshotMapper.updateById(snapshot);
        changeLifecycle.recordSyncIdentity(project.getId(), syncId, snapshot);
        if ("SOURCE".equals(intakeMode)) changeLifecycle.beginSourceObservation(project.getId());
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

            ToolDefinitionEntity beforeGlobalTool = catalogRow == null
                    ? existingTool : catalogProjectionStore.findGlobalTool(catalogRow, qualifiedName, existingToolId);
            if (beforeGlobalTool != null && (beforeGlobalTool.getProjectId() != null
                    && !Objects.equals(project.getId(), beforeGlobalTool.getProjectId())
                    || StringUtils.hasText(beforeGlobalTool.getQualifiedName())
                    && !qualifiedName.equals(beforeGlobalTool.getQualifiedName()))) {
                throw new IllegalArgumentException("能力调用标识已被其他能力占用: " + storageName);
            }
            String changeType;
            if (beforeGlobalTool == null) {
                changeType = "ADDED";
                added++;
            } else if (fieldDiffs.isEmpty()) {
                changeType = "UNCHANGED";
                unchanged++;
            } else {
                changeType = "CHANGED";
                changed++;
            }
            CapabilityChangePolicy.Decision decision = changePolicy.decide(changeType, registration, fieldDiffs);
            Map<String, Object> impact = changeLifecycle.decisionImpact(decision);
            impact.put("candidate", registration);
            items.add(new CapabilityDiffItem(qualifiedName, capabilityName, changeType,
                    existingToolId, storageName, fieldDiffs, impact));
            CapabilityDiffItemEntity diffItem = insertDiffItem(snapshot, project, syncId, qualifiedName, capabilityName, storageName,
                    changeType, existingToolId, fieldDiffs, impact, CapabilityCatalogStateCodec.capture(objectMapper, catalogRow, beforeGlobalTool));
            changeLifecycle.prepareCandidate(snapshot, diffItem, registration);
            boolean automatic = "SOURCE".equals(intakeMode) && decision.automatic();
            if (automatic && !"UNCHANGED".equals(changeType)) {
                catalogProjectionStore.applySdkCapabilityCatalogRow(project, registration, storageName, qualifiedName, capabilityName);
                diffItem.setReviewStatus("AUTO_APPLIED");
                diffItem.setReviewNote(decision.reason());
                diffItemMapper.updateById(diffItem);
                reviewEvidence.recordReviewDecision(snapshot.getId(), diffItem.getId(), syncId, project, qualifiedName,
                        "AUTO_APPLY", "SUCCESS", CapabilityChangePolicy.VERSION, decision.reason());
                applied++;
            }
            if ("SOURCE".equals(intakeMode)) {
                changeLifecycle.observeSource(project, snapshot, diffItem, changePolicy.contractHash(registration),
                        automatic && !"UNCHANGED".equals(changeType)
                                ? changePolicy.contractHash(registration) : changePolicy.contractHash(beforeGlobalTool));
                if (automatic && "UNCHANGED".equals(changeType)) {
                    catalogProjectionStore.bindUnchangedSource(catalogRow, beforeGlobalTool, qualifiedName);
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
            if (Boolean.TRUE.equals(row.getRemovedFromSource()) && Boolean.FALSE.equals(row.getEnabled())) continue;
            deleted++;
            Map<String, Object> impact = changeLifecycle.decisionImpact(changePolicy.decide("DELETED", null, List.of()));
            items.add(new CapabilityDiffItem(qualifiedName, capabilityName, "DELETED",
                    row.getGlobalToolDefinitionId(), row.getName(), List.of(), impact));
            CapabilityDiffItemEntity diffItem = insertDiffItem(snapshot, project, syncId, qualifiedName,
                    capabilityName, row.getName(), "DELETED", row.getGlobalToolDefinitionId(), List.of(), impact,
                    CapabilityCatalogStateCodec.capture(objectMapper, row, catalogProjectionStore.findGlobalTool(row, qualifiedName, row.getGlobalToolDefinitionId())));
            changeLifecycle.prepareCandidate(snapshot, diffItem, null);
            if ("SOURCE".equals(intakeMode)) {
                changeLifecycle.observeSource(project, snapshot, diffItem, null,
                        changePolicy.contractHash(catalogProjectionStore.findGlobalTool(row, qualifiedName, row.getGlobalToolDefinitionId())));
            }
        }
        if ("SOURCE".equals(intakeMode)) changeLifecycle.finishSourceObservation(project.getId(), snapshot);
        updateSnapshotSummary(snapshot, capabilities.size(), added, changed, unchanged, deleted, true, applied);
        reviewEvidence.refreshSnapshotReviewStatus(snapshot);
        CapabilitySyncResponse response = new CapabilitySyncResponse(syncId, project.getId(), project.getProjectCode(),
                capabilities.size(), added, changed, unchanged, applied, items);
        writeSyncLog(project, syncId, request == null ? null : request.source(), snapshot.getStatus(), response, null);
        return response;
    }

    private CapabilitySyncResponse repeatedSnapshot(ScanProjectEntity project, CapabilitySnapshotEntity snapshot) {
        List<CapabilityDiffItemEntity> stored = diffItemMapper.selectList(Wrappers.<CapabilityDiffItemEntity>lambdaQuery()
                .eq(CapabilityDiffItemEntity::getSnapshotId, snapshot.getId())
                .eq(CapabilityDiffItemEntity::getProjectId, project.getId()));
        List<CapabilityDiffItem> items = stored.stream().map(item -> {
            try {
                List<FieldDiff> fields = objectMapper.readValue(item.getFieldDiffJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, FieldDiff.class));
                Map<String, Object> impact = objectMapper.readValue(item.getImpactJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
                return new CapabilityDiffItem(item.getQualifiedName(), item.getName(), item.getChangeType(),
                        item.getExistingToolId(), item.getStorageName(), fields, impact);
            } catch (Exception invalid) { throw new IllegalStateException("已保存的能力变化记录无法读取", invalid); }
        }).toList();
        return new CapabilitySyncResponse(snapshot.getSyncId(), project.getId(), project.getProjectCode(),
                snapshot.getReceived(), snapshot.getAdded(), snapshot.getChanged(), snapshot.getUnchanged(), 0, items);
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
        snapshot.setIntakeMode("DIAGNOSTIC");
        snapshot.setReportCount(1);
        snapshot.setLastSeenAt(LocalDateTime.now());
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
        item.setReviewStatus("UNCHANGED".equals(changeType) ? "UNCHANGED" : "PENDING");
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
        snapshot.setStatus("DIAGNOSTIC".equals(snapshot.getIntakeMode()) ? "DIAGNOSTIC"
                : apply && applied >= added + changed + deleted ? "APPLIED" : "PENDING");
        snapshot.setUpdatedAt(LocalDateTime.now());
        snapshotMapper.updateById(snapshot);
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
        addDiff(diffs, "metadata", row.getCapabilityMetadataJson(), writeJson(changePolicy.mergeSdkMetadata(registration)));
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
        addDiff(diffs, "metadata", entity.getCapabilityMetadataJson(), writeJson(changePolicy.mergeSdkMetadata(registration)));
        return diffs;
    }

    private void addDiff(List<FieldDiff> diffs, String field, Object oldValue, Object newValue) {
        if (!changePolicy.equalValue(oldValue, newValue)) {
            diffs.add(new FieldDiff(field, oldValue, newValue));
        }
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
}

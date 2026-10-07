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
import com.enterprise.ai.agent.registry.RegistryContracts.HttpApiSyncSummary;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetStore;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetEntity;
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
    private final StarterMvcHttpApiIntakeService httpApiIntake;
    private final BusinessMethodAssetStore businessMethods;

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
        if (capabilities.stream()
                .anyMatch(item -> CapabilityAssetType.HTTP_API.equals(changePolicy.assetType(item)))) {
            throw new IllegalArgumentException("HTTP API 必须通过 httpApis 同步，不能放入 capabilities");
        }
        String syncId = StringUtils.hasText(request == null ? null : request.syncId())
                ? request.syncId().trim()
                : UUID.randomUUID().toString();
        StarterMvcHttpApiIntakeService.Plan httpApis = httpApiIntake.prepare(project, syncId,
                request == null ? null : request.httpApis());
        String contentHash = syncContentHash(capabilities, httpApis);

        CapabilitySnapshotEntity repeated = changeLifecycle.findRepeat(project.getId(), syncId, intakeMode, contentHash);
        if (repeated != null) {
            repairRepeatedAcceptedSourceProjection(project, intakeMode, capabilities, repeated);
            HttpApiSyncSummary summary = observeHttpApis(intakeMode, httpApis);
            return repeatedSnapshot(project, repeated, summary);
        }

        int added = 0;
        int changed = 0;
        int unchanged = 0;
        int applied = 0;
        List<CapabilityDiffItem> items = new ArrayList<>();
        CapabilitySnapshotEntity snapshot = createSnapshot(project, syncId, request, capabilities, httpApis);
        snapshot.setIntakeMode(intakeMode);
        snapshot.setContentHash(contentHash);
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
            BusinessMethodAssetEntity acceptedMethod = businessMethods.find(qualifiedName)
                    .filter(asset -> asset.getAcceptedRevisionId() != null && "ACCEPTED".equals(asset.getStatus())).orElse(null);

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

            List<FieldDiff> fieldDiffs = acceptedMethod == null ? List.of()
                    : fieldDiffsFromMethod(businessMethods.acceptedDeclaration(acceptedMethod), registration);
            Long existingToolId = catalogRow == null ? existingTool == null ? null : existingTool.getId()
                    : catalogRow.getGlobalToolDefinitionId();

            ToolDefinitionEntity beforeGlobalTool = catalogRow == null
                    ? existingTool : catalogProjectionStore.findGlobalTool(catalogRow, qualifiedName, existingToolId);
            if (beforeGlobalTool != null && (beforeGlobalTool.getProjectId() != null
                    && !Objects.equals(project.getId(), beforeGlobalTool.getProjectId())
                    || StringUtils.hasText(beforeGlobalTool.getQualifiedName())
                    && !qualifiedName.equals(beforeGlobalTool.getQualifiedName()))) {
                throw new IllegalArgumentException("能力调用标识已被其他能力占用: " + storageName);
            }
            String changeType;
            if (acceptedMethod == null) {
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
                    changeType, existingToolId, fieldDiffs, impact, catalogProjectionStore.captureState(qualifiedName, catalogRow, beforeGlobalTool));
            changeLifecycle.prepareCandidate(snapshot, diffItem, registration);
            boolean automatic = "SOURCE".equals(intakeMode) && decision.automatic();
            if (automatic && !"UNCHANGED".equals(changeType)) {
                catalogProjectionStore.applySdkCapabilityCatalogRow(project, registration, storageName, qualifiedName, capabilityName, diffItem);
                diffItem.setReviewStatus("AUTO_APPLIED");
                diffItem.setReviewNote(decision.reason());
                diffItemMapper.updateById(diffItem);
                reviewEvidence.recordReviewDecision(snapshot.getId(), diffItem.getId(), syncId, project, qualifiedName,
                        "AUTO_APPLY", "SUCCESS", CapabilityChangePolicy.VERSION, decision.reason());
                applied++;
            }
            if ("SOURCE".equals(intakeMode)) {
                if (automatic && "UNCHANGED".equals(changeType)) {
                    catalogProjectionStore.applySdkCapabilityCatalogRow(project, registration, storageName,
                            qualifiedName, capabilityName, diffItem);
                }
                String acceptedHash = businessMethods.find(qualifiedName)
                        .filter(asset -> asset.getAcceptedRevisionId() != null && "ACCEPTED".equals(asset.getStatus()))
                        .map(asset -> businessMethods.acceptedRevision(asset).getInvocationHash()).orElse(null);
                changeLifecycle.observeSource(project, snapshot, diffItem, changePolicy.contractHash(registration),
                        acceptedHash);
            }
        }

        int deleted = 0;
        for (BusinessMethodAssetEntity method : businessMethods.inventory(project.getId())) {
            if (reportedQualifiedNames.contains(method.getQualifiedName())) continue;
            ScanProjectToolEntity row = scanProjectToolMapper.selectOne(Wrappers.<ScanProjectToolEntity>lambdaQuery()
                    .eq(ScanProjectToolEntity::getProjectId, project.getId())
                    .eq(ScanProjectToolEntity::getSourceQualifiedName, method.getQualifiedName()).last("limit 1"));
            deleted++;
            recordDeletedSource(project, snapshot, syncId, intakeMode, method.getQualifiedName(), method.getMethodCode(),
                    method.getInvocationName(), row, row == null ? null : row.getGlobalToolDefinitionId(), items);
        }
        if ("SOURCE".equals(intakeMode)) changeLifecycle.finishSourceObservation(project.getId(), snapshot);
        HttpApiSyncSummary httpApiSummary = observeHttpApis(intakeMode, httpApis);
        updateSnapshotSummary(snapshot, capabilities.size(), added, changed, unchanged, deleted, true, applied);
        reviewEvidence.refreshSnapshotReviewStatus(snapshot);
        CapabilitySyncResponse response = new CapabilitySyncResponse(syncId, project.getId(), project.getProjectCode(),
                capabilities.size(), added, changed, unchanged, applied, items, httpApiSummary);
        writeSyncLog(project, syncId, request == null ? null : request.source(), snapshot.getStatus(), response, null);
        return response;
    }

    private void recordDeletedSource(ScanProjectEntity project, CapabilitySnapshotEntity snapshot, String syncId,
                                     String intakeMode, String qualifiedName, String methodCode, String invocationName,
                                     ScanProjectToolEntity row, Long projectionId, List<CapabilityDiffItem> items) {
        ToolDefinitionEntity projection = catalogProjectionStore.findGlobalTool(row, qualifiedName, projectionId);
        Map<String, Object> impact = changeLifecycle.decisionImpact(changePolicy.decide("DELETED", null, List.of()));
        items.add(new CapabilityDiffItem(qualifiedName, methodCode, "DELETED", projectionId, invocationName, List.of(), impact));
        CapabilityDiffItemEntity diff = insertDiffItem(snapshot, project, syncId, qualifiedName, methodCode, invocationName,
                "DELETED", projectionId, List.of(), impact, catalogProjectionStore.captureState(qualifiedName, row, projection));
        changeLifecycle.prepareCandidate(snapshot, diff, null);
        if ("SOURCE".equals(intakeMode)) {
            String acceptedHash = businessMethods.find(qualifiedName)
                    .filter(method -> method.getAcceptedRevisionId() != null && "ACCEPTED".equals(method.getStatus()))
                    .map(method -> businessMethods.acceptedRevision(method).getInvocationHash())
                    .orElse(null);
            changeLifecycle.observeSource(project, snapshot, diff, null, acceptedHash);
        }
    }

    /**
     * A repeated receipt deliberately avoids a new source observation, snapshot, candidate and
     * apply record. It can nevertheless repair a derived type column when the source state still
     * proves that this exact already-accepted contract is current.
     */
    private void repairRepeatedAcceptedSourceProjection(ScanProjectEntity project, String intakeMode,
                                                         List<CapabilityRegistration> capabilities,
                                                         CapabilitySnapshotEntity repeated) {
        if (!"SOURCE".equals(intakeMode)) {
            return;
        }
        for (CapabilityRegistration registration : capabilities) {
            String capabilityName = normalizeCapabilityName(registration.name());
            String storageName = storageName(project.getProjectCode(), capabilityName);
            String qualifiedName = project.getProjectCode() + ":" + capabilityName;
            CapabilityDiffItemEntity repeatedItem = diffItemMapper.selectOne(Wrappers.<CapabilityDiffItemEntity>lambdaQuery()
                    .eq(CapabilityDiffItemEntity::getSnapshotId, repeated.getId())
                    .eq(CapabilityDiffItemEntity::getProjectId, project.getId())
                    .eq(CapabilityDiffItemEntity::getQualifiedName, qualifiedName)
                    .last("limit 1"));
            if (!isAcceptedRepeatedSourceItem(repeatedItem)) {
                continue;
            }
            BusinessMethodAssetEntity method = businessMethods.find(qualifiedName).orElse(null);
            CapabilitySourceStateEntity source = changeLifecycle.sourceState(qualifiedName);
            if (method != null && "ACCEPTED".equals(method.getStatus()) && method.getAcceptedRevisionId() != null
                    && Objects.equals(method.getProjectId(), project.getId()) && source != null
                    && Objects.equals(source.getSnapshotId(), repeatedItem.getSnapshotId())
                    && Objects.equals(source.getDiffItemId(), repeatedItem.getId())
                    && Objects.equals(source.getSourceContractHash(), source.getAcceptedContractHash())
                    && Objects.equals(source.getAcceptedContractHash(), businessMethods.acceptedRevision(method).getInvocationHash())
                    && "READY".equals(source.getAvailability())
                    && changePolicy.hash(businessMethods.acceptedDeclaration(method)).equals(changePolicy.hash(registration))) {
                catalogProjectionStore.applySdkCapabilityCatalogRow(project, registration, storageName,
                        qualifiedName, capabilityName, repeatedItem);
            }
        }
    }

    private boolean isAcceptedRepeatedSourceItem(CapabilityDiffItemEntity item) {
        return item != null && Set.of("AUTO_APPLIED", "APPLIED", "UNCHANGED").contains(item.getReviewStatus());
    }

    private CapabilitySyncResponse repeatedSnapshot(ScanProjectEntity project, CapabilitySnapshotEntity snapshot,
                                                    HttpApiSyncSummary httpApiSummary) {
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
                snapshot.getReceived(), snapshot.getAdded(), snapshot.getChanged(), snapshot.getUnchanged(), 0, items,
                httpApiSummary);
    }

    private CapabilitySnapshotEntity createSnapshot(ScanProjectEntity project,
                                                     String syncId,
                                                     CapabilitySyncRequest request,
                                                     List<CapabilityRegistration> capabilities,
                                                     StarterMvcHttpApiIntakeService.Plan httpApis) {
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
                capabilities,
                httpApis.supported() ? httpApis.snapshotRegistrations() : null)));
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

    private String syncContentHash(List<CapabilityRegistration> capabilities,
                                   StarterMvcHttpApiIntakeService.Plan httpApis) {
        Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("capabilities", capabilities);
        content.put("httpApis", httpApis.supported() ? httpApis.snapshotRegistrations() : null);
        return changePolicy.hash(content);
    }

    private HttpApiSyncSummary observeHttpApis(String intakeMode, StarterMvcHttpApiIntakeService.Plan httpApis) {
        if (!httpApis.supported()) {
            return new HttpApiSyncSummary(false, 0, 0, 0);
        }
        if (!"SOURCE".equals(intakeMode)) {
            return new HttpApiSyncSummary(true, httpApis.operations().size(), 0, 0);
        }
        return httpApiIntake.observe(httpApis);
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

    private List<FieldDiff> fieldDiffsFromMethod(CapabilityRegistration accepted, CapabilityRegistration candidate) {
        List<FieldDiff> diffs = new ArrayList<>();
        com.fasterxml.jackson.databind.JsonNode before = objectMapper.valueToTree(accepted);
        com.fasterxml.jackson.databind.JsonNode after = objectMapper.valueToTree(candidate);
        after.fields().forEachRemaining(field -> {
            if (!"name".equals(field.getKey())) addDiff(diffs, field.getKey(), before.get(field.getKey()), field.getValue());
        });
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

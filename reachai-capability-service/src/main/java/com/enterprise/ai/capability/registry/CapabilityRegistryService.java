package com.enterprise.ai.capability.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.registry.CapabilityDiffItemEntity;
import com.enterprise.ai.agent.registry.CapabilityDiffItemMapper;
import com.enterprise.ai.agent.registry.CapabilitySnapshotEntity;
import com.enterprise.ai.agent.registry.CapabilitySnapshotMapper;
import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityDiffItemDTO;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityRegistration;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityReviewRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySnapshotDTO;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySyncRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySyncResponse;
import com.enterprise.ai.agent.registry.RegistryContracts.InstanceHeartbeatRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.InstanceHeartbeatResponse;
import com.enterprise.ai.agent.registry.RegistryContracts.ProjectRegisterRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.RegistryProjectResponse;
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

@Service
@RequiredArgsConstructor
public class CapabilityRegistryService {

    private final ScanProjectMapper scanProjectMapper;
    private final CapabilitySnapshotMapper snapshotMapper;
    private final CapabilityDiffItemMapper diffItemMapper;
    private final RegistrySecurityService registrySecurityService;
    private final RegistryProjectRegistrationService projectRegistration;
    private final ObjectMapper objectMapper;
    private final CapabilityChangePolicy changePolicy;
    private final CapabilityChangeLifecycle changeLifecycle;
    private final CapabilityCatalogProjectionStore catalogProjectionStore;
    private final RegistryInstanceLifecycleService instanceLifecycle;
    private final CapabilitySourceIntakeService sourceIntake;
    private final CapabilityReviewEvidenceStore reviewEvidence;

    @Transactional
    public RegistryProjectResponse registerProject(ProjectRegisterRequest request,
                                                   String enrollmentToken,
                                                   RegistrySecurityService.RegistrySignatureHeaders signatureHeaders) {
        return projectRegistration.registerProject(request, enrollmentToken, signatureHeaders);
    }

    @Transactional
    public InstanceHeartbeatResponse heartbeat(String projectCode, InstanceHeartbeatRequest request) {
        return instanceLifecycle.heartbeat(getProject(projectCode), request);
    }

    public List<ProjectInstanceEntity> listInstances(String projectCode) {
        return instanceLifecycle.listInstances(getProject(projectCode));
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
        instanceLifecycle.offline(getProject(projectCode), instanceId);
    }

    @Transactional
    public ProjectInstanceEntity updateInstanceStatus(String projectCode, String instanceId, String status) {
        return instanceLifecycle.updateInstanceStatus(getProject(projectCode), instanceId, status);
    }

    @Transactional
    public int purgeOfflineInstances(String projectCode, int minIdleMinutes) {
        return instanceLifecycle.purgeOfflineInstances(getProject(projectCode), minIdleMinutes);
    }

    @Transactional
    public CapabilitySyncResponse diff(String projectCode, CapabilitySyncRequest request) {
        return sourceIntake.diagnose(getProject(projectCode), request);
    }

    @Transactional
    public CapabilitySyncResponse sync(String projectCode, CapabilitySyncRequest request) {
        // The server owns automatic admission. The caller's apply flag is ignored.
        return sourceIntake.receiveSource(getProject(projectCode), request);
    }

    @Transactional
    public CapabilitySyncResponse syncFromProject(
            String projectCode,
            CapabilitySyncRequest request,
            RegistrySecurityService.RegistrySignatureHeaders signatureHeaders) {
        registrySecurityService.verifyRequired(projectCode, signatureHeaders);
        return sourceIntake.receiveSource(getProject(projectCode), request);
    }

    @Transactional
    public CapabilitySyncResponse apply(String projectCode, CapabilitySyncRequest request) {
        // No separate bypass: legacy in-process callers enter the same policy.
        return sourceIntake.receiveSource(getProject(projectCode), request);
    }

    public List<CapabilitySnapshotDTO> listSnapshots(String projectCode) {
        ScanProjectEntity project = getProject(projectCode);
        return snapshotMapper.selectList(Wrappers.<CapabilitySnapshotEntity>lambdaQuery()
                        .eq(CapabilitySnapshotEntity::getProjectId, project.getId())
                        .orderByDesc(CapabilitySnapshotEntity::getId).last("limit 100"))
                .stream()
                .map(this::toSnapshotDto)
                .toList();
    }

    public List<CapabilityDiffItemDTO> listDiffItems(String projectCode, Long snapshotId) {
        ScanProjectEntity project = getProject(projectCode);
        CapabilitySnapshotEntity snapshot = requireProjectSnapshot(project, snapshotId);
        List<CapabilityDiffItemEntity> items = diffItemMapper.selectList(
                Wrappers.<CapabilityDiffItemEntity>lambdaQuery()
                        .eq(CapabilityDiffItemEntity::getSnapshotId, snapshot.getId())
                        .eq(CapabilityDiffItemEntity::getProjectId, project.getId())
                        .eq(CapabilityDiffItemEntity::getProjectCode, project.getProjectCode())
                        .orderByAsc(CapabilityDiffItemEntity::getId));
        items.forEach(item -> requireDiffItemOwnership(project, snapshot, item));
        return items.stream()
                .map(this::toDiffItemDto)
                .toList();
    }

    public CapabilityChangePage listChanges(String projectCode, String state, String keyword, int current, int size) {
        ScanProjectEntity project = getProject(projectCode);
        if (!Set.of("PENDING", "PROCESSED", "ALL").contains(state)) {
            throw new IllegalArgumentException("变化筛选状态无效");
        }
        var query = Wrappers.<CapabilityDiffItemEntity>lambdaQuery()
                .eq(CapabilityDiffItemEntity::getProjectId, project.getId())
                .eq(CapabilityDiffItemEntity::getIntakeMode, "SOURCE");
        if ("PENDING".equals(state)) query.eq(CapabilityDiffItemEntity::getReviewStatus, "PENDING");
        if ("PROCESSED".equals(state)) query.ne(CapabilityDiffItemEntity::getReviewStatus, "PENDING");
        if (StringUtils.hasText(keyword)) query.and(filter -> filter.like(CapabilityDiffItemEntity::getName, keyword.trim())
                .or().like(CapabilityDiffItemEntity::getQualifiedName, keyword.trim())
                .or().like(CapabilityDiffItemEntity::getImpactJson, keyword.trim()));
        query.orderByDesc(CapabilityDiffItemEntity::getId);
        var page = diffItemMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                Math.max(1, current), Math.min(50, Math.max(1, size))), query);
        long pending = diffItemMapper.selectCount(Wrappers.<CapabilityDiffItemEntity>lambdaQuery()
                .eq(CapabilityDiffItemEntity::getProjectId, project.getId())
                .eq(CapabilityDiffItemEntity::getIntakeMode, "SOURCE")
                .eq(CapabilityDiffItemEntity::getReviewStatus, "PENDING"));
        long automated = diffItemMapper.selectCount(Wrappers.<CapabilityDiffItemEntity>lambdaQuery()
                .eq(CapabilityDiffItemEntity::getProjectId, project.getId())
                .eq(CapabilityDiffItemEntity::getReviewStatus, "AUTO_APPLIED"));
        return new CapabilityChangePage(page.getRecords().stream().map(this::toDiffItemDto).toList(),
                page.getTotal(), page.getCurrent(), page.getSize(), pending, automated);
    }

    public record CapabilityChangePage(List<CapabilityDiffItemDTO> records, long total, long current,
                                       long size, long pending, long automated) { }

    @Transactional
    public CapabilityDiffItemDTO reviewDiffItem(String projectCode,
                                                Long diffItemId,
                                                CapabilityReviewRequest request) {
        ScanProjectEntity project = getProject(projectCode);
        scanProjectMapper.lockCapabilityChanges(project.getId());
        CapabilityDiffItemEntity item = requireProjectDiffItem(project, diffItemId);
        CapabilitySnapshotEntity snapshot = requireProjectSnapshot(project, item.getSnapshotId());
        requireDiffItemOwnership(project, snapshot, item);
        if (request == null || !StringUtils.hasText(request.action())) {
            throw new IllegalArgumentException("评审动作不能为空");
        }
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("APPLY", "IGNORE").contains(action)) {
            throw new IllegalArgumentException("不支持的评审动作: " + action);
        }
        if (!"PENDING".equalsIgnoreCase(item.getReviewStatus())) {
            throw new IllegalArgumentException("只有待评审的差异项可以应用或忽略");
        }
        changeLifecycle.assertCurrent(item);
        if (!"IGNORE".equals(action)) {
            catalogProjectionStore.assertCatalogStateUnchanged(project, item);
            if ("DELETED".equalsIgnoreCase(item.getChangeType())) {
                catalogProjectionStore.markCatalogRowRemoved(project, item);
                changeLifecycle.updateAccepted(item.getQualifiedName(), null);
                item.setReviewStatus("APPLIED");
                item.setReviewNote(request == null ? null : request.note());
                item.setUpdatedAt(LocalDateTime.now());
                diffItemMapper.updateById(item);
                reviewEvidence.recordReviewDecision(snapshot.getId(), item.getId(), item.getSyncId(), project, item.getQualifiedName(),
                        "CATALOG_REMOVED", "SUCCESS", defaultString(request == null ? null : request.operator(), "system"),
                        request == null ? null : request.note());
                reviewEvidence.refreshSnapshotReviewStatus(snapshot);
                return toDiffItemDto(item);
            }
            CapabilityRegistration registration = findRegistration(snapshot, item.getQualifiedName());
            if (registration == null) {
                throw new IllegalArgumentException("快照中找不到能力: " + item.getQualifiedName());
            }
            registration = changePolicy.normalize(project, List.of(registration)).get(0);
            String capabilityName = normalizeCapabilityName(registration.name());
            String storageName = StringUtils.hasText(item.getStorageName())
                    ? item.getStorageName()
                    : storageName(project.getProjectCode(), capabilityName);
            catalogProjectionStore.applySdkCapabilityCatalogRow(project, registration, storageName, item.getQualifiedName(), capabilityName);
            changeLifecycle.updateAccepted(item.getQualifiedName(), changePolicy.contractHash(registration));
            item.setReviewStatus("APPLIED");
            item.setReviewNote(request == null ? null : request.note());
            item.setUpdatedAt(LocalDateTime.now());
            diffItemMapper.updateById(item);
            reviewEvidence.recordReviewDecision(snapshot.getId(), item.getId(), item.getSyncId(), project, item.getQualifiedName(),
                    "APPLY", "SUCCESS", defaultString(request == null ? null : request.operator(), "system"),
                    request == null ? null : request.note());
            reviewEvidence.refreshSnapshotReviewStatus(snapshot);
            return toDiffItemDto(item);
        }
        String operator = defaultString(request == null ? null : request.operator(), "system");
        String note = request == null ? null : request.note();
        item.setReviewStatus("IGNORED");
        item.setReviewNote(note);
        item.setUpdatedAt(LocalDateTime.now());
        diffItemMapper.updateById(item);
        reviewEvidence.recordReviewDecision(snapshot.getId(), item.getId(), item.getSyncId(), project, item.getQualifiedName(),
                "IGNORE", "SUCCESS", operator, note);
        reviewEvidence.refreshSnapshotReviewStatus(snapshot);
        return toDiffItemDto(item);
    }

    @Transactional
    public CapabilityDiffItemDTO rollbackDiffItem(String projectCode,
                                                  Long diffItemId,
                                                  CapabilityReviewRequest request) {
        ScanProjectEntity project = getProject(projectCode);
        scanProjectMapper.lockCapabilityChanges(project.getId());
        CapabilityDiffItemEntity item = requireProjectDiffItem(project, diffItemId);
        CapabilitySnapshotEntity snapshot = requireProjectSnapshot(project, item.getSnapshotId());
        requireDiffItemOwnership(project, snapshot, item);
        if (!Set.of("APPLIED", "AUTO_APPLIED").contains(item.getReviewStatus())) {
            throw new IllegalArgumentException("只有已应用的评审项可以回滚");
        }
        if (!StringUtils.hasText(item.getBeforeStateJson())) {
            throw new IllegalArgumentException("该评审项创建时未保存回滚状态，请重新生成差异后再应用");
        }
        CapabilityDiffItemEntity latestApplied = diffItemMapper.selectOne(
                Wrappers.<CapabilityDiffItemEntity>lambdaQuery()
                        .eq(CapabilityDiffItemEntity::getQualifiedName, item.getQualifiedName())
                        .eq(CapabilityDiffItemEntity::getProjectId, project.getId())
                        .eq(CapabilityDiffItemEntity::getProjectCode, project.getProjectCode())
                        .in(CapabilityDiffItemEntity::getReviewStatus, List.of("APPLIED", "AUTO_APPLIED"))
                        .orderByDesc(CapabilityDiffItemEntity::getId)
                        .last("limit 1"));
        if (latestApplied != null && !Objects.equals(latestApplied.getId(), item.getId())) {
            throw new IllegalArgumentException("该能力已有更新的已应用变更，不能覆盖式回滚旧快照");
        }

        catalogProjectionStore.restoreCatalogState(project, item);
        ScanProjectToolEntity restoredScan = catalogProjectionStore.findCatalogRow(project, item);
        changeLifecycle.updateAccepted(item.getQualifiedName(), changePolicy.contractHash(
                catalogProjectionStore.findGlobalTool(restoredScan, item.getQualifiedName(), item.getExistingToolId())));

        String operator = defaultString(request == null ? null : request.operator(), "system");
        String note = request == null ? null : request.note();
        item.setReviewStatus("ROLLED_BACK");
        item.setReviewNote(note);
        item.setUpdatedAt(LocalDateTime.now());
        diffItemMapper.updateById(item);
        reviewEvidence.recordReviewDecision(snapshot.getId(), item.getId(), item.getSyncId(), project, item.getQualifiedName(),
                "ROLLBACK", "SUCCESS", operator, note);
        reviewEvidence.refreshSnapshotReviewStatus(snapshot);
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

    private CapabilitySnapshotEntity requireProjectSnapshot(ScanProjectEntity project, Long snapshotId) {
        if (snapshotId == null) {
            throw new IllegalArgumentException("snapshotId 不能为空");
        }
        CapabilitySnapshotEntity snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) {
            throw new IllegalArgumentException("快照不存在: " + snapshotId);
        }
        requireProjectOwnership(project, snapshot.getProjectId(), snapshot.getProjectCode(), "快照", snapshotId);
        return snapshot;
    }

    private CapabilityDiffItemEntity requireProjectDiffItem(ScanProjectEntity project, Long diffItemId) {
        if (diffItemId == null) {
            throw new IllegalArgumentException("diffItemId 不能为空");
        }
        CapabilityDiffItemEntity item = diffItemMapper.selectById(diffItemId);
        if (item == null) {
            throw new IllegalArgumentException("评审项不存在: " + diffItemId);
        }
        requireProjectOwnership(project, item.getProjectId(), item.getProjectCode(), "评审项", diffItemId);
        return item;
    }

    private void requireDiffItemOwnership(ScanProjectEntity project,
                                          CapabilitySnapshotEntity snapshot,
                                          CapabilityDiffItemEntity item) {
        requireProjectOwnership(project, item.getProjectId(), item.getProjectCode(), "评审项", item.getId());
        if (!Objects.equals(snapshot.getId(), item.getSnapshotId())) {
            throw new IllegalArgumentException("评审项不属于快照: " + item.getId());
        }
    }

    private void requireProjectOwnership(ScanProjectEntity project,
                                         Long resourceProjectId,
                                         String resourceProjectCode,
                                         String resourceName,
                                         Long resourceId) {
        if (!Objects.equals(project.getId(), resourceProjectId)
                || !Objects.equals(project.getProjectCode(), resourceProjectCode)) {
            throw new IllegalArgumentException(resourceName + "不属于项目 "
                    + project.getProjectCode() + ": " + resourceId);
        }
    }

    private CapabilitySnapshotDTO toSnapshotDto(CapabilitySnapshotEntity entity) {
        return new CapabilitySnapshotDTO(entity.getId(), entity.getProjectId(), entity.getProjectCode(),
                entity.getSyncId(), entity.getSource(), entity.getStatus(), entity.getReceived(), entity.getAdded(),
                entity.getChanged(), entity.getUnchanged(), entity.getDeleted(), String.valueOf(entity.getCreatedAt()),
                String.valueOf(entity.getUpdatedAt()));
    }

    private CapabilityDiffItemDTO toDiffItemDto(CapabilityDiffItemEntity entity) {
        String impactJson = entity.getImpactJson();
        if ("SOURCE".equals(entity.getIntakeMode())) {
            try {
                Map<String, Object> impact = StringUtils.hasText(impactJson)
                        ? objectMapper.readValue(impactJson, new com.fasterxml.jackson.core.type.TypeReference<>() { })
                        : new LinkedHashMap<>();
                CapabilitySourceStateEntity source = changeLifecycle.sourceState(entity.getQualifiedName());
                impact.put("sourceAvailability", source == null ? "SOURCE_UNKNOWN" : source.getAvailability());
                impact.put("currentCandidate", source != null && Objects.equals(source.getDiffItemId(), entity.getId()));
                impactJson = writeJson(impact);
            } catch (Exception invalid) { throw new IllegalStateException("能力变化证据无法读取", invalid); }
        }
        return new CapabilityDiffItemDTO(entity.getId(), entity.getSnapshotId(), entity.getSyncId(),
                entity.getProjectCode(), entity.getQualifiedName(), entity.getName(), entity.getStorageName(),
                entity.getChangeType(), entity.getExistingToolId(), entity.getFieldDiffJson(), impactJson,
                entity.getReviewStatus(), entity.getReviewNote(), canRollback(entity));
    }

    private boolean canRollback(CapabilityDiffItemEntity item) {
        return StringUtils.hasText(item.getBeforeStateJson()) && Set.of("APPLIED", "AUTO_APPLIED").contains(item.getReviewStatus())
                && diffItemMapper.selectCount(Wrappers.<CapabilityDiffItemEntity>lambdaQuery()
                    .eq(CapabilityDiffItemEntity::getProjectId, item.getProjectId())
                    .eq(CapabilityDiffItemEntity::getQualifiedName, item.getQualifiedName())
                    .in(CapabilityDiffItemEntity::getReviewStatus, List.of("APPLIED", "AUTO_APPLIED"))
                    .gt(CapabilityDiffItemEntity::getId, item.getId())) == 0;
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

}

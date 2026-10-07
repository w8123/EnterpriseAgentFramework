package com.enterprise.ai.capability.catalog.businessmethod;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.registry.CapabilitySnapshotEntity;
import com.enterprise.ai.agent.registry.CapabilitySnapshotMapper;
import com.enterprise.ai.agent.registry.CapabilityDiffItemMapper;
import com.enterprise.ai.agent.registry.CapabilityDiffItemEntity;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityRegistration;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySyncRequest;
import com.enterprise.ai.capability.registry.CapabilityAssetType;
import com.enterprise.ai.capability.registry.CapabilityChangePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Maintains accepted method facts inside the project-locked Registry transaction, before Tool projection. */
@Component
@RequiredArgsConstructor
public class BusinessMethodAssetStore {
    private final BusinessMethodAssetMapper assets;
    private final BusinessMethodRevisionMapper revisions;
    private final CapabilitySnapshotMapper snapshots;
    private final CapabilityDiffItemMapper differences;
    private final CapabilityChangePolicy policy;
    private final ObjectMapper json;

    public Optional<BusinessMethodAssetEntity> find(String qualifiedName) {
        if (!StringUtils.hasText(qualifiedName)) return Optional.empty();
        return Optional.ofNullable(assets.selectOne(Wrappers.<BusinessMethodAssetEntity>lambdaQuery()
                .eq(BusinessMethodAssetEntity::getQualifiedName, qualifiedName.trim())));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public BusinessMethodAssetEntity accept(ScanProjectEntity project, CapabilityRegistration declaration,
                                           Long snapshotId, Long diffItemId) {
        if (policy.assetType(declaration) != CapabilityAssetType.BUSINESS_METHOD) {
            throw new IllegalArgumentException("业务方法资产只能接纳明确的 BUSINESS_METHOD 声明");
        }
        if (project == null || project.getId() == null || !StringUtils.hasText(project.getProjectCode())
                || snapshotId == null || diffItemId == null) {
            throw new IllegalArgumentException("业务方法接纳必须绑定项目、来源快照和变化记录");
        }
        CapabilitySnapshotEntity snapshot = snapshots.selectById(snapshotId);
        if (snapshot == null || !Objects.equals(project.getId(), snapshot.getProjectId())
                || !Objects.equals(project.getProjectCode(), snapshot.getProjectCode())
                || !"SOURCE".equals(snapshot.getIntakeMode())) {
            throw new IllegalArgumentException("业务方法接纳来源快照不属于当前项目或可信来源");
        }
        String qualifiedName = project.getProjectCode() + ":" + declaration.name();
        CapabilityDiffItemEntity item = differences.selectById(diffItemId);
        if (item == null || !Objects.equals(snapshotId, item.getSnapshotId())
                || !Objects.equals(project.getId(), item.getProjectId())
                || !Objects.equals(project.getProjectCode(), item.getProjectCode())
                || !Objects.equals(qualifiedName, item.getQualifiedName())
                || !"SOURCE".equals(item.getIntakeMode()) || "DELETED".equals(item.getChangeType())
                || !List.of("PENDING", "APPLIED", "AUTO_APPLIED", "UNCHANGED").contains(item.getReviewStatus())) {
            throw new IllegalArgumentException("业务方法接纳变化记录与当前来源声明不一致");
        }
        CapabilityRegistration recorded = declaration(snapshot, declaration.name());
        if (!policy.hash(recorded).equals(policy.hash(declaration))) {
            throw new IllegalArgumentException("接纳的业务方法与不可变来源快照不一致");
        }
        BusinessMethodAssetEntity asset = find(qualifiedName).orElseGet(BusinessMethodAssetEntity::new);
        if (asset.getId() != null && !Objects.equals(project.getId(), asset.getProjectId())) {
            throw new IllegalArgumentException("业务方法稳定身份已属于其他项目");
        }
        LocalDateTime now = LocalDateTime.now();
        if (asset.getId() == null) {
            asset.setProjectId(project.getId());
            asset.setProjectCode(project.getProjectCode());
            asset.setMethodCode(declaration.name());
            asset.setQualifiedName(qualifiedName);
            asset.setInvocationName(invocationName(project.getProjectCode(), declaration.name()));
            asset.setTitle(declaration.title());
            asset.setDescription(declaration.description());
            asset.setStatus("ACCEPTED");
            asset.setEnabled(Boolean.TRUE.equals(declaration.enabled()));
            asset.setSideEffect(CapabilityChangePolicy.sideEffect(declaration.sideEffect()));
            asset.setCreatedAt(now);
            asset.setUpdatedAt(now);
            assets.insert(asset);
        }

        String invocationHash = policy.contractHash(declaration);
        BusinessMethodRevisionEntity current = asset.getAcceptedRevisionId() == null ? null
                : revisions.selectById(asset.getAcceptedRevisionId());
        // Display-only edits keep the business fingerprint but still reference the new immutable source content.
        boolean identical = current != null && Objects.equals(current.getInvocationHash(), invocationHash)
                && policy.hash(acceptedDeclaration(asset)).equals(policy.hash(declaration));
        if (!identical) {
            BusinessMethodRevisionEntity revision = new BusinessMethodRevisionEntity();
            revision.setAssetId(asset.getId());
            revision.setSnapshotId(snapshotId);
            revision.setDiffItemId(diffItemId);
            revision.setContractHash(businessContractHash(declaration));
            revision.setInvocationHash(invocationHash);
            revision.setBindingHash(bindingHash(declaration));
            revision.setAcceptedAt(now);
            revisions.insert(revision);
            asset.setAcceptedRevisionId(revision.getId());
        }
        asset.setTitle(declaration.title());
        asset.setDescription(declaration.description());
        asset.setEnabled(Boolean.TRUE.equals(declaration.enabled()));
        asset.setSideEffect(CapabilityChangePolicy.sideEffect(declaration.sideEffect()));
        asset.setStatus("ACCEPTED");
        asset.setUpdatedAt(now);
        assets.updateById(asset);
        return asset;
    }

    public List<BusinessMethodAssetEntity> inventory(Long projectId) {
        return assets.selectList(Wrappers.<BusinessMethodAssetEntity>lambdaQuery()
                .eq(BusinessMethodAssetEntity::getProjectId, projectId)
                .isNotNull(BusinessMethodAssetEntity::getAcceptedRevisionId)
                .eq(BusinessMethodAssetEntity::getStatus, "ACCEPTED"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void remove(String qualifiedName) {
        find(qualifiedName).ifPresent(asset -> {
            asset.setStatus("REMOVED");
            asset.setEnabled(false);
            asset.setUpdatedAt(LocalDateTime.now());
            assets.updateById(asset);
        });
    }

    public BusinessMethodRevisionEntity acceptedRevision(BusinessMethodAssetEntity asset) {
        BusinessMethodRevisionEntity revision = asset == null || asset.getAcceptedRevisionId() == null ? null
                : revisions.selectById(asset.getAcceptedRevisionId());
        if (revision == null || !Objects.equals(asset.getId(), revision.getAssetId())) {
            throw new IllegalStateException("业务方法缺少属于该资产的接纳修订");
        }
        return revision;
    }

    public CapabilityRegistration acceptedDeclaration(BusinessMethodAssetEntity asset) {
        BusinessMethodRevisionEntity revision = acceptedRevision(asset);
        CapabilitySnapshotEntity snapshot = snapshots.selectById(revision.getSnapshotId());
        if (snapshot == null || !Objects.equals(asset.getProjectId(), snapshot.getProjectId())
                || !Objects.equals(asset.getProjectCode(), snapshot.getProjectCode())
                || !"SOURCE".equals(snapshot.getIntakeMode())) {
            throw new IllegalStateException("业务方法接纳快照缺失或项目来源不一致");
        }
        CapabilityDiffItemEntity decision = differences.selectById(revision.getDiffItemId());
        if (decision == null || !Objects.equals(revision.getSnapshotId(), decision.getSnapshotId())
                || !Objects.equals(asset.getProjectId(), decision.getProjectId())
                || !Objects.equals(asset.getProjectCode(), decision.getProjectCode())
                || !Objects.equals(asset.getQualifiedName(), decision.getQualifiedName())
                || !"SOURCE".equals(decision.getIntakeMode())) {
            throw new IllegalStateException("业务方法接纳修订缺少属于该资产的来源决定");
        }
        CapabilityRegistration declaration = declaration(snapshot, asset.getMethodCode());
        if (policy.assetType(declaration) != CapabilityAssetType.BUSINESS_METHOD
                || !Objects.equals(revision.getContractHash(), businessContractHash(declaration))
                || !Objects.equals(revision.getInvocationHash(), policy.contractHash(declaration))
                || !Objects.equals(revision.getBindingHash(), bindingHash(declaration))) {
            throw new IllegalStateException("业务方法不可变接纳修订与来源内容不一致");
        }
        return declaration;
    }

    public String businessContractHash(CapabilityRegistration declaration) {
        ObjectNode contract = json.valueToTree(declaration);
        contract.remove(List.of("name", "title", "description", "httpMethod", "baseUrl", "contextPath", "endpointPath"));
        return policy.hash(contract);
    }

    public static String invocationName(String projectCode, String methodCode) {
        return (projectCode + "_" + methodCode).replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("_+", "_");
    }

    public String bindingHash(CapabilityRegistration declaration) {
        ObjectNode binding = json.createObjectNode();
        binding.put("httpMethod", declaration.httpMethod());
        binding.put("baseUrl", declaration.baseUrl());
        binding.put("contextPath", declaration.contextPath());
        binding.put("endpointPath", declaration.endpointPath());
        return policy.hash(binding);
    }

    public JsonNode captureState(String qualifiedName) {
        BusinessMethodAssetEntity asset = find(qualifiedName).orElse(null);
        if (asset == null) return json.getNodeFactory().nullNode();
        ObjectNode state = json.createObjectNode();
        state.put("id", asset.getId());
        state.put("qualifiedName", asset.getQualifiedName());
        if (asset.getAcceptedRevisionId() == null) state.putNull("acceptedRevisionId");
        else state.put("acceptedRevisionId", asset.getAcceptedRevisionId());
        state.put("status", asset.getStatus());
        state.put("enabled", Boolean.TRUE.equals(asset.getEnabled()));
        return state;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void restore(String qualifiedName, JsonNode state) {
        BusinessMethodAssetEntity asset = find(qualifiedName).orElse(null);
        if (state == null || state.isNull()) {
            if (asset != null) {
                asset.setAcceptedRevisionId(null);
                asset.setStatus("UNACCEPTED");
                asset.setEnabled(false);
                asset.setUpdatedAt(LocalDateTime.now());
                assets.updateById(asset);
            }
            return;
        }
        if (asset == null || !state.isObject() || state.path("id").asLong(-1) != asset.getId()
                || !Objects.equals(qualifiedName, state.path("qualifiedName").asText())) {
            throw new IllegalArgumentException("业务方法回滚状态与当前稳定资产不一致");
        }
        JsonNode revisionId = state.get("acceptedRevisionId");
        asset.setAcceptedRevisionId(revisionId == null || revisionId.isNull() ? null : revisionId.asLong());
        if (asset.getAcceptedRevisionId() != null) {
            CapabilityRegistration declaration = acceptedDeclaration(asset);
            asset.setTitle(declaration.title());
            asset.setDescription(declaration.description());
            asset.setSideEffect(CapabilityChangePolicy.sideEffect(declaration.sideEffect()));
        }
        String status = state.path("status").asText();
        if (!List.of("ACCEPTED", "REMOVED", "UNACCEPTED").contains(status)) {
            throw new IllegalArgumentException("业务方法回滚状态无效");
        }
        asset.setStatus(status);
        asset.setEnabled(state.path("enabled").asBoolean(false));
        asset.setUpdatedAt(LocalDateTime.now());
        assets.updateById(asset);
    }

    private CapabilityRegistration declaration(CapabilitySnapshotEntity snapshot, String methodCode) {
        try {
            CapabilitySyncRequest request = json.readValue(snapshot.getPayloadJson(), CapabilitySyncRequest.class);
            List<CapabilityRegistration> matches = request.capabilities() == null ? List.of()
                    : request.capabilities().stream().filter(item -> item != null
                            && Objects.equals(methodCode, item.name())).toList();
            if (matches.size() != 1) throw new IllegalStateException("快照中的业务方法必须唯一存在");
            return matches.get(0);
        } catch (IllegalStateException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalStateException("业务方法来源快照无法读取", invalid);
        }
    }
}

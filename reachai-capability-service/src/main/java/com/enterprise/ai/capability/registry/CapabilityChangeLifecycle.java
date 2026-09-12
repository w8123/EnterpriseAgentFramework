package com.enterprise.ai.capability.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.registry.CapabilityDiffItemEntity;
import com.enterprise.ai.agent.registry.CapabilityDiffItemMapper;
import com.enterprise.ai.agent.registry.CapabilitySnapshotEntity;
import com.enterprise.ai.agent.registry.CapabilitySnapshotMapper;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityRegistration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Called inside the project-locked registry transaction; observations are never inferred from decisions. */
@Service
@RequiredArgsConstructor
public class CapabilityChangeLifecycle {
    private final CapabilitySnapshotMapper snapshots;
    private final CapabilityDiffItemMapper differences;
    private final CapabilitySourceStateMapper sources;
    private final CapabilityChangePolicy policy;
    private final CapabilitySyncReceiptMapper receipts;

    public CapabilitySnapshotEntity findRepeat(Long projectId, String syncId, String mode,
                                               List<CapabilityRegistration> capabilities) {
        if (syncId.length() > 64) throw new IllegalArgumentException("同步标识不能超过 64 个字符");
        String hash = policy.hash(capabilities);
        CapabilitySyncReceiptEntity receipt = receipts.selectOne(Wrappers.<CapabilitySyncReceiptEntity>lambdaQuery()
                .eq(CapabilitySyncReceiptEntity::getProjectId, projectId)
                .eq(CapabilitySyncReceiptEntity::getSyncId, syncId).last("limit 1 FOR UPDATE"));
        if (receipt != null && (!hash.equals(receipt.getContentHash()) || !mode.equals(receipt.getIntakeMode()))) {
            throw new IllegalArgumentException("同一同步标识不能用于不同内容或来源，请使用新的同步标识");
        }
        CapabilitySnapshotEntity retry = snapshots.selectOne(Wrappers.<CapabilitySnapshotEntity>lambdaQuery()
                .eq(CapabilitySnapshotEntity::getProjectId, projectId)
                .eq(receipt == null, CapabilitySnapshotEntity::getSyncId, syncId)
                .eq(receipt != null, CapabilitySnapshotEntity::getId, receipt == null ? null : receipt.getSnapshotId())
                .last("limit 1 FOR UPDATE"));
        if (receipt != null && retry == null) throw new IllegalStateException("同步回执引用的快照不存在");
        if (retry != null && (!hash.equals(retry.getContentHash()) || !mode.equals(retry.getIntakeMode()))) {
            throw new IllegalArgumentException("同一同步标识不能用于不同内容或来源，请使用新的同步标识");
        }
        CapabilitySnapshotEntity latest = retry == null
                ? snapshots.selectOne(Wrappers.<CapabilitySnapshotEntity>lambdaQuery()
                    .eq(CapabilitySnapshotEntity::getProjectId, projectId)
                    .eq(CapabilitySnapshotEntity::getIntakeMode, mode)
                    .orderByDesc(CapabilitySnapshotEntity::getId).last("limit 1 FOR UPDATE")) : retry;
        if (latest == null || !hash.equals(latest.getContentHash())) return null;
        if (retry == null && "SOURCE".equals(mode) && differences.selectCount(Wrappers.<CapabilityDiffItemEntity>lambdaQuery()
                .eq(CapabilityDiffItemEntity::getSnapshotId, latest.getId())
                .eq(CapabilityDiffItemEntity::getReviewStatus, "ROLLED_BACK")) > 0) return null;
        latest.setReportCount((latest.getReportCount() == null ? 1 : latest.getReportCount()) + 1);
        latest.setLastSeenAt(LocalDateTime.now());
        snapshots.updateById(latest);
        if (receipt == null) recordSyncIdentity(projectId, syncId, latest);
        return latest;
    }

    public void recordSyncIdentity(Long projectId, String syncId, CapabilitySnapshotEntity snapshot) {
        var receipt = new CapabilitySyncReceiptEntity();
        receipt.setProjectId(projectId); receipt.setSyncId(syncId); receipt.setSnapshotId(snapshot.getId());
        receipt.setIntakeMode(snapshot.getIntakeMode()); receipt.setContentHash(snapshot.getContentHash());
        receipt.setCreatedAt(LocalDateTime.now());
        receipts.insert(receipt);
    }

    public Map<String, Object> decisionImpact(CapabilityChangePolicy.Decision decision) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("policyVersion", CapabilityChangePolicy.VERSION);
        impact.put("reasonCode", decision.reasonCode());
        impact.put("reason", decision.reason());
        impact.put("automatic", decision.automatic());
        impact.put("runtimeEvidence", "UNKNOWN");
        impact.put("publicationEvidence", "UNKNOWN");
        return impact;
    }

    public void beginSourceObservation(Long projectId) {
        snapshots.update(null, Wrappers.<CapabilitySnapshotEntity>lambdaUpdate()
                .eq(CapabilitySnapshotEntity::getProjectId, projectId)
                .eq(CapabilitySnapshotEntity::getIntakeMode, "SOURCE")
                .in(CapabilitySnapshotEntity::getStatus, List.of("PENDING", "PARTIAL"))
                .set(CapabilitySnapshotEntity::getStatus, "SUPERSEDED")
                .set(CapabilitySnapshotEntity::getUpdatedAt, LocalDateTime.now()));
        differences.update(null, Wrappers.<CapabilityDiffItemEntity>lambdaUpdate()
                .eq(CapabilityDiffItemEntity::getProjectId, projectId)
                .eq(CapabilityDiffItemEntity::getIntakeMode, "SOURCE")
                .eq(CapabilityDiffItemEntity::getReviewStatus, "PENDING")
                .set(CapabilityDiffItemEntity::getReviewStatus, "SUPERSEDED")
                .set(CapabilityDiffItemEntity::getUpdatedAt, LocalDateTime.now()));
    }

    public void finishSourceObservation(Long projectId, CapabilitySnapshotEntity snapshot) {
        // A previously proposed addition may disappear before ever entering the catalog.
        // It still ceases to be a current candidate and cannot retain a stale source observation.
        sources.update(null, Wrappers.<CapabilitySourceStateEntity>lambdaUpdate()
                .eq(CapabilitySourceStateEntity::getProjectId, projectId)
                .ne(CapabilitySourceStateEntity::getSnapshotId, snapshot.getId())
                .set(CapabilitySourceStateEntity::getSnapshotId, snapshot.getId())
                .set(CapabilitySourceStateEntity::getSourceContractHash, null)
                .set(CapabilitySourceStateEntity::getAvailability, "SOURCE_MISSING")
                .set(CapabilitySourceStateEntity::getObservedAt, LocalDateTime.now()));
    }

    public void prepareCandidate(CapabilitySnapshotEntity snapshot, CapabilityDiffItemEntity item,
                                 CapabilityRegistration registration) {
        item.setIntakeMode(snapshot.getIntakeMode());
        item.setCandidateHash(policy.hash(Arrays.asList(registration, item.getBeforeStateJson())));
        if (!"SOURCE".equals(snapshot.getIntakeMode())) {
            item.setReviewStatus("DIAGNOSTIC");
        } else {
            List<CapabilityDiffItemEntity> previous = differences.selectList(Wrappers.<CapabilityDiffItemEntity>lambdaQuery()
                    .eq(CapabilityDiffItemEntity::getProjectId, item.getProjectId())
                    .eq(CapabilityDiffItemEntity::getQualifiedName, item.getQualifiedName())
                    .eq(CapabilityDiffItemEntity::getIntakeMode, "SOURCE")
                    .ne(CapabilityDiffItemEntity::getId, item.getId())
                    .in(CapabilityDiffItemEntity::getReviewStatus, List.of("PENDING", "IGNORED"))
                    .orderByDesc(CapabilityDiffItemEntity::getId));
            for (CapabilityDiffItemEntity old : previous) {
                if ("IGNORED".equals(old.getReviewStatus()) && item.getCandidateHash().equals(old.getCandidateHash())
                        && "PENDING".equals(item.getReviewStatus())) {
                    item.setReviewStatus("IGNORED");
                    item.setReviewNote(old.getReviewNote());
                }
                if ("PENDING".equals(old.getReviewStatus())) {
                    old.setReviewStatus("SUPERSEDED");
                    old.setUpdatedAt(LocalDateTime.now());
                    differences.updateById(old);
                }
            }
        }
        differences.updateById(item);
    }

    public void observeSource(ScanProjectEntity project, CapabilitySnapshotEntity snapshot,
                              CapabilityDiffItemEntity item, String sourceHash, String acceptedHash) {
        CapabilitySourceStateEntity state = sourceState(item.getQualifiedName());
        if (state == null) {
            state = new CapabilitySourceStateEntity();
            state.setProjectId(project.getId());
            state.setProjectCode(project.getProjectCode());
            state.setQualifiedName(item.getQualifiedName());
        }
        state.setSnapshotId(snapshot.getId());
        state.setDiffItemId(item.getId());
        state.setSourceContractHash(sourceHash);
        state.setAcceptedContractHash(acceptedHash);
        state.setAvailability(availability(sourceHash, acceptedHash));
        state.setObservedAt(LocalDateTime.now());
        if (state.getId() == null) sources.insert(state);
        else sources.updateById(state);
    }

    public void assertCurrent(CapabilityDiffItemEntity item) {
        if (!"SOURCE".equals(item.getIntakeMode())) {
            throw new IllegalArgumentException("诊断与历史候选不改变目录，请从业务系统重新同步");
        }
        CapabilitySourceStateEntity state = sourceState(item.getQualifiedName());
        if (state == null || !Objects.equals(state.getDiffItemId(), item.getId())) {
            throw new IllegalArgumentException("该变化已被新的来源观察替代，请刷新当前待处理变化");
        }
    }

    public void updateAccepted(String qualifiedName, String contractHash) {
        CapabilitySourceStateEntity state = sourceState(qualifiedName);
        if (state == null) return; // A catalog rollback cannot invent a source observation.
        state.setAcceptedContractHash(contractHash);
        state.setAvailability(availability(state.getSourceContractHash(), contractHash));
        sources.updateById(state);
    }

    public CapabilitySourceStateEntity sourceState(String qualifiedName) {
        return sources.selectOne(Wrappers.<CapabilitySourceStateEntity>lambdaQuery()
                .eq(CapabilitySourceStateEntity::getQualifiedName, qualifiedName).last("limit 1"));
    }

    private String availability(String sourceHash, String acceptedHash) {
        if (sourceHash == null) return "SOURCE_MISSING";
        if (acceptedHash == null) return "UNACCEPTED";
        return sourceHash.equals(acceptedHash) ? "READY" : "CONTRACT_DRIFT";
    }
}

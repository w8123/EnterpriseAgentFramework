package com.enterprise.ai.capability.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.registry.CapabilityApplyRecordEntity;
import com.enterprise.ai.agent.registry.CapabilityApplyRecordMapper;
import com.enterprise.ai.agent.registry.CapabilityDiffItemEntity;
import com.enterprise.ai.agent.registry.CapabilityDiffItemMapper;
import com.enterprise.ai.agent.registry.CapabilitySnapshotEntity;
import com.enterprise.ai.agent.registry.CapabilitySnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Stores review decisions and derives snapshot review status in the caller's transaction. */
@Service
@RequiredArgsConstructor
public class CapabilityReviewEvidenceStore {
    private final CapabilitySnapshotMapper snapshotMapper;
    private final CapabilityDiffItemMapper diffItemMapper;
    private final CapabilityApplyRecordMapper applyRecordMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void refreshSnapshotReviewStatus(CapabilitySnapshotEntity snapshot) {
        List<CapabilityDiffItemEntity> items = diffItemMapper.selectList(
                Wrappers.<CapabilityDiffItemEntity>lambdaQuery()
                        .eq(CapabilityDiffItemEntity::getSnapshotId, snapshot.getId())
                        .eq(CapabilityDiffItemEntity::getProjectId, snapshot.getProjectId())
                        .eq(CapabilityDiffItemEntity::getProjectCode, snapshot.getProjectCode()));
        if (items == null || items.isEmpty()) {
            return;
        }
        long applied = items.stream().filter(item -> Set.of("APPLIED", "AUTO_APPLIED", "UNCHANGED")
                .contains(item.getReviewStatus())).count();
        long ignored = items.stream().filter(item -> "IGNORED".equalsIgnoreCase(item.getReviewStatus())).count();
        long pending = items.stream().filter(item -> "PENDING".equalsIgnoreCase(item.getReviewStatus())).count();
        String status;
        if ("DIAGNOSTIC".equals(snapshot.getIntakeMode())) {
            status = "DIAGNOSTIC";
        } else if (applied == items.size()) {
            status = "APPLIED";
        } else if (ignored == items.size()) {
            status = "IGNORED";
        } else if (pending == 0) {
            status = "RESOLVED";
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

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReviewDecision(Long snapshotId,
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
}

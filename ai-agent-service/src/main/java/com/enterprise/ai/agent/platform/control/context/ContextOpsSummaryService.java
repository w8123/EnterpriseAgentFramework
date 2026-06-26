package com.enterprise.ai.agent.platform.control.context;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateEntity;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateMapper;
import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContextOpsSummaryService {

    private static final int RECENT_AUDIT_HOURS = 24;
    private static final String RUNTIME_USER_COUNT_WARNING =
            "runtimeUserExcludedCount is a lane-level count only; RUNTIME_USER private details are not exposed";
    private static final String RUNTIME_USER_AGGREGATE_WARNING =
            "RUNTIME_USER summary is aggregate-only; private item details are not exposed";

    private final ContextAccessPolicyService accessPolicyService;
    private final ContextNamespaceService namespaceService;
    private final ContextItemMapper itemMapper;
    private final ContextMemoryCandidateMapper candidateMapper;
    private final ContextAuditEventMapper auditEventMapper;

    public ContextOpsSummaryResponse summarize(String tenantId,
                                               String projectCode,
                                               Long projectId,
                                               String memoryLane,
                                               boolean includeRuntimeUser) {
        if (!StringUtils.hasText(tenantId)) {
            throw new IllegalArgumentException("tenantId is required");
        }
        accessPolicyService.assertProjectAccessOnly(projectCode, projectId);

        MemoryLane lane = resolveLane(memoryLane);
        if (lane == MemoryLane.RUNTIME_USER && !includeRuntimeUser) {
            throw new IllegalArgumentException(
                    "includeRuntimeUser=true is required for RUNTIME_USER ops summary");
        }
        List<String> warnings = new ArrayList<>();
        if (lane == MemoryLane.RUNTIME_USER) {
            warnings.add(RUNTIME_USER_AGGREGATE_WARNING);
        }

        List<ContextNamespaceEntity> namespaces = namespaceService.listActiveNamespaceEntities(
                tenantId.trim(), null, projectCode, projectId);
        Map<Long, ContextNamespaceEntity> namespaceById = namespaces.stream()
                .filter(ns -> matchesProjectScope(ns, projectCode, projectId))
                .collect(Collectors.toMap(ContextNamespaceEntity::getId, ns -> ns, (a, b) -> a));

        LocalDateTime now = LocalDateTime.now();
        int itemCount = countItems(namespaceById.keySet(), lane.name(), null, null, null);
        int activeItemCount = countItems(namespaceById.keySet(), lane.name(), ContextStatus.ACTIVE.name(), null, null);
        int staleItemCount = countItems(namespaceById.keySet(), lane.name(), ContextStatus.STALE.name(), null, null);
        int revokedItemCount = countItems(namespaceById.keySet(), lane.name(), ContextStatus.REVOKED.name(), null, null);
        int deletedItemCount = countItems(namespaceById.keySet(), lane.name(), ContextStatus.DELETED.name(), null, null);
        int expiringItemCount = countItems(namespaceById.keySet(), lane.name(), null, now, null);
        int staleDueItemCount = countItems(namespaceById.keySet(), lane.name(), ContextStatus.ACTIVE.name(), null, now);
        int runtimeUserExcludedCount = includeRuntimeUser ? 0
                : countItems(namespaceById.keySet(), MemoryLane.RUNTIME_USER.name(), null, null, null);

        if (!includeRuntimeUser && runtimeUserExcludedCount > 0) {
            warnings.add(RUNTIME_USER_COUNT_WARNING);
        }

        int pendingCandidateCount = countCandidates(tenantId.trim(), projectCode, projectId,
                ContextMemoryCandidateStatus.PENDING.name());
        int expiredCandidateCount = countCandidates(tenantId.trim(), projectCode, projectId,
                ContextMemoryCandidateStatus.EXPIRED.name());

        LocalDateTime auditSince = now.minusHours(RECENT_AUDIT_HOURS);
        int auditEventCountRecent = auditEventMapper.selectCount(
                Wrappers.lambdaQuery(ContextAuditEventEntity.class)
                        .eq(ContextAuditEventEntity::getTenantId, tenantId.trim())
                        .ge(ContextAuditEventEntity::getCreatedAt, auditSince)
                        .eq(StringUtils.hasText(projectCode), ContextAuditEventEntity::getProjectCode, projectCode)
                        .eq(projectId != null, ContextAuditEventEntity::getProjectId, projectId))
                .intValue();

        return ContextOpsSummaryResponse.builder()
                .tenantId(tenantId.trim())
                .projectCode(projectCode)
                .projectId(projectId)
                .memoryLane(lane.name())
                .namespaceCount(namespaceById.size())
                .itemCount(itemCount)
                .activeItemCount(activeItemCount)
                .staleItemCount(staleItemCount)
                .revokedItemCount(revokedItemCount)
                .deletedItemCount(deletedItemCount)
                .expiringItemCount(expiringItemCount)
                .pendingCandidateCount(pendingCandidateCount)
                .expiredCandidateCount(expiredCandidateCount)
                .auditEventCountRecent(auditEventCountRecent)
                .staleDueItemCount(staleDueItemCount)
                .runtimeUserExcludedCount(runtimeUserExcludedCount)
                .warnings(warnings)
                .build();
    }

    private MemoryLane resolveLane(String memoryLane) {
        if (!StringUtils.hasText(memoryLane)) {
            return MemoryLane.PROJECT_DEV;
        }
        return accessPolicyService.requireMemoryLane(memoryLane);
    }

    private boolean matchesProjectScope(ContextNamespaceEntity namespace, String projectCode, Long projectId) {
        if (!ContextProjectIdentity.hasProjectIdentity(projectCode, projectId)) {
            return true;
        }
        return ContextProjectIdentity.matches(
                projectCode, projectId, namespace.getProjectCode(), namespace.getProjectId());
    }

    private int countItems(Collection<Long> namespaceIds,
                           String memoryLane,
                           String status,
                           LocalDateTime expiresAtOrBefore,
                           LocalDateTime staleAfterOrBefore) {
        if (namespaceIds == null || namespaceIds.isEmpty()) {
            return 0;
        }
        Long count = itemMapper.selectCount(Wrappers.lambdaQuery(ContextItemEntity.class)
                .in(ContextItemEntity::getNamespaceId, namespaceIds)
                .eq(ContextItemEntity::getMemoryLane, memoryLane)
                .eq(StringUtils.hasText(status), ContextItemEntity::getStatus, status)
                .le(expiresAtOrBefore != null, ContextItemEntity::getExpiresAt, expiresAtOrBefore)
                .le(staleAfterOrBefore != null, ContextItemEntity::getStaleAfter, staleAfterOrBefore));
        return toInt(count);
    }

    private int countCandidates(String tenantId,
                                String projectCode,
                                Long projectId,
        String status) {
        Long count = candidateMapper.selectCount(
                Wrappers.lambdaQuery(ContextMemoryCandidateEntity.class)
                        .eq(ContextMemoryCandidateEntity::getTenantId, tenantId)
                        .eq(StringUtils.hasText(projectCode),
                                ContextMemoryCandidateEntity::getProjectCode,
                                projectCode)
                        .eq(projectId != null,
                                ContextMemoryCandidateEntity::getProjectId,
                                projectId)
                        .eq(ContextMemoryCandidateEntity::getStatus, status));
        return toInt(count);
    }

    private int toInt(Long count) {
        return count == null ? 0 : Math.toIntExact(count);
    }
}

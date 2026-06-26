package com.enterprise.ai.agent.platform.control.context;

import com.enterprise.ai.agent.platform.control.context.memory.ContextMemoryCandidateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ContextLifecycleService {

    private static final String RUNTIME_USER_STALE_WARNING =
            "RUNTIME_USER item stale is not supported in Phase-6; skipped even when includeRuntimeUserItems=true";

    private final ContextAccessPolicyService accessPolicyService;
    private final ContextMemoryCandidateService candidateService;
    private final ContextItemService itemService;
    private final ContextAuditService auditService;

    @Transactional
    public ContextLifecycleRunResponse run(ContextLifecycleRunRequest request) {
        if (request == null || !StringUtils.hasText(request.getTenantId())) {
            throw new IllegalArgumentException("tenantId is required");
        }
        String tenantId = request.getTenantId().trim();
        accessPolicyService.assertProjectAccessOnly(request.getProjectCode(), request.getProjectId());

        boolean dryRun = Boolean.TRUE.equals(request.getDryRun());
        boolean includeRuntimeUserItems = Boolean.TRUE.equals(request.getIncludeRuntimeUserItems());
        int candidateLimit = normalizeLimit(request.getCandidateExpireLimit());
        int itemLimit = normalizeLimit(request.getItemStaleLimit());

        List<String> warnings = new ArrayList<>();
        if (includeRuntimeUserItems) {
            warnings.add(RUNTIME_USER_STALE_WARNING);
        }

        int expiredCandidateCount = candidateService.markExpiredCandidates(tenantId, dryRun, candidateLimit);

        Map<Long, ContextNamespaceEntity> namespaces = itemService.loadNamespacesByTenant(tenantId);
        List<ContextItemEntity> staleCandidates =
                itemService.listStaleCandidatesForLifecycle(namespaces.keySet(), itemLimit);

        int scannedItemCount = 0;
        int staleItemCount = 0;
        int skippedRuntimeUserItemCount = 0;

        for (ContextItemEntity item : staleCandidates) {
            scannedItemCount++;
            ContextNamespaceEntity namespace = namespaces.get(item.getNamespaceId());
            if (namespace == null) {
                continue;
            }
            if (MemoryLane.RUNTIME_USER.name().equalsIgnoreCase(item.getMemoryLane())) {
                skippedRuntimeUserItemCount++;
                continue;
            }
            if (!MemoryLane.PROJECT_DEV.name().equalsIgnoreCase(item.getMemoryLane())) {
                continue;
            }
            if (ContextProjectIdentity.hasProjectIdentity(request.getProjectCode(), request.getProjectId())
                    && !ContextProjectIdentity.matches(
                    request.getProjectCode(), request.getProjectId(),
                    namespace.getProjectCode(), namespace.getProjectId())) {
                continue;
            }
            if (dryRun) {
                staleItemCount++;
            } else {
                itemService.markStaleByLifecycle(item, namespace, tenantId,
                        request.getProjectCode(), request.getProjectId());
                staleItemCount++;
            }
        }

        if (!dryRun) {
            recordLifecycleRunAudit(tenantId, request.getProjectCode(), request.getProjectId(),
                    expiredCandidateCount, staleItemCount, skippedRuntimeUserItemCount, scannedItemCount);
        }

        return ContextLifecycleRunResponse.builder()
                .tenantId(tenantId)
                .projectCode(request.getProjectCode())
                .projectId(request.getProjectId())
                .dryRun(dryRun)
                .expiredCandidateCount(expiredCandidateCount)
                .staleItemCount(staleItemCount)
                .skippedRuntimeUserItemCount(skippedRuntimeUserItemCount)
                .scannedItemCount(scannedItemCount)
                .warnings(warnings)
                .build();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 500;
        }
        return Math.min(limit, 5000);
    }

    private void recordLifecycleRunAudit(String tenantId,
                                         String projectCode,
                                         Long projectId,
                                         int expiredCandidateCount,
                                         int staleItemCount,
                                         int skippedRuntimeUserItemCount,
                                         int scannedItemCount) {
        ContextQueryRequest scope = new ContextQueryRequest();
        scope.setTenantId(tenantId);
        scope.setProjectCode(projectCode);
        scope.setProjectId(projectId);
        scope.setMemoryLane(MemoryLane.PROJECT_DEV.name());
        scope.setActorType("SYSTEM");
        scope.setActorId("context-lifecycle");
        String reason = String.format(
                "lifecycle run: expiredCandidates=%d staleItems=%d skippedRuntimeUser=%d scanned=%d",
                expiredCandidateCount, staleItemCount, skippedRuntimeUserItemCount, scannedItemCount);
        auditService.record(ContextAuditEventType.LIFECYCLE_RUN, ContextAuditDecision.ALLOW,
                reason, scope, null, null);
    }
}

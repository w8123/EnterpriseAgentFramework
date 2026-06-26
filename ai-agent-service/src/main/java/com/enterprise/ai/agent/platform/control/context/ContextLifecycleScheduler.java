package com.enterprise.ai.agent.platform.control.context;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContextLifecycleScheduler {

    private final ContextLifecycleService lifecycleService;

    @Value("${eaf.context.lifecycle.enabled:false}")
    boolean enabled;

    @Value("${eaf.context.lifecycle.tenant-id:default}")
    String tenantId;

    @Value("${eaf.context.lifecycle.project-code:}")
    String projectCode;

    @Value("${eaf.context.lifecycle.project-id:#{null}}")
    Long projectId;

    @Value("${eaf.context.lifecycle.dry-run:false}")
    boolean dryRun;

    @Value("${eaf.context.lifecycle.include-runtime-user-items:false}")
    boolean includeRuntimeUserItems;

    @Value("${eaf.context.lifecycle.candidate-expire-limit:500}")
    int candidateExpireLimit;

    @Value("${eaf.context.lifecycle.item-stale-limit:500}")
    int itemStaleLimit;

    @Scheduled(
            fixedDelayString = "${eaf.context.lifecycle.fixed-delay-ms:3600000}",
            initialDelayString = "${eaf.context.lifecycle.initial-delay-ms:60000}")
    public void runScheduledLifecycle() {
        if (!enabled) {
            return;
        }
        if (!StringUtils.hasText(tenantId)) {
            log.warn("[ContextLifecycleScheduler] skip: eaf.context.lifecycle.tenant-id is blank");
            return;
        }

        ContextLifecycleRunRequest request = new ContextLifecycleRunRequest();
        request.setTenantId(tenantId.trim());
        request.setProjectCode(StringUtils.hasText(projectCode) ? projectCode.trim() : null);
        request.setProjectId(projectId);
        request.setDryRun(dryRun);
        request.setIncludeRuntimeUserItems(includeRuntimeUserItems);
        request.setCandidateExpireLimit(candidateExpireLimit);
        request.setItemStaleLimit(itemStaleLimit);

        try {
            ContextLifecycleRunResponse response = lifecycleService.run(request);
            log.info("[ContextLifecycleScheduler] completed: tenantId={}, projectCode={}, projectId={}, dryRun={}, "
                            + "expiredCandidates={}, staleItems={}, skippedRuntimeUser={}, scannedItems={}",
                    response.getTenantId(), response.getProjectCode(), response.getProjectId(), response.isDryRun(),
                    response.getExpiredCandidateCount(), response.getStaleItemCount(),
                    response.getSkippedRuntimeUserItemCount(), response.getScannedItemCount());
        } catch (Exception ex) {
            log.warn("[ContextLifecycleScheduler] failed: tenantId={}, projectCode={}, projectId={}, dryRun={}, reason={}",
                    request.getTenantId(), request.getProjectCode(), request.getProjectId(), request.getDryRun(),
                    ex.getMessage(), ex);
        }
    }
}

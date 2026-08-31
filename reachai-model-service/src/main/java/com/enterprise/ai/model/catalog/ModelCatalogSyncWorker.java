package com.enterprise.ai.model.catalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class ModelCatalogSyncWorker {

    private final ModelCatalogSyncRunMapper runMapper;
    private final ModelCatalogSourceMapper sourceMapper;
    private final ModelCatalogSyncService syncService;
    private final ModelCatalogSyncProperties properties;
    private final ModelCatalogSettingsService settingsService;

    private final String workerId = "model-catalog-" + ProcessHandle.current().pid() + "-"
            + UUID.randomUUID().toString().substring(0, 8);
    private final AtomicBoolean firstAutomaticPoll = new AtomicBoolean(true);

    @Scheduled(
            initialDelayString = "${model.catalog-sync.initial-delay-ms:600000}",
            fixedDelayString = "${model.catalog-sync.poll-delay-ms:600000}")
    public void synchronizeDueSources() {
        try {
            if (settingsService.autoSyncEnabled()) {
                prepareAutomaticDailySlots();
            }
        } catch (Exception ex) {
            log.error("Model catalog automatic schedule preparation failed: {}", safeMessage(ex));
        }
        processQueuedRunsNow();
    }

    public void processQueuedRunsNow() {
        int processed = 0;
        try {
            runMapper.markExpiredExhausted();
            int batchSize = Math.max(1, Math.min(20, properties.getBatchSize()));
            for (; processed < batchSize; processed++) {
                ClaimedRun claimed = claimNext();
                if (claimed == null) break;
                process(claimed);
            }
        } catch (Exception ex) {
            log.error("Model catalog queued-run processing failed after {} source(s): {}", processed, safeMessage(ex));
        }
    }

    private void prepareAutomaticDailySlots() {
        LocalDate businessDate = LocalDate.now(properties.resolvedZoneId());
        boolean startupPoll = firstAutomaticPoll.getAndSet(false);
        runMapper.createDailySlots(
                businessDate,
                startupPoll ? "STARTUP" : "DAILY",
                Math.max(1, properties.getMaxAttempts()));
        if (startupPoll) {
            runMapper.requeueDeadSlotsOnStartup(
                    businessDate, Math.max(1, properties.getMaxAttempts()));
        }
    }

    ClaimedRun claimNext() {
        for (int collision = 0; collision < 20; collision++) {
            Long runId = runMapper.findLeaseCandidateId();
            if (runId == null) return null;
            String token = UUID.randomUUID().toString();
            LocalDateTime leasedUntil = LocalDateTime.now()
                    .plusSeconds(Math.max(60, properties.getLeaseSeconds()));
            if (runMapper.claim(runId, workerId, token, leasedUntil) == 1) {
                ModelCatalogSyncRunEntity run = runMapper.selectById(runId);
                if (run == null || !token.equals(run.getLeaseToken())) {
                    throw new IllegalStateException("Claimed model catalog run cannot be reloaded: " + runId);
                }
                return new ClaimedRun(run, token);
            }
        }
        return null;
    }

    private void process(ClaimedRun claimed) {
        try {
            syncService.process(claimed.run(), claimed.leaseToken());
        } catch (Exception ex) {
            handleFailure(claimed, ex);
        }
    }

    private void handleFailure(ClaimedRun claimed, Exception error) {
        ModelCatalogSyncRunEntity current = runMapper.selectById(claimed.run().getId());
        if (current == null || !claimed.leaseToken().equals(current.getLeaseToken())) {
            log.warn("Discarding model catalog failure after lease loss for run {}", claimed.run().getId());
            return;
        }
        ModelCatalogSyncException catalogError = error instanceof ModelCatalogSyncException syncError
                ? syncError
                : new ModelCatalogSyncException(
                "MODEL_CATALOG_SYNC_FAILED", safeMessage(error), true, error);
        int attempts = current.getAttemptCount() == null ? 1 : current.getAttemptCount();
        int maxAttempts = current.getMaxAttempts() == null ? 1 : current.getMaxAttempts();
        boolean retry = catalogError.retriable() && attempts < maxAttempts;
        String nextStatus = retry ? "RETRY" : "DEAD";
        LocalDateTime availableAt = retry
                ? LocalDateTime.now().plusMinutes(Math.max(1, properties.getRetryDelayMinutes()))
                : LocalDateTime.now();
        String message = bounded(safeMessage(catalogError), 2_000);
        runMapper.release(current.getId(), claimed.leaseToken(), nextStatus, availableAt,
                catalogError.code(), message);
        sourceMapper.markFailure(current.getSourceId(), LocalDateTime.now(), catalogError.code(), message);
        log.warn("Model catalog source sync failed runId={} sourceId={} nextStatus={} code={} message={}",
                current.getId(), current.getSourceId(), nextStatus, catalogError.code(), message);
    }

    private String safeMessage(Throwable error) {
        String value = error == null ? null : error.getMessage();
        if (!StringUtils.hasText(value)) value = error == null ? "Unknown error" : error.getClass().getSimpleName();
        return value.replaceAll("(?i)(api[_-]?key|authorization|bearer)\\s*[:=]?\\s*[^\\s,;]+", "$1=[REDACTED]");
    }

    private String bounded(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    record ClaimedRun(ModelCatalogSyncRunEntity run, String leaseToken) {
    }
}

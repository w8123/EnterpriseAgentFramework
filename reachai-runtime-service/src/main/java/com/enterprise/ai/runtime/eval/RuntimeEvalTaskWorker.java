package com.enterprise.ai.runtime.eval;

import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * MySQL 5.7-compatible leased worker. It is opt-in until the EvalOps migration is applied.
 * Expired leases are reclaimable and all executable side effects remain blocked by the typed Eval context.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "reachai.runtime.eval.worker-enabled", havingValue = "true")
public class RuntimeEvalTaskWorker {

    private final RuntimeEvalTaskMapper taskMapper;
    private final RuntimeEvalExperimentMapper experimentMapper;
    private final RuntimeEvalExperimentVariantMapper variantMapper;
    private final RuntimeEvalExperimentItemMapper itemMapper;
    private final RuntimeEvalEvaluatorSuiteVersionMapper suiteMapper;
    private final RuntimeEvalDatasetService datasetService;
    private final RuntimeEvalTargetSnapshotService targetSnapshotService;
    private final RuntimeAgentExecutionService executionService;
    private final RuntimeEvalDeterministicJudge judge;
    private final RuntimeEvalResultPersistenceService persistence;
    private final RuntimeEvalExperimentService experimentService;
    private final RuntimeEvalJsonSupport json;

    private final String workerId = "eval-" + ProcessHandle.current().pid() + "-"
            + UUID.randomUUID().toString().substring(0, 8);

    @Value("${reachai.runtime.eval.worker-batch-size:2}")
    private int batchSize = 2;

    @Value("${reachai.runtime.eval.worker-lease-seconds:600}")
    private int leaseSeconds = 600;

    @Scheduled(
            initialDelayString = "${reachai.runtime.eval.worker-initial-delay-ms:5000}",
            fixedDelayString = "${reachai.runtime.eval.worker-poll-delay-ms:1000}")
    public void processAvailableTasks() {
        int processed = 0;
        try {
            for (; processed < Math.max(1, Math.min(20, batchSize)); processed++) {
                ClaimedTask claimed = claimNext();
                if (claimed == null) break;
                process(claimed);
            }
            experimentService.refreshOpenExperiments(20);
        } catch (Exception ex) {
            log.error("Eval worker poll failed after {} task(s): {}", processed, safeMessage(ex));
        }
    }

    ClaimedTask claimNext() {
        for (int collision = 0; collision < 20; collision++) {
            Long taskId = taskMapper.findLeaseCandidateId();
            if (taskId == null) return null;
            String token = UUID.randomUUID().toString();
            LocalDateTime leasedUntil = LocalDateTime.now().plusSeconds(Math.max(30, leaseSeconds));
            if (taskMapper.claim(taskId, workerId, token, leasedUntil) == 1) {
                return reloadClaimed(taskId, token, false);
            }
            if (taskMapper.claimExpiredExhausted(taskId, workerId, token, leasedUntil) == 1) {
                return reloadClaimed(taskId, token, true);
            }
        }
        return null;
    }

    void process(ClaimedTask claimed) {
        RuntimeEvalTaskEntity task = claimed.task();
        RuntimeEvalExperimentItemEntity item = null;
        try {
            if (!"EXECUTE_ITEM".equals(task.getTaskType())) {
                throw new IllegalStateException("Unsupported Eval task type: " + task.getTaskType());
            }
            RuntimeEvalExperimentEntity experiment = required(experimentMapper.selectById(task.getExperimentId()),
                    "Eval experiment missing for task " + task.getId());
            item = required(itemMapper.selectById(task.getExperimentItemId()),
                    "Eval experiment item missing for task " + task.getId());
            RuntimeEvalExperimentVariantEntity variant = required(variantMapper.selectById(item.getVariantId()),
                    "Eval variant missing for item " + item.getId());
            verifyRelations(task, experiment, item, variant);

            if ("COMPLETED".equals(item.getStatus())) {
                if (taskMapper.complete(task.getId(), claimed.token()) == 1) {
                    experimentService.refreshAndFinalize(experiment.getId());
                }
                return;
            }
            if ("CANCEL_REQUESTED".equals(experiment.getStatus()) || "CANCELLED".equals(experiment.getStatus())) {
                renewOrThrow(claimed, 120);
                persistence.cancel(task, claimed.token(), item);
                experimentService.refreshAndFinalize(experiment.getId());
                return;
            }
            if (claimed.exhaustedLeaseRecovery()) {
                renewOrThrow(claimed, 120);
                persistence.releaseFailure(
                        task,
                        claimed.token(),
                        item,
                        "EVAL_TASK_LEASE_EXPIRED",
                        "Eval task lease expired after the final allowed attempt",
                        LocalDateTime.now());
                experimentService.refreshAndFinalize(experiment.getId());
                return;
            }

            RuntimeEvalDatasetItemEntity datasetItem = datasetService.requiredVerifiedItem(
                    experiment.getDatasetVersionId(), item.getDatasetItemId());
            RuntimeEvalEvaluatorSuiteVersionEntity suite = required(
                    suiteMapper.selectById(experiment.getEvaluatorSuiteVersionId()),
                    "Eval evaluator suite missing for experiment " + experiment.getId());
            if (!json.sha256(suite.getConfigJson()).equalsIgnoreCase(suite.getFingerprintSha256())) {
                throw new IllegalStateException("Eval evaluator suite fingerprint verification failed: " + suite.getId());
            }
            RuntimeEvalTargetSnapshotService.CapturedTarget target =
                    targetSnapshotService.loadAgentSnapshot(variant.getTargetSnapshotId());
            if (!Objects.equals(target.snapshot().getId(), variant.getTargetSnapshotId())
                    || !Objects.equals(target.snapshot().getFingerprintSha256(), variant.getTargetFingerprint())) {
                throw new IllegalStateException("Eval variant target snapshot identity verification failed: " + variant.getId());
            }

            experimentService.markRunning(experiment.getId());
            persistence.markRunning(item, variant);
            Map<String, Object> input = new LinkedHashMap<>(json.readMap(datasetItem.getInputJson()));
            input.put("agentId", experiment.getTargetId());
            input.put("tenantId", experiment.getTenantId());
            if (StringUtils.hasText(experiment.getProjectCode())) input.put("projectCode", experiment.getProjectCode());
            input.put("message", firstText(datasetItem.getMessage(), text(input.get("message")), ""));
            input.put("sessionId", "eval-exp-" + experiment.getId() + "-variant-" + variant.getId()
                    + "-item-" + datasetItem.getId() + "-repeat-" + item.getRepeatNo());
            input.put("userId", "eval-worker");
            input.put("entryType", "EVAL");
            input.put("intentHint", "EVAL_EXPERIMENT");
            input.put("evalExperimentId", experiment.getId());
            input.put("evalExperimentItemId", item.getId());
            input.put("evalDatasetItemId", datasetItem.getId());
            input.put("evalVariantId", variant.getId());

            RuntimeEvalExecutionContext evalContext = RuntimeEvalExecutionContext.readOnly(
                    "experiment:" + experiment.getId(),
                    "item:" + item.getId(),
                    target.snapshot().getFingerprintSha256());
            long startedNanos = System.nanoTime();
            Map<String, Object> response = executionService.executeEvaluation(
                    target.executionContext(), input, true, evalContext);
            int elapsedMs = elapsedMillis(startedNanos);
            Map<String, Object> expected = json.readMap(datasetItem.getExpectedJson());
            Map<String, Object> suiteConfig = json.readMap(suite.getConfigJson());
            RuntimeEvalDeterministicJudge.Judgement judgement =
                    judge.evaluate(response, expected, suiteConfig, elapsedMs);
            Map<String, Object> metadata = json.map(response.get("metadata"));
            Map<String, Object> evidence = new LinkedHashMap<>(metadata);
            evidence.put("targetFingerprint", target.snapshot().getFingerprintSha256());
            evidence.put("datasetItemFingerprint", datasetItem.getContentSha256());
            evidence.put("evaluatorSuiteFingerprint", suite.getFingerprintSha256());
            evidence.put("variantKey", variant.getVariantKey());
            boolean runtimeSuccess = Boolean.TRUE.equals(response.get("success"));
            RuntimeEvalResultPersistenceService.ExecutionOutcome outcome =
                    new RuntimeEvalResultPersistenceService.ExecutionOutcome(
                            runtimeSuccess,
                            text(response.get("answer")),
                            text(metadata.get("traceId")),
                            elapsedMs,
                            new LinkedHashMap<>(evidence),
                            text(metadata.get("code")),
                            runtimeSuccess ? null : firstText(text(response.get("answer")), "Agent runtime execution failed"),
                            judgement);
            renewOrThrow(claimed, 120);
            persistence.complete(task, claimed.token(), item, outcome);
            experimentService.refreshAndFinalize(experiment.getId());
        } catch (RuntimeEvalResultPersistenceService.StaleEvalTaskLeaseException stale) {
            log.warn("Discarding stale Eval task result: {}", stale.getMessage());
        } catch (Exception ex) {
            handleFailure(claimed, item, ex);
        }
    }

    private void handleFailure(ClaimedTask claimed,
                               RuntimeEvalExperimentItemEntity item,
                               Exception ex) {
        RuntimeEvalTaskEntity task = taskMapper.selectById(claimed.task().getId());
        if (task == null || !claimed.token().equals(task.getLeaseToken())) {
            log.warn("Eval task {} failed after its lease was lost", claimed.task().getId());
            return;
        }
        if (item == null) item = itemMapper.selectById(task.getExperimentItemId());
        if (item == null) {
            log.error("Eval task {} cannot persist failure because its item is missing: {}",
                    task.getId(), safeMessage(ex));
            taskMapper.release(task.getId(), claimed.token(), "DEAD", LocalDateTime.now(),
                    "EVAL_TASK_ARTIFACT_MISSING", safeMessage(ex));
            experimentService.refreshAndFinalize(task.getExperimentId());
            return;
        }
        try {
            renewOrThrow(new ClaimedTask(task, claimed.token()), 120);
            int attempts = task.getAttemptCount() == null ? 1 : task.getAttemptCount();
            long delaySeconds = Math.min(300, 5L * (1L << Math.min(6, Math.max(0, attempts - 1))));
            String code = ex instanceof IllegalStateException
                    ? "EVAL_ARTIFACT_VERIFICATION_FAILED" : "EVAL_TASK_EXECUTION_FAILED";
            persistence.releaseFailure(task, claimed.token(), item, code, safeMessage(ex),
                    LocalDateTime.now().plusSeconds(delaySeconds));
            experimentService.refreshAndFinalize(task.getExperimentId());
        } catch (RuntimeEvalResultPersistenceService.StaleEvalTaskLeaseException stale) {
            log.warn("Eval task {} failure was discarded after lease loss", task.getId());
        }
    }

    private void renewOrThrow(ClaimedTask claimed, int minimumSeconds) {
        int seconds = Math.max(minimumSeconds, leaseSeconds);
        if (taskMapper.renew(claimed.task().getId(), claimed.token(), LocalDateTime.now().plusSeconds(seconds)) != 1) {
            throw new RuntimeEvalResultPersistenceService.StaleEvalTaskLeaseException(claimed.task().getId());
        }
    }

    private ClaimedTask reloadClaimed(Long taskId,
                                      String token,
                                      boolean exhaustedLeaseRecovery) {
        RuntimeEvalTaskEntity task = taskMapper.selectById(taskId);
        if (task == null || !token.equals(task.getLeaseToken())) {
            throw new IllegalStateException("Claimed Eval task cannot be reloaded: " + taskId);
        }
        return new ClaimedTask(task, token, exhaustedLeaseRecovery);
    }

    private void verifyRelations(RuntimeEvalTaskEntity task,
                                 RuntimeEvalExperimentEntity experiment,
                                 RuntimeEvalExperimentItemEntity item,
                                 RuntimeEvalExperimentVariantEntity variant) {
        if (!Objects.equals(task.getExperimentId(), experiment.getId())
                || !Objects.equals(item.getExperimentId(), experiment.getId())
                || !Objects.equals(variant.getExperimentId(), experiment.getId())) {
            throw new IllegalStateException("Eval task artifact relation verification failed: " + task.getId());
        }
    }

    private <T> T required(T value, String message) {
        if (value == null) throw new IllegalStateException(message);
        return value;
    }

    private int elapsedMillis(long startedNanos) {
        return (int) Math.min(Integer.MAX_VALUE,
                Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L));
    }

    private String safeMessage(Throwable error) {
        String value = firstText(error.getMessage(), error.getClass().getSimpleName(), "Eval task failed");
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String firstText(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
    }

    record ClaimedTask(RuntimeEvalTaskEntity task,
                       String token,
                       boolean exhaustedLeaseRecovery) {

        ClaimedTask(RuntimeEvalTaskEntity task, String token) {
            this(task, token, false);
        }
    }
}

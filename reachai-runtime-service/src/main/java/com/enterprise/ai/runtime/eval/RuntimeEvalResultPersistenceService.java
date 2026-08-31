package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
class RuntimeEvalResultPersistenceService {

    private final RuntimeEvalExperimentItemMapper itemMapper;
    private final RuntimeEvalExperimentVariantMapper variantMapper;
    private final RuntimeEvalScoreMapper scoreMapper;
    private final RuntimeEvalTaskMapper taskMapper;
    private final RuntimeEvalJsonSupport json;

    @Transactional
    void markRunning(RuntimeEvalExperimentItemEntity item,
                     RuntimeEvalExperimentVariantEntity variant) {
        if (!"COMPLETED".equals(item.getStatus())) {
            item.setStatus("RUNNING");
            item.setStartedAt(LocalDateTime.now());
            item.setFinishedAt(null);
            itemMapper.updateById(item);
        }
        if ("QUEUED".equals(variant.getStatus())) {
            variant.setStatus("RUNNING");
            variantMapper.updateById(variant);
        }
    }

    @Transactional
    void complete(RuntimeEvalTaskEntity task,
                  String leaseToken,
                  RuntimeEvalExperimentItemEntity item,
                  ExecutionOutcome outcome) {
        item.setStatus("COMPLETED");
        item.setRuntimeSuccess(outcome.runtimeSuccess());
        item.setAssertionPassed(outcome.judgement().passed());
        item.setScore(outcome.judgement().score());
        item.setElapsedMs(outcome.elapsedMs());
        item.setAnswer(outcome.answer());
        item.setTraceId(outcome.traceId());
        item.setExecutionMetadataJson(json.canonicalJson(outcome.executionMetadata()));
        item.setEvaluatorResultsJson(json.canonicalJson(outcome.judgement().scores().stream()
                .map(this::scoreMap).toList()));
        if (!outcome.runtimeSuccess()) {
            item.setErrorCode(firstText(outcome.errorCode(), "EVAL_RUNTIME_FAILED"));
            item.setErrorMessage(firstText(outcome.errorMessage(), "Agent runtime execution failed"));
        } else if (!outcome.judgement().passed()) {
            item.setErrorCode("EVAL_ASSERTION_FAILED");
            item.setErrorMessage(outcome.judgement().scores().stream()
                    .filter(value -> !value.passed()).map(RuntimeEvalDeterministicJudge.ScoreResult::reason)
                    .reduce((left, right) -> left + "; " + right).orElse("Eval assertion failed"));
        } else {
            item.setErrorCode(null);
            item.setErrorMessage(null);
        }
        item.setFinishedAt(LocalDateTime.now());
        itemMapper.updateById(item);

        scoreMapper.delete(Wrappers.<RuntimeEvalScoreEntity>lambdaQuery()
                .eq(RuntimeEvalScoreEntity::getExperimentItemId, item.getId()));
        for (RuntimeEvalDeterministicJudge.ScoreResult value : outcome.judgement().scores()) {
            RuntimeEvalScoreEntity score = new RuntimeEvalScoreEntity();
            score.setExperimentId(item.getExperimentId());
            score.setExperimentItemId(item.getId());
            score.setVariantId(item.getVariantId());
            score.setDatasetItemId(item.getDatasetItemId());
            score.setEvaluatorKey(value.key());
            score.setEvaluatorType(value.type());
            score.setScore(value.score());
            score.setPassed(value.passed());
            score.setWeight(value.weight());
            score.setReason(value.reason());
            score.setMetadataJson(json.canonicalJson(value.metadata()));
            score.setCreatedAt(LocalDateTime.now());
            scoreMapper.insert(score);
        }
        if (taskMapper.complete(task.getId(), leaseToken) != 1) {
            throw new StaleEvalTaskLeaseException(task.getId());
        }
    }

    @Transactional
    void releaseFailure(RuntimeEvalTaskEntity task,
                        String leaseToken,
                        RuntimeEvalExperimentItemEntity item,
                        String errorCode,
                        String errorMessage,
                        LocalDateTime retryAt) {
        boolean exhausted = task.getAttemptCount() != null
                && task.getMaxAttempts() != null
                && task.getAttemptCount() >= task.getMaxAttempts();
        String nextStatus = exhausted ? "DEAD" : "RETRY";
        item.setStatus(exhausted ? "FAILED" : "PENDING");
        item.setRuntimeSuccess(false);
        item.setAssertionPassed(false);
        item.setScore(0.0);
        item.setErrorCode(errorCode);
        item.setErrorMessage(errorMessage);
        item.setStartedAt(exhausted ? item.getStartedAt() : null);
        item.setFinishedAt(exhausted ? LocalDateTime.now() : null);
        itemMapper.updateById(item);
        if (taskMapper.release(task.getId(), leaseToken, nextStatus, retryAt, errorCode, errorMessage) != 1) {
            throw new StaleEvalTaskLeaseException(task.getId());
        }
    }

    @Transactional
    void cancel(RuntimeEvalTaskEntity task,
                String leaseToken,
                RuntimeEvalExperimentItemEntity item) {
        item.setStatus("CANCELLED");
        item.setErrorCode("EVAL_EXPERIMENT_CANCELLED");
        item.setErrorMessage("Experiment cancellation was requested");
        item.setFinishedAt(LocalDateTime.now());
        itemMapper.updateById(item);
        if (taskMapper.release(task.getId(), leaseToken, "CANCELLED", LocalDateTime.now(),
                "EVAL_EXPERIMENT_CANCELLED", "Experiment cancellation was requested") != 1) {
            throw new StaleEvalTaskLeaseException(task.getId());
        }
    }

    private Map<String, Object> scoreMap(RuntimeEvalDeterministicJudge.ScoreResult value) {
        return json.ordered(
                "key", value.key(),
                "type", value.type(),
                "score", value.score(),
                "passed", value.passed(),
                "weight", value.weight(),
                "required", value.required(),
                "reason", value.reason(),
                "metadata", value.metadata());
    }

    private String firstText(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }

    record ExecutionOutcome(boolean runtimeSuccess,
                            String answer,
                            String traceId,
                            int elapsedMs,
                            Map<String, Object> executionMetadata,
                            String errorCode,
                            String errorMessage,
                            RuntimeEvalDeterministicJudge.Judgement judgement) {
    }

    static class StaleEvalTaskLeaseException extends IllegalStateException {
        StaleEvalTaskLeaseException(Long taskId) {
            super("Eval task lease is stale: " + taskId);
        }
    }
}

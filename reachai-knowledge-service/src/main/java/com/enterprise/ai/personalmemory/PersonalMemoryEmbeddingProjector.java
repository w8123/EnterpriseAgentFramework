package com.enterprise.ai.personalmemory;

import com.enterprise.ai.embedding.EmbeddingService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Builds the rebuildable semantic projection outside the outbox transaction.
 * Claims are compare-and-set leases so multiple Knowledge replicas can run safely.
 */
@Component
public class PersonalMemoryEmbeddingProjector {

    private static final Logger log = LoggerFactory.getLogger(PersonalMemoryEmbeddingProjector.class);
    private static final String METRIC_PREFIX = "reachai.personal_memory.embedding";

    private final KnowledgePersonalMemoryIndexMapper mapper;
    private final EmbeddingService embeddingService;
    private final PersonalMemoryEmbeddingProperties properties;
    private final MeterRegistry meterRegistry;

    public PersonalMemoryEmbeddingProjector(KnowledgePersonalMemoryIndexMapper mapper,
                                            EmbeddingService embeddingService,
                                            PersonalMemoryEmbeddingProperties properties,
                                            MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.embeddingService = embeddingService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(
            fixedDelayString = "${reachai.personal-memory.embedding.fixed-delay-ms:1000}",
            initialDelayString = "${reachai.personal-memory.embedding.initial-delay-ms:5000}")
    public void projectDue() {
        if (!properties.semanticEnabled()) {
            return;
        }
        try {
            ProjectBatchResult result = runOnce();
            meterRegistry.counter(METRIC_PREFIX + ".runs", "outcome",
                    result.failed() == 0 ? "success" : "partial").increment();
            meterRegistry.summary(METRIC_PREFIX + ".claimed").record(result.claimed());
            meterRegistry.summary(METRIC_PREFIX + ".ready").record(result.ready());
            meterRegistry.summary(METRIC_PREFIX + ".failed").record(result.failed());
            meterRegistry.summary(METRIC_PREFIX + ".stale").record(result.stale());
        } catch (RuntimeException failure) {
            meterRegistry.counter(METRIC_PREFIX + ".runs", "outcome", "error").increment();
            log.warn("Personal memory embedding projection run failed: failureType={}",
                    failure.getClass().getSimpleName());
        }
    }

    ProjectBatchResult runOnce() {
        if (!properties.semanticEnabled()) {
            return new ProjectBatchResult(0, 0, 0, 0, 0, 0);
        }
        LocalDateTime now = LocalDateTime.now();
        int selectionLimit = Math.min(2000, Math.multiplyExact(properties.getBatchSize(), 4));
        List<KnowledgePersonalMemoryIndexEntity> candidates = mapper.selectEmbeddingCandidates(
                now, properties.getModelInstanceId(), PersonalMemoryEmbeddingCodec.FORMAT, selectionLimit);
        List<ClaimedProjection> claimed = claim(candidates, now);
        if (claimed.isEmpty()) {
            return new ProjectBatchResult(candidates.size(), 0, 0, 0, 0, 0);
        }

        Map<String, List<ClaimedProjection>> tenantBatches = new LinkedHashMap<>();
        for (ClaimedProjection item : claimed) {
            tenantBatches.computeIfAbsent(tenantBatchKey(item.entity()), ignored -> new ArrayList<>())
                    .add(item);
        }
        int ready = 0;
        int retry = 0;
        int dead = 0;
        int stale = 0;
        for (List<ClaimedProjection> tenantBatch : tenantBatches.values()) {
            ProjectionOutcome outcome = projectTenantBatch(tenantBatch);
            ready += outcome.ready();
            retry += outcome.retry();
            dead += outcome.dead();
            stale += outcome.stale();
        }
        return new ProjectBatchResult(candidates.size(), claimed.size(), ready, retry, dead, stale);
    }

    private ProjectionOutcome projectTenantBatch(List<ClaimedProjection> claimed) {
        List<List<Float>> embeddings;
        long startedAt = System.nanoTime();
        try {
            embeddings = embeddingService.embedBatch(properties.getModelInstanceId(),
                    claimed.stream().map(ClaimedProjection::text).toList());
            recordProviderLatency(startedAt, "success");
        } catch (RuntimeException failure) {
            recordProviderLatency(startedAt, "error");
            int[] failures = failAll(claimed, "EMBEDDING_PROVIDER_ERROR", LocalDateTime.now());
            log.warn("Personal memory embedding provider call failed: claimed={}, failureType={}",
                    claimed.size(), failure.getClass().getSimpleName());
            return new ProjectionOutcome(0, failures[0], failures[1], failures[2]);
        }
        if (embeddings == null || embeddings.size() != claimed.size()) {
            int[] failures = failAll(claimed, "INVALID_EMBEDDING_RESPONSE", LocalDateTime.now());
            return new ProjectionOutcome(0, failures[0], failures[1], failures[2]);
        }
        Integer batchDimension = null;
        for (List<Float> values : embeddings) {
            try {
                int dimension = PersonalMemoryEmbeddingCodec.validate(values);
                if (batchDimension == null) {
                    batchDimension = dimension;
                } else if (batchDimension != dimension) {
                    int[] failures = failAll(claimed, "INCONSISTENT_EMBEDDING_DIMENSION", LocalDateTime.now());
                    return new ProjectionOutcome(0, failures[0], failures[1], failures[2]);
                }
            } catch (IllegalArgumentException ignoredInvalidVector) {
                // Invalid rows are handled independently below so valid rows in the
                // same provider response can still make progress.
            }
        }

        int ready = 0;
        int retry = 0;
        int dead = 0;
        int stale = 0;
        for (int index = 0; index < claimed.size(); index++) {
            ClaimedProjection item = claimed.get(index);
            try {
                List<Float> values = embeddings.get(index);
                byte[] encoded = PersonalMemoryEmbeddingCodec.encode(values);
                int updated = mapper.completeEmbedding(
                        item.entity().getId(), item.entity().getSourceVersion(), item.claimToken(),
                        encoded, PersonalMemoryEmbeddingCodec.FORMAT, values.size(),
                        properties.getModelInstanceId(), item.textSha256(), LocalDateTime.now());
                if (updated > 0) {
                    ready++;
                } else {
                    mapper.releaseEmbeddingClaim(item.entity().getId(), item.claimToken());
                    stale++;
                }
            } catch (IllegalArgumentException invalidVector) {
                FailureResult failure = fail(item, "INVALID_EMBEDDING_VECTOR", LocalDateTime.now());
                retry += failure.retry();
                dead += failure.dead();
                stale += failure.stale();
            }
        }
        return new ProjectionOutcome(ready, retry, dead, stale);
    }

    private void recordProviderLatency(long startedAt, String outcome) {
        meterRegistry.timer(METRIC_PREFIX + ".provider_latency", "outcome", outcome)
                .record(Math.max(0L, System.nanoTime() - startedAt), TimeUnit.NANOSECONDS);
    }

    private static String tenantBatchKey(KnowledgePersonalMemoryIndexEntity entity) {
        return String.valueOf(entity.getTenantId());
    }

    private List<ClaimedProjection> claim(List<KnowledgePersonalMemoryIndexEntity> candidates,
                                          LocalDateTime now) {
        List<ClaimedProjection> result = new ArrayList<>();
        if (candidates == null) {
            return result;
        }
        LocalDateTime claimUntil = now.plusSeconds(properties.getClaimSeconds());
        for (KnowledgePersonalMemoryIndexEntity candidate : candidates) {
            if (result.size() >= properties.getBatchSize()) {
                break;
            }
            if (candidate == null || candidate.getId() == null || candidate.getSourceVersion() == null) {
                continue;
            }
            String token = UUID.randomUUID().toString();
            int attempt = nextAttempt(candidate);
            int updated = mapper.claimEmbedding(candidate.getId(), candidate.getSourceVersion(), token,
                    attempt, now, claimUntil, properties.getModelInstanceId(),
                    PersonalMemoryEmbeddingCodec.FORMAT);
            if (updated == 0) {
                continue;
            }
            String text = embeddingText(candidate);
            result.add(new ClaimedProjection(candidate, token, text, sha256(text), attempt));
        }
        return result;
    }

    private static int nextAttempt(KnowledgePersonalMemoryIndexEntity candidate) {
        if ("DISABLED".equals(candidate.getEmbeddingStatus())
                || "READY".equals(candidate.getEmbeddingStatus())
                || "DEAD".equals(candidate.getEmbeddingStatus())) {
            return 1;
        }
        return (candidate.getEmbeddingAttempts() == null ? 0 : candidate.getEmbeddingAttempts()) + 1;
    }

    private int[] failAll(List<ClaimedProjection> claimed, String errorCode, LocalDateTime now) {
        int retry = 0;
        int dead = 0;
        int stale = 0;
        for (ClaimedProjection item : claimed) {
            FailureResult result = fail(item, errorCode, now);
            retry += result.retry();
            dead += result.dead();
            stale += result.stale();
        }
        return new int[]{retry, dead, stale};
    }

    private FailureResult fail(ClaimedProjection item, String errorCode, LocalDateTime now) {
        boolean exhausted = item.attempt() >= properties.getMaxAttempts();
        LocalDateTime nextAttemptAt = exhausted ? null : now.plusSeconds(backoffSeconds(item.attempt()));
        int updated = mapper.failEmbedding(item.entity().getId(), item.entity().getSourceVersion(),
                item.claimToken(), exhausted ? "DEAD" : "RETRY", properties.getModelInstanceId(),
                PersonalMemoryEmbeddingCodec.FORMAT, errorCode, nextAttemptAt, now);
        if (updated > 0) {
            return exhausted ? new FailureResult(0, 1, 0) : new FailureResult(1, 0, 0);
        }
        mapper.releaseEmbeddingClaim(item.entity().getId(), item.claimToken());
        return new FailureResult(0, 0, 1);
    }

    private long backoffSeconds(int attempt) {
        long delay = properties.getInitialBackoffSeconds();
        for (int index = 1; index < attempt && delay < properties.getMaxBackoffSeconds(); index++) {
            delay = Math.min(properties.getMaxBackoffSeconds(), Math.multiplyExact(delay, 2));
        }
        return delay;
    }

    private String embeddingText(KnowledgePersonalMemoryIndexEntity item) {
        StringBuilder value = new StringBuilder();
        append(value, item.getTitle());
        append(value, item.getContent());
        append(value, item.getSummary());
        String text = value.toString();
        if (text.length() > properties.getMaxTextChars()) {
            int end = properties.getMaxTextChars();
            if (Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
            return text.substring(0, end);
        }
        return text;
    }

    private static void append(StringBuilder target, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (!target.isEmpty()) {
            target.append('\n');
        }
        target.append(value.trim());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private record ClaimedProjection(KnowledgePersonalMemoryIndexEntity entity,
                                     String claimToken,
                                     String text,
                                     String textSha256,
                                     int attempt) {
    }

    private record FailureResult(int retry, int dead, int stale) {
    }

    private record ProjectionOutcome(int ready, int retry, int dead, int stale) {
    }

    record ProjectBatchResult(int selected, int claimed, int ready, int retry, int dead, int stale) {
        int failed() {
            return retry + dead;
        }
    }
}

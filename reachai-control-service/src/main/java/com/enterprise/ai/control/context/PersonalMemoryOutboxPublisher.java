package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** At-least-once projection publisher; the Knowledge event ledger provides idempotency. */
@Service
@ConditionalOnProperty(prefix = "reachai.context.personal-memory", name = "outbox-enabled",
        havingValue = "true", matchIfMissing = true)
@Slf4j
public class PersonalMemoryOutboxPublisher {

    private static final int MAX_ATTEMPTS = 12;
    private final ContextMemoryOutboxMapper mapper;
    private final PersonalMemoryKnowledgeIndexClient client;
    private final ObjectMapper objectMapper;

    public PersonalMemoryOutboxPublisher(ContextMemoryOutboxMapper mapper,
                                         PersonalMemoryKnowledgeIndexClient client,
                                         ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${reachai.context.personal-memory.outbox-delay-ms:2000}")
    public void publishDue() {
        claimBatch().forEach(this::publishOne);
    }

    List<ContextMemoryOutboxEntity> claimBatch() {
        LocalDateTime now = LocalDateTime.now();
        List<ContextMemoryOutboxEntity> due = mapper.selectList(
                Wrappers.<ContextMemoryOutboxEntity>lambdaQuery()
                        .and(q -> q.eq(ContextMemoryOutboxEntity::getStatus, "PENDING")
                                .le(ContextMemoryOutboxEntity::getNextAttemptAt, now)
                                .or().eq(ContextMemoryOutboxEntity::getStatus, "PUBLISHING")
                                .le(ContextMemoryOutboxEntity::getLockedAt, now.minusMinutes(5)))
                        .orderByAsc(ContextMemoryOutboxEntity::getId)
                        .last("LIMIT 50"));
        for (ContextMemoryOutboxEntity event : due) {
            var claim = Wrappers.<ContextMemoryOutboxEntity>lambdaUpdate()
                    .eq(ContextMemoryOutboxEntity::getId, event.getId());
            if ("PENDING".equals(event.getStatus())) {
                claim.eq(ContextMemoryOutboxEntity::getStatus, "PENDING")
                        .le(ContextMemoryOutboxEntity::getNextAttemptAt, now);
            } else {
                claim.eq(ContextMemoryOutboxEntity::getStatus, "PUBLISHING")
                        .le(ContextMemoryOutboxEntity::getLockedAt, now.minusMinutes(5));
            }
            // Each compare-and-set update is an atomic claim. A surrounding batch transaction
            // would only hold locks longer and is not required for at-least-once delivery.
            int updated = mapper.update(null, claim
                    .set(ContextMemoryOutboxEntity::getStatus, "PUBLISHING")
                    .set(ContextMemoryOutboxEntity::getLockedAt, now)
                    .set(ContextMemoryOutboxEntity::getUpdatedAt, now));
            if (updated == 0) event.setId(null);
        }
        return due.stream().filter(event -> event.getId() != null).toList();
    }

    private void publishOne(ContextMemoryOutboxEntity event) {
        try {
            client.publish(event);
            markPublished(event);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            markFailed(event, ex);
        } catch (Exception ex) {
            markFailed(event, ex);
        }
    }

    void markPublished(ContextMemoryOutboxEntity event) {
        LocalDateTime now = LocalDateTime.now();
        mapper.update(null, Wrappers.<ContextMemoryOutboxEntity>lambdaUpdate()
                .eq(ContextMemoryOutboxEntity::getId, event.getId())
                .eq(ContextMemoryOutboxEntity::getStatus, "PUBLISHING")
                .set(ContextMemoryOutboxEntity::getStatus, "PUBLISHED")
                .set(ContextMemoryOutboxEntity::getPayloadJson, publishedReceipt(objectMapper, event))
                .set(ContextMemoryOutboxEntity::getPublishedAt, now)
                .set(ContextMemoryOutboxEntity::getLockedAt, null)
                .set(ContextMemoryOutboxEntity::getLastError, null)
                .set(ContextMemoryOutboxEntity::getUpdatedAt, now));
    }

    static String publishedReceipt(ObjectMapper objectMapper, ContextMemoryOutboxEntity event) {
        java.util.Map<String, Object> receipt = new java.util.LinkedHashMap<>();
        receipt.put("schema", "reachai-personal-memory-index-receipt-v1");
        receipt.put("sourceVersion", event.getId());
        receipt.put("eventType", event.getEventType());
        receipt.put("published", true);
        try {
            return objectMapper.writeValueAsString(receipt);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("failed to serialize personal-memory outbox receipt", failure);
        }
    }

    void markFailed(ContextMemoryOutboxEntity event, Exception failure) {
        int attempts = (event.getAttemptCount() == null ? 0 : event.getAttemptCount()) + 1;
        LocalDateTime now = LocalDateTime.now();
        long delaySeconds = Math.min(3600L, 1L << Math.min(attempts, 11));
        mapper.update(null, Wrappers.<ContextMemoryOutboxEntity>lambdaUpdate()
                .eq(ContextMemoryOutboxEntity::getId, event.getId())
                .eq(ContextMemoryOutboxEntity::getStatus, "PUBLISHING")
                .set(ContextMemoryOutboxEntity::getStatus, attempts >= MAX_ATTEMPTS ? "DEAD" : "PENDING")
                .set(ContextMemoryOutboxEntity::getAttemptCount, attempts)
                .set(ContextMemoryOutboxEntity::getNextAttemptAt, now.plusSeconds(delaySeconds))
                .set(ContextMemoryOutboxEntity::getLockedAt, null)
                .set(ContextMemoryOutboxEntity::getLastError, safeFailureCode(failure))
                .set(ContextMemoryOutboxEntity::getUpdatedAt, now));
        log.warn("Personal memory index outbox publish failed: eventId={}, attempt={}, failureType={}",
                event.getEventId(), attempts, failure.getClass().getSimpleName());
    }

    static String safeFailureCode(Exception failure) {
        String simpleName = failure == null ? null : failure.getClass().getSimpleName();
        if (simpleName == null || simpleName.isBlank()) {
            simpleName = "UNKNOWN";
        }
        String normalized = simpleName
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toUpperCase(java.util.Locale.ROOT);
        String code = "PUBLISH_" + (normalized.isBlank() ? "UNKNOWN" : normalized);
        return code.length() <= 64 ? code : code.substring(0, 64);
    }
}

package com.enterprise.ai.control.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.ai.control.context.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Transactional outbox writer for the Knowledge-owned, fully rebuildable search projection. */
@Service
public class PersonalMemoryIndexOutboxService {

    public static final String UPSERT = "PERSONAL_MEMORY_UPSERT";
    public static final String DELETE = "PERSONAL_MEMORY_DELETE";

    private final ContextMemoryOutboxMapper mapper;
    private final ObjectMapper objectMapper;

    public PersonalMemoryIndexOutboxService(ContextMemoryOutboxMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void enqueueUpsert(PersonalMemoryPrincipal principal, ContextItemEntity item) {
        Map<String, Object> payload = base(principal, item);
        payload.put("type", item.getItemType());
        payload.put("title", item.getTitle());
        payload.put("content", item.getContent());
        payload.put("summary", item.getSummary());
        payload.put("trustLevel", item.getTrustLevel());
        payload.put("expiresAt", item.getExpiresAt());
        payload.values().removeIf(java.util.Objects::isNull);
        enqueue(item, UPSERT, payload, null);
    }

    public void enqueueDelete(PersonalMemoryPrincipal principal, ContextItemEntity item) {
        enqueueDelete(principal, item, null);
    }

    public long enqueueDelete(PersonalMemoryPrincipal principal,
                              ContextItemEntity item,
                              String correlationId) {
        scrubHistoricalPayloads(item.getId());
        Map<String, Object> payload = base(principal, item);
        return enqueue(item, DELETE, payload, correlationId);
    }

    private void scrubHistoricalPayloads(Long memoryId) {
        if (memoryId == null) return;
        LocalDateTime now = LocalDateTime.now();
        String payload = erasureReceipt(objectMapper);
        // PENDING/PUBLISHING/DEAD copies are no longer deliverable once the canonical item is
        // erased. SUPERSEDED prevents a scrubbed receipt from being retried as an index event.
        // A copy already in memory may still finish on the wire; the new DELETE event receives a
        // larger sourceVersion and remains authoritative in Knowledge.
        mapper.scrubAndSupersedePersonalMemory(String.valueOf(memoryId), payload, now);
    }

    static String erasureReceipt(ObjectMapper objectMapper) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("schema", "reachai-personal-memory-index-erasure-receipt-v1");
        receipt.put("deleted", true);
        try {
            return objectMapper.writeValueAsString(receipt);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("failed to serialize personal-memory erasure receipt", failure);
        }
    }

    private Map<String, Object> base(PersonalMemoryPrincipal principal, ContextItemEntity item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "reachai-personal-memory-index-event-v1");
        payload.put("memoryId", item.getId());
        payload.put("tenantId", principal.tenantId());
        payload.put("runtimeUserId", principal.runtimeUserId());
        return payload;
    }

    private long enqueue(ContextItemEntity item,
                         String eventType,
                         Map<String, Object> payload,
                         String correlationId) {
        LocalDateTime now = LocalDateTime.now();
        ContextMemoryOutboxEntity outbox = new ContextMemoryOutboxEntity();
        outbox.setEventId(UUID.randomUUID().toString());
        outbox.setAggregateType("PERSONAL_MEMORY");
        outbox.setAggregateId(String.valueOf(item.getId()));
        outbox.setEventType(eventType);
        outbox.setCorrelationId(normalizedCorrelationId(correlationId));
        // The DB-generated outbox id is the globally monotonic aggregate version.
        // Publish cannot observe this row before the surrounding transaction commits.
        payload.put("sourceVersion", 0L);
        outbox.setPayloadJson(json(payload));
        outbox.setStatus("PENDING");
        outbox.setAttemptCount(0);
        outbox.setNextAttemptAt(now);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        mapper.insert(outbox);
        payload.put("sourceVersion", outbox.getId());
        outbox.setPayloadJson(json(payload));
        outbox.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(outbox);
        return outbox.getId();
    }

    private static String normalizedCorrelationId(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > 64
                || !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException(
                    "personal memory outbox correlation id is invalid");
        }
        return normalized;
    }

    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize personal memory index event", ex);
        }
    }

}

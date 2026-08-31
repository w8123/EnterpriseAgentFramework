package com.enterprise.ai.runtime.managed;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/** At-least-once publisher; Control's inbox verifies each event id and payload digest. */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "reachai.runtime.managed-executor.enabled",
        havingValue = "true")
public class ManagedExecutionOutboxPublisher {

    private final ManagedExecutionOutboxMapper mapper;
    private final ManagedExecutionControlEventClient client;
    private final ObjectMapper objectMapper;
    private final String publisherId;
    private final int batchSize;
    private final int leaseSeconds;

    public ManagedExecutionOutboxPublisher(
            ManagedExecutionOutboxMapper mapper,
            ManagedExecutionControlEventClient client,
            ObjectMapper objectMapper,
            @Value("${reachai.runtime.managed-executor.outbox-batch-size:50}") int batchSize,
            @Value("${reachai.runtime.managed-executor.outbox-lease-seconds:60}") int leaseSeconds) {
        this(mapper, client, objectMapper,
                "managed-outbox-" + UUID.randomUUID(), batchSize, leaseSeconds);
    }

    ManagedExecutionOutboxPublisher(
            ManagedExecutionOutboxMapper mapper,
            ManagedExecutionControlEventClient client,
            ObjectMapper objectMapper,
            String publisherId,
            int batchSize,
            int leaseSeconds) {
        if (publisherId == null || !publisherId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("Managed Execution publisher id is invalid");
        }
        if (batchSize < 1 || batchSize > 500 || leaseSeconds < 15 || leaseSeconds > 900) {
            throw new IllegalArgumentException("Managed Execution outbox limits are invalid");
        }
        this.mapper = mapper;
        this.client = client;
        this.objectMapper = objectMapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.publisherId = publisherId;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
    }

    @Scheduled(
            initialDelayString = "${reachai.runtime.managed-executor.outbox-initial-delay-ms:5000}",
            fixedDelayString = "${reachai.runtime.managed-executor.outbox-fixed-delay-ms:2000}")
    public void publishDue() {
        for (int index = 0; index < batchSize; index++) {
            Reservation reservation = reserve();
            if (reservation == null) return;
            try {
                client.publish(toRequest(reservation.event()));
                complete(reservation);
            } catch (Exception failure) {
                release(reservation, safeCode(failure));
            }
        }
    }

    @Transactional
    public Reservation reserve() {
        LocalDateTime now = LocalDateTime.now();
        Long id = mapper.findPublishCandidateId(now);
        if (id == null) return null;
        String leaseToken = UUID.randomUUID().toString();
        if (mapper.claimForPublish(id, publisherId, leaseToken, now, now.plusSeconds(leaseSeconds)) != 1) {
            return null;
        }
        ManagedExecutionOutboxEntity event = mapper.selectById(id);
        if (event == null) {
            throw new IllegalStateException("Claimed Managed Execution outbox row disappeared");
        }
        return new Reservation(id, leaseToken, event);
    }

    @Transactional
    public void complete(Reservation reservation) {
        if (mapper.markPublished(reservation.id(), reservation.leaseToken(), LocalDateTime.now()) != 1) {
            log.warn("Managed Execution outbox publish fence was lost for event {}",
                    reservation.event().getEventId());
        }
    }

    @Transactional
    public void release(Reservation reservation, String failureCode) {
        LocalDateTime now = LocalDateTime.now();
        int attempt = reservation.event().getAttemptCount() == null
                ? 1 : Math.max(1, reservation.event().getAttemptCount());
        long delay = Math.min(900L, 5L * (1L << Math.min(7, Math.max(0, attempt - 1))));
        mapper.releaseAfterFailure(
                reservation.id(),
                reservation.leaseToken(),
                failureCode,
                now.plusSeconds(delay),
                now);
    }

    private ManagedExecutionControlEventClient.PublishRequest toRequest(
            ManagedExecutionOutboxEntity event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayloadJson());
            if (payload == null || !payload.isObject()) {
                throw new IllegalStateException("Managed Execution outbox payload must be an object");
            }
            return new ManagedExecutionControlEventClient.PublishRequest(
                    "reachai.managed-execution.control-event.v1",
                    event.getEventId(),
                    event.getExecutionId(),
                    event.getEventType(),
                    payload);
        } catch (Exception invalid) {
            throw new ManagedExecutionControlEventClient.DeliveryException(
                    "MANAGED_OUTBOX_PAYLOAD_INVALID",
                    "Managed Execution outbox payload is invalid", invalid);
        }
    }

    private String safeCode(Throwable failure) {
        if (failure instanceof ManagedExecutionControlEventClient.DeliveryException delivery
                && delivery.code() != null && delivery.code().matches("[A-Z0-9_]{3,96}")) {
            return delivery.code();
        }
        return "MANAGED_CONTROL_DELIVERY_FAILED";
    }

    public record Reservation(
            Long id,
            String leaseToken,
            ManagedExecutionOutboxEntity event) {
    }
}

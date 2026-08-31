package com.enterprise.ai.control.a2a.application.port;

import java.time.LocalDateTime;
import java.util.List;

public interface A2aOutboxRepository {

    List<A2aTaskRepository.OutboxRecord> claimDue(
            String workerId, LocalDateTime now, LocalDateTime leaseUntil, int limit);

    void markDelivered(long id, String workerId, LocalDateTime deliveredAt);

    void markRetry(
            long id, String workerId, int attemptCount, LocalDateTime nextAttemptAt,
            String errorCode, String errorSummary);

    void markDead(
            long id, String workerId, int attemptCount,
            String errorCode, String errorSummary);

    boolean renewLease(long id, String workerId, LocalDateTime leaseUntil);

    List<A2aTaskRepository.OutboxRecord> markExpiredClaimsUnknown(LocalDateTime now, int limit);
}

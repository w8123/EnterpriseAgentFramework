package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.enterprise.ai.control.a2a.application.port.A2aOutboxRepository;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.OutboxRecord;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class MybatisA2aOutboxRepository implements A2aOutboxRepository {

    private final A2aOutboxMapper mapper;

    @Override
    public List<OutboxRecord> claimDue(
            String workerId, LocalDateTime now, LocalDateTime leaseUntil, int limit) {
        int requested = Math.max(1, Math.min(limit, 100));
        List<OutboxRecord> claimed = new ArrayList<>();
        for (Long id : mapper.findDueIds(now, Math.min(500, requested * 4))) {
            if (claimed.size() >= requested) {
                break;
            }
            if (id != null && mapper.claim(id, workerId, now, leaseUntil) == 1) {
                A2aOutboxEntity entity = mapper.selectById(id);
                if (entity != null) {
                    claimed.add(toRecord(entity));
                }
            }
        }
        return List.copyOf(claimed);
    }

    @Override
    public void markDelivered(long id, String workerId, LocalDateTime deliveredAt) {
        requireOne(mapper.markDelivered(id, workerId, deliveredAt));
    }

    @Override
    public void markRetry(
            long id, String workerId, int attemptCount, LocalDateTime nextAttemptAt,
            String errorCode, String errorSummary) {
        requireOne(mapper.markRetry(id, workerId, attemptCount, nextAttemptAt,
                safe(errorCode, 96), safe(errorSummary, 1000)));
    }

    @Override
    public void markDead(
            long id, String workerId, int attemptCount, String errorCode, String errorSummary) {
        requireOne(mapper.markDead(id, workerId, attemptCount,
                safe(errorCode, 96), safe(errorSummary, 1000)));
    }

    @Override
    public boolean renewLease(long id, String workerId, LocalDateTime leaseUntil) {
        return mapper.renewLease(id, workerId, leaseUntil) == 1;
    }

    @Override
    public List<OutboxRecord> markExpiredClaimsUnknown(LocalDateTime now, int limit) {
        int requested = Math.max(1, Math.min(limit, 500));
        List<OutboxRecord> expired = new ArrayList<>();
        for (Long id : mapper.findExpiredClaimIds(now, requested)) {
            if (id == null) {
                continue;
            }
            A2aOutboxEntity before = mapper.selectById(id);
            if (before != null && mapper.markExpiredClaimUnknown(id, now) == 1) {
                A2aOutboxEntity after = mapper.selectById(id);
                expired.add(toRecord(after == null ? before : after));
            }
        }
        return List.copyOf(expired);
    }

    private OutboxRecord toRecord(A2aOutboxEntity entity) {
        return new OutboxRecord(entity.getId(), entity.getEventId(), entity.getAggregateType(),
                entity.getAggregateId(), entity.getEventType(), entity.getResourceRefJson(),
                entity.getStatus(), entity.getAttemptCount() == null ? 0 : entity.getAttemptCount(),
                entity.getNextAttemptAt(), entity.getLeaseOwner(), entity.getLeaseUntil(),
                entity.getLastErrorCode(), entity.getLastErrorSummary(), entity.getCreatedAt(),
                entity.getDeliveredAt(), entity.getUpdatedAt());
    }

    private void requireOne(int affected) {
        if (affected != 1) {
            throw new A2aDomainException("A2A_OUTBOX_LEASE_LOST",
                    "A2A outbox lease was lost before acknowledgement");
        }
    }

    private String safe(String value, int max) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}

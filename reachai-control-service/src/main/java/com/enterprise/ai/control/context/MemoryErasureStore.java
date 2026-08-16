package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Transactional Control-owned persistence boundary for cross-domain erasure requests. */
@Service
public class MemoryErasureStore {

    private final MemoryErasureRequestMapper requestMapper;
    private final MemoryErasureDomainMapper domainMapper;

    public MemoryErasureStore(MemoryErasureRequestMapper requestMapper,
                              MemoryErasureDomainMapper domainMapper) {
        this.requestMapper = requestMapper;
        this.domainMapper = domainMapper;
    }

    @Transactional
    public CreateResult createOrGet(MemoryErasureRequestEntity request,
                                    List<DomainSpec> domains) {
        MemoryErasureRequestEntity existing = findByClientRequest(
                request.getTenantId(), request.getClientRequestId());
        if (existing != null) {
            return new CreateResult(existing, false);
        }
        try {
            requestMapper.insert(request);
        } catch (DuplicateKeyException concurrentCreate) {
            existing = findByClientRequest(request.getTenantId(), request.getClientRequestId());
            if (existing == null) {
                throw concurrentCreate;
            }
            return new CreateResult(existing, false);
        }
        for (DomainSpec spec : domains) {
            MemoryErasureDomainEntity domain = new MemoryErasureDomainEntity();
            domain.setErasureRequestId(request.getId());
            domain.setDomainCode(spec.domainCode());
            domain.setOwnerService(spec.ownerService());
            domain.setExecutionMode(spec.executionMode());
            domain.setStatus(spec.initialStatus());
            domain.setAttemptCount(0);
            domain.setCreatedAt(request.getCreatedAt());
            domain.setUpdatedAt(request.getUpdatedAt());
            domainMapper.insert(domain);
        }
        return new CreateResult(request, true);
    }

    public MemoryErasureRequestEntity findByClientRequest(
            String tenantId, String clientRequestId) {
        return requestMapper.selectOne(new LambdaQueryWrapper<MemoryErasureRequestEntity>()
                .eq(MemoryErasureRequestEntity::getTenantId, tenantId)
                .eq(MemoryErasureRequestEntity::getClientRequestId, clientRequestId)
                .last("LIMIT 1"));
    }

    public MemoryErasureRequestEntity findByRequestId(String requestId) {
        return requestMapper.selectOne(new LambdaQueryWrapper<MemoryErasureRequestEntity>()
                .eq(MemoryErasureRequestEntity::getRequestId, requestId)
                .last("LIMIT 1"));
    }

    public List<MemoryErasureDomainEntity> domains(Long requestId) {
        return domainMapper.selectList(new LambdaQueryWrapper<MemoryErasureDomainEntity>()
                .eq(MemoryErasureDomainEntity::getErasureRequestId, requestId)
                .orderByAsc(MemoryErasureDomainEntity::getId));
    }

    public List<Long> dueRequestIds(LocalDateTime now, int limit) {
        return requestMapper.selectList(new LambdaQueryWrapper<MemoryErasureRequestEntity>()
                        .in(MemoryErasureRequestEntity::getStatus,
                                "REQUESTED", "RETRY", "RUNNING")
                        .and(q -> q.isNull(MemoryErasureRequestEntity::getNextAttemptAt)
                                .or().le(MemoryErasureRequestEntity::getNextAttemptAt, now))
                        .and(q -> q.isNull(MemoryErasureRequestEntity::getLeaseExpiresAt)
                                .or().le(MemoryErasureRequestEntity::getLeaseExpiresAt, now))
                        .orderByAsc(MemoryErasureRequestEntity::getNextAttemptAt)
                        .orderByAsc(MemoryErasureRequestEntity::getId)
                        .last("LIMIT " + limit))
                .stream()
                .map(MemoryErasureRequestEntity::getId)
                .toList();
    }

    @Transactional
    public MemoryErasureRequestEntity claim(Long id,
                                            String leaseOwner,
                                            LocalDateTime now,
                                            int leaseSeconds) {
        int updated = requestMapper.update(null,
                new LambdaUpdateWrapper<MemoryErasureRequestEntity>()
                        .eq(MemoryErasureRequestEntity::getId, id)
                        .in(MemoryErasureRequestEntity::getStatus,
                                "REQUESTED", "RETRY", "RUNNING")
                        .and(q -> q.isNull(MemoryErasureRequestEntity::getNextAttemptAt)
                                .or().le(MemoryErasureRequestEntity::getNextAttemptAt, now))
                        .and(q -> q.isNull(MemoryErasureRequestEntity::getLeaseExpiresAt)
                                .or().le(MemoryErasureRequestEntity::getLeaseExpiresAt, now))
                        .set(MemoryErasureRequestEntity::getStatus, "RUNNING")
                        .set(MemoryErasureRequestEntity::getLeaseOwner, leaseOwner)
                        .set(MemoryErasureRequestEntity::getLeaseExpiresAt,
                                now.plusSeconds(leaseSeconds))
                        .setSql("attempt_count = attempt_count + 1")
                        .set(MemoryErasureRequestEntity::getUpdatedAt, now));
        return updated == 1 ? requestMapper.selectById(id) : null;
    }

    @Transactional
    public void updateDomain(Long requestId,
                             String domainCode,
                             String status,
                             String resultCode,
                             Long affectedCount,
                             String failureCode,
                             String evidenceReference,
                             LocalDateTime now) {
        LambdaUpdateWrapper<MemoryErasureDomainEntity> update =
                new LambdaUpdateWrapper<MemoryErasureDomainEntity>()
                        .eq(MemoryErasureDomainEntity::getErasureRequestId, requestId)
                        .eq(MemoryErasureDomainEntity::getDomainCode, domainCode)
                        .set(MemoryErasureDomainEntity::getStatus, status)
                        .set(MemoryErasureDomainEntity::getResultCode, resultCode)
                        .set(MemoryErasureDomainEntity::getAffectedCount, affectedCount)
                        .set(MemoryErasureDomainEntity::getLastFailureCode, failureCode)
                        .set(MemoryErasureDomainEntity::getEvidenceReference,
                                evidenceReference)
                        .setSql("attempt_count = attempt_count + 1")
                        .set(MemoryErasureDomainEntity::getUpdatedAt, now);
        update.set(MemoryErasureDomainEntity::getCompletedAt,
                "COMPLETED".equals(status) ? now : null);
        if (domainMapper.update(null, update) != 1) {
            throw new IllegalStateException("memory erasure domain was not found");
        }
    }

    @Transactional
    public void finishAttempt(Long requestId,
                              String leaseOwner,
                              String status,
                              LocalDateTime nextAttemptAt,
                              String failureCode,
                              boolean scrubRuntimeUserId,
                              boolean automatedCompleted,
                              boolean completed,
                              LocalDateTime now) {
        LambdaUpdateWrapper<MemoryErasureRequestEntity> update =
                new LambdaUpdateWrapper<MemoryErasureRequestEntity>()
                        .eq(MemoryErasureRequestEntity::getId, requestId)
                        .eq(MemoryErasureRequestEntity::getLeaseOwner, leaseOwner)
                        .set(MemoryErasureRequestEntity::getStatus, status)
                        .set(MemoryErasureRequestEntity::getNextAttemptAt, nextAttemptAt)
                        .set(MemoryErasureRequestEntity::getLastFailureCode, failureCode)
                        .set(MemoryErasureRequestEntity::getLeaseOwner, null)
                        .set(MemoryErasureRequestEntity::getLeaseExpiresAt, null)
                        .set(MemoryErasureRequestEntity::getUpdatedAt, now);
        if (scrubRuntimeUserId) {
            update.set(MemoryErasureRequestEntity::getRuntimeUserId, null);
        }
        if (automatedCompleted) {
            update.set(MemoryErasureRequestEntity::getAutomatedCompletedAt, now);
        }
        if (completed) {
            update.set(MemoryErasureRequestEntity::getCompletedAt, now);
        }
        if (requestMapper.update(null, update) != 1) {
            throw new IllegalStateException("memory erasure request lease was lost");
        }
    }

    @Transactional
    public void attestDomain(Long requestId,
                             String domainCode,
                             String resultCode,
                             String evidenceReference,
                             LocalDateTime now) {
        int updated = domainMapper.update(null,
                new LambdaUpdateWrapper<MemoryErasureDomainEntity>()
                        .eq(MemoryErasureDomainEntity::getErasureRequestId, requestId)
                        .eq(MemoryErasureDomainEntity::getDomainCode, domainCode)
                        .eq(MemoryErasureDomainEntity::getExecutionMode, "MANUAL_EVIDENCE")
                        .eq(MemoryErasureDomainEntity::getStatus, "WAITING_EVIDENCE")
                        .set(MemoryErasureDomainEntity::getStatus, "COMPLETED")
                        .set(MemoryErasureDomainEntity::getResultCode, resultCode)
                        .set(MemoryErasureDomainEntity::getEvidenceReference, evidenceReference)
                        .set(MemoryErasureDomainEntity::getLastFailureCode, null)
                        .set(MemoryErasureDomainEntity::getCompletedAt, now)
                        .set(MemoryErasureDomainEntity::getUpdatedAt, now));
        if (updated != 1) {
            throw new IllegalStateException(
                    "manual memory erasure domain was already completed or was not found");
        }
    }

    @Transactional
    public void updateRequestStatus(Long requestId,
                                    String status,
                                    boolean completed,
                                    LocalDateTime now) {
        LambdaUpdateWrapper<MemoryErasureRequestEntity> update =
                new LambdaUpdateWrapper<MemoryErasureRequestEntity>()
                        .eq(MemoryErasureRequestEntity::getId, requestId)
                        .set(MemoryErasureRequestEntity::getStatus, status)
                        .set(MemoryErasureRequestEntity::getLastFailureCode, null)
                        .set(MemoryErasureRequestEntity::getUpdatedAt, now);
        if (completed) {
            update.set(MemoryErasureRequestEntity::getCompletedAt, now);
        }
        if (requestMapper.update(null, update) != 1) {
            throw new IllegalArgumentException("memory erasure request was not found");
        }
    }

    @Transactional
    public void resetForRetry(Long requestId, LocalDateTime now) {
        domainMapper.update(null, new LambdaUpdateWrapper<MemoryErasureDomainEntity>()
                .eq(MemoryErasureDomainEntity::getErasureRequestId, requestId)
                .eq(MemoryErasureDomainEntity::getExecutionMode, "AUTOMATED")
                .ne(MemoryErasureDomainEntity::getStatus, "COMPLETED")
                .set(MemoryErasureDomainEntity::getStatus, "PENDING")
                .set(MemoryErasureDomainEntity::getLastFailureCode, null)
                .set(MemoryErasureDomainEntity::getUpdatedAt, now));
        int updated = requestMapper.update(null,
                new LambdaUpdateWrapper<MemoryErasureRequestEntity>()
                        .eq(MemoryErasureRequestEntity::getId, requestId)
                        .in(MemoryErasureRequestEntity::getStatus,
                                "BLOCKED_LEGAL_HOLD", "FAILED", "RETRY")
                        .isNotNull(MemoryErasureRequestEntity::getRuntimeUserId)
                        .set(MemoryErasureRequestEntity::getStatus, "RETRY")
                        .set(MemoryErasureRequestEntity::getNextAttemptAt, now)
                        .set(MemoryErasureRequestEntity::getLeaseOwner, null)
                        .set(MemoryErasureRequestEntity::getLeaseExpiresAt, null)
                        .set(MemoryErasureRequestEntity::getLastFailureCode, null)
                        .set(MemoryErasureRequestEntity::getUpdatedAt, now));
        if (updated != 1) {
            throw new IllegalStateException(
                    "memory erasure request is not retryable or its target was already scrubbed");
        }
    }

    public record DomainSpec(String domainCode, String ownerService,
                             String executionMode, String initialStatus) {
    }

    public record CreateResult(MemoryErasureRequestEntity request, boolean created) {
    }
}

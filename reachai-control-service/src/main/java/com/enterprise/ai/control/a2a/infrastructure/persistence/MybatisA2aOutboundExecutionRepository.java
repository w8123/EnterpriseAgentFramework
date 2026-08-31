package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.enterprise.ai.control.a2a.application.port.A2aOutboundExecutionRepository;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MybatisA2aOutboundExecutionRepository implements A2aOutboundExecutionRepository {

    private final A2aOutboundExecutionMapper mapper;

    @Override
    public OutboundExecution save(OutboundExecution execution) {
        if (execution == null) throw invalid("outbound execution is required");
        A2aOutboundExecutionEntity entity = toEntity(execution);
        try {
            if (entity.getId() == null) {
                mapper.insert(entity);
            } else if (mapper.updateById(entity) != 1) {
                throw invalid("outbound execution changed concurrently");
            }
            return toRecord(entity);
        } catch (DuplicateKeyException failure) {
            throw new A2aDomainException("A2A_OUTBOUND_EXECUTION_CONFLICT",
                    "an outbound execution already exists for this Task");
        }
    }

    @Override
    public Optional<OutboundExecution> findByTaskRefId(long taskRefId) {
        return Optional.ofNullable(mapper.findByTaskRefId(taskRefId)).map(this::toRecord);
    }

    @Override
    public Optional<OutboundExecution> lockByTaskRefId(long taskRefId) {
        return Optional.ofNullable(mapper.lockByTaskRefId(taskRefId)).map(this::toRecord);
    }

    @Override
    public List<OutboundExecution> claimDue(
            String workerId, LocalDateTime now, LocalDateTime leaseUntil, int limit) {
        if (workerId == null || workerId.isBlank() || now == null || leaseUntil == null) {
            throw invalid("a complete polling lease is required");
        }
        int requested = Math.max(1, Math.min(limit, 100));
        List<OutboundExecution> claimed = new ArrayList<>();
        for (Long id : mapper.findDueIds(now, Math.min(500, requested * 4))) {
            if (claimed.size() >= requested) break;
            if (id != null && mapper.claim(id, workerId, now, leaseUntil) == 1) {
                A2aOutboundExecutionEntity entity = mapper.selectById(id);
                if (entity != null) claimed.add(toRecord(entity));
            }
        }
        return List.copyOf(claimed);
    }

    @Override
    public boolean claimNow(
            long taskRefId, String workerId, LocalDateTime now, LocalDateTime leaseUntil) {
        if (workerId == null || workerId.isBlank() || now == null || leaseUntil == null) {
            throw invalid("a complete operation lease is required");
        }
        return mapper.claimNow(taskRefId, workerId, now, leaseUntil) == 1;
    }

    @Override
    public void schedule(
            long taskRefId, String workerId, LocalDateTime nextPollAt,
            LocalDateTime lastPolledAt, int pollAttemptCount,
            String errorCode, String errorSummary) {
        requireOne(mapper.schedule(taskRefId, workerId, nextPollAt, lastPolledAt,
                pollAttemptCount, safe(errorCode, 96), safe(errorSummary, 1000)),
                "the outbound polling lease was lost");
    }

    @Override
    public void activate(long taskRefId, LocalDateTime nextPollAt) {
        requireOne(mapper.activate(taskRefId, nextPollAt),
                "the outbound execution could not be activated");
    }

    @Override
    public void pause(long taskRefId, String reasonCode, String reasonSummary) {
        requireOne(mapper.pause(taskRefId, safe(reasonCode, 96), safe(reasonSummary, 1000)),
                "the outbound execution could not be paused");
    }

    @Override
    public void markTerminal(long taskRefId) {
        if (mapper.markTerminal(taskRefId) == 1) return;
        A2aOutboundExecutionEntity current = mapper.findByTaskRefId(taskRefId);
        if (current == null || !"TERMINAL".equals(current.getPollStatus())) {
            throw invalid("the outbound execution could not be finalized");
        }
    }

    private A2aOutboundExecutionEntity toEntity(OutboundExecution value) {
        A2aOutboundExecutionEntity entity = new A2aOutboundExecutionEntity();
        entity.setId(value.id());
        entity.setTaskRefId(value.taskRefId());
        entity.setRuntimeBindingId(value.runtimeBindingId());
        entity.setAgentConfigVersionId(value.agentConfigVersionId());
        entity.setPrincipalTrustProfileId(value.principalTrustProfileId());
        entity.setRemoteTrustProfileId(value.remoteTrustProfileId());
        entity.setRemoteInterfaceKey(value.remoteInterfaceKey());
        entity.setRemoteSecuritySchemeKey(value.remoteSecuritySchemeKey());
        entity.setCredentialId(value.credentialId());
        entity.setProtocolSkillId(value.protocolSkillId());
        entity.setContentClassification(value.contentClassification());
        entity.setAcceptedOutputModesJson(value.acceptedOutputModesJson());
        entity.setHistoryLength(value.historyLength());
        entity.setMaxRequestBytes(value.maxRequestBytes());
        entity.setMaxResponseBytes(value.maxResponseBytes());
        entity.setMaxArtifactBytes(value.maxArtifactBytes());
        entity.setTimeoutMs(value.timeoutMs());
        entity.setPollStatus(value.pollStatus());
        entity.setPollAttemptCount(value.pollAttemptCount());
        entity.setNextPollAt(value.nextPollAt());
        entity.setLastPolledAt(value.lastPolledAt());
        entity.setLastPollErrorCode(value.lastPollErrorCode());
        entity.setLastPollErrorSummary(value.lastPollErrorSummary());
        entity.setLeaseOwner(value.leaseOwner());
        entity.setLeaseUntil(value.leaseUntil());
        entity.setCreatedAt(value.createdAt());
        entity.setUpdatedAt(value.updatedAt());
        return entity;
    }

    private OutboundExecution toRecord(A2aOutboundExecutionEntity value) {
        return new OutboundExecution(
                value.getId(), value.getTaskRefId(), value.getRuntimeBindingId(),
                value.getAgentConfigVersionId(), value.getPrincipalTrustProfileId(),
                value.getRemoteTrustProfileId(), value.getRemoteInterfaceKey(),
                value.getRemoteSecuritySchemeKey(), value.getCredentialId(),
                value.getProtocolSkillId(), value.getContentClassification(),
                value.getAcceptedOutputModesJson(), value.getHistoryLength(),
                value.getMaxRequestBytes(), value.getMaxResponseBytes(),
                value.getMaxArtifactBytes(), value.getTimeoutMs(), value.getPollStatus(),
                value.getPollAttemptCount(), value.getNextPollAt(), value.getLastPolledAt(),
                value.getLastPollErrorCode(), value.getLastPollErrorSummary(),
                value.getLeaseOwner(), value.getLeaseUntil(), value.getCreatedAt(),
                value.getUpdatedAt());
    }

    private void requireOne(int affected, String detail) {
        if (affected != 1) throw invalid(detail);
    }

    private A2aDomainException invalid(String detail) {
        return new A2aDomainException("A2A_OUTBOUND_EXECUTION_STATE_CONFLICT", detail);
    }

    private String safe(String value, int max) {
        if (value == null) return null;
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}

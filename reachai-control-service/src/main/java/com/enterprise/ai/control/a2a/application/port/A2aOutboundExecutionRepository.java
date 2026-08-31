package com.enterprise.ai.control.a2a.application.port;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persists the immutable outbound acceptance snapshot and the distributed polling lease.
 * The protocol Task remains in {@link A2aTaskRepository}; this extension exists only for
 * Control-owned outbound execution concerns.
 */
public interface A2aOutboundExecutionRepository {

    OutboundExecution save(OutboundExecution execution);

    Optional<OutboundExecution> findByTaskRefId(long taskRefId);

    Optional<OutboundExecution> lockByTaskRefId(long taskRefId);

    List<OutboundExecution> claimDue(
            String workerId, LocalDateTime now, LocalDateTime leaseUntil, int limit);

    boolean claimNow(long taskRefId, String workerId, LocalDateTime now, LocalDateTime leaseUntil);

    void schedule(
            long taskRefId, String workerId, LocalDateTime nextPollAt,
            LocalDateTime lastPolledAt, int pollAttemptCount,
            String errorCode, String errorSummary);

    void activate(long taskRefId, LocalDateTime nextPollAt);

    void pause(long taskRefId, String reasonCode, String reasonSummary);

    void markTerminal(long taskRefId);

    record OutboundExecution(
            Long id,
            long taskRefId,
            long runtimeBindingId,
            long agentConfigVersionId,
            long principalTrustProfileId,
            long remoteTrustProfileId,
            String remoteInterfaceKey,
            String remoteSecuritySchemeKey,
            Long credentialId,
            String protocolSkillId,
            String contentClassification,
            String acceptedOutputModesJson,
            int historyLength,
            long maxRequestBytes,
            int maxResponseBytes,
            long maxArtifactBytes,
            long timeoutMs,
            String pollStatus,
            int pollAttemptCount,
            LocalDateTime nextPollAt,
            LocalDateTime lastPolledAt,
            String lastPollErrorCode,
            String lastPollErrorSummary,
            String leaseOwner,
            LocalDateTime leaseUntil,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }
}

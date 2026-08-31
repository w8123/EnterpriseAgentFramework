package com.enterprise.ai.runtime.managed;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.runtime.managed.ManagedExecutionPayloadSanitizer.SanitizedEvent;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ArtifactDescriptor;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.CreateRequest;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.CreatedView;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ExecutionView;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerClaimView;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerCommand;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerCommandBatchView;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerCompleteRequest;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerEventBatchRequest;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerEventV1;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerMutationView;
import com.enterprise.ai.runtime.managed.ManagedSandboxProvisioner.ProvisioningRequest;
import com.enterprise.ai.runtime.managed.ManagedSandboxProvisioner.SandboxHandle;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ManagedExecutionService {

    private static final Set<String> SOURCE_TYPES = Set.of("AI_CODING_TASK", "AGENT_DELEGATION", "OPERATOR");
    private static final Set<String> ARTIFACT_TYPES = ManagedArtifactVerifier.REQUIRED_TYPES;
    private static final Set<String> REQUIRED_ARTIFACT_TYPES = ManagedArtifactVerifier.REQUIRED_TYPES;

    private final ManagedExecutionMapper executionMapper;
    private final ManagedExecutionEventMapper eventMapper;
    private final ManagedArtifactMapper artifactMapper;
    private final ManagedExecutionOutboxMapper outboxMapper;
    private final ManagedExecutorProperties properties;
    private final ManagedWorkerTokenService tokenService;
    private final ManagedExecutionPayloadSanitizer payloadSanitizer;
    private final ManagedExecutionApprovalService approvalService;
    private final ManagedExecutionRunProjector runProjector;
    private final ManagedArtifactVerifier artifactVerifier;
    private final ManagedSandboxProvisioner sandboxProvisioner;
    private final ManagedArtifactStore artifactStore;
    private final ObjectMapper objectMapper;

    @Transactional
    public CreatedView create(String tenantId, String userId, CreateRequest request) {
        String trustedTenantId = identifier(tenantId, "tenantId", 96);
        String trustedUserId = identifier(userId, "userId", 128);
        if (request == null) throw invalid("Managed execution request is required");
        String projectCode = identifier(request.projectCode(), "projectCode", 128).toUpperCase(Locale.ROOT);
        String sourceType = enumValue(request.sourceType(), SOURCE_TYPES, "sourceType");
        String sourceRef = optionalIdentifier(request.sourceRef(), "sourceRef", 128);
        if (!"OPERATOR".equals(sourceType) && !StringUtils.hasText(sourceRef)) {
            throw invalid("sourceRef is required for this Managed Execution source");
        }
        String profile = enumValue(request.sandboxProfile(),
                Set.of("ANALYZE_READONLY", "WORKSPACE_PATCH"), "sandboxProfile");
        String objective = requiredContent(request.objective(), "objective", properties.maxObjectiveCharacters());
        String objectiveSha256 = sha256(objective);
        String provider = enumValue(defaultText(request.executorProvider(), "CODEX"),
                Set.of("CODEX"), "executorProvider");
        String modelRef = optionalIdentifier(request.modelRef(), "modelRef", 128);
        String acceptanceProfile = identifier(
                defaultText(request.acceptanceProfile(), "PROJECT_DEFAULT"), "acceptanceProfile", 128);
        int maxWallTime = bounded(request.maxWallTimeSeconds(), 1_800, 60, 14_400, "maxWallTimeSeconds");
        int approvalTimeout = bounded(request.approvalTimeoutSeconds(), 600, 30, 1_800,
                "approvalTimeoutSeconds");
        ManagedExecutionEntity replay = findBySource(
                trustedTenantId, projectCode, sourceType, sourceRef);
        if (replay != null) {
            requireSameCreate(replay, trustedUserId, provider, profile, modelRef,
                    acceptanceProfile, objectiveSha256, maxWallTime, approvalTimeout);
            return new CreatedView(toView(replay));
        }

        requireCreationEnabled();
        if (!properties.allowsProject(projectCode)) {
            throw new ManagedExecutionException(403, "MANAGED_EXECUTOR_PROJECT_NOT_ALLOWED",
                    "Managed Executor is not enabled for this project");
        }
        if (!properties.allowsProfile(profile)) {
            throw new ManagedExecutionException(403, "MANAGED_EXECUTOR_PROFILE_NOT_ALLOWED",
                    "Managed Executor profile is not enabled");
        }
        if (executionMapper.countActive() >= properties.maxClusterConcurrency()) {
            throw new ManagedExecutionException(429, "MANAGED_EXECUTOR_CAPACITY_EXHAUSTED",
                    "Managed Executor concurrency limit reached");
        }
        String executionId = "mex_" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();

        ManagedExecutionEntity entity = new ManagedExecutionEntity();
        entity.setExecutionId(executionId);
        entity.setTenantId(trustedTenantId);
        entity.setProjectCode(projectCode);
        entity.setRequestedByUserId(trustedUserId);
        entity.setSourceType(sourceType);
        entity.setSourceRef(sourceRef);
        entity.setExecutorProvider(provider);
        entity.setSandboxProfile(profile);
        entity.setModelRef(modelRef);
        entity.setAcceptanceProfile(acceptanceProfile);
        entity.setObjectiveText(objective);
        entity.setObjectiveSha256(objectiveSha256);
        entity.setStatus(ManagedExecutionStatus.QUEUED.name());
        entity.setCleanupStatus("PENDING");
        entity.setCleanupAttemptCount(0);
        entity.setCleanupAvailableAt(now);
        entity.setPriority(bounded(request.priority(), 0, -100, 100, "priority"));
        entity.setMaxWallTimeSeconds(maxWallTime);
        entity.setApprovalTimeoutSeconds(approvalTimeout);
        entity.setApprovalCount(0);
        entity.setCommandSequence(0L);
        entity.setLastEventSequence(0);
        entity.setProvisionAttemptCount(0);
        entity.setProvisionMaxAttempts(3);
        entity.setProvisionAvailableAt(now);
        entity.setVersion(0L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            executionMapper.insert(entity);
        } catch (DuplicateKeyException raced) {
            ManagedExecutionEntity concurrent = findBySource(
                    trustedTenantId, projectCode, sourceType, sourceRef);
            if (concurrent == null) throw raced;
            requireSameCreate(concurrent, trustedUserId, provider, profile, modelRef,
                    acceptanceProfile, objectiveSha256, maxWallTime, approvalTimeout);
            return new CreatedView(toView(concurrent));
        }
        appendOutbox(entity, "MANAGED_EXECUTION_QUEUED", Map.of("status", entity.getStatus()), now);
        return new CreatedView(toView(entity));
    }

    private ManagedExecutionEntity findBySource(String tenantId,
                                                String projectCode,
                                                String sourceType,
                                                String sourceRef) {
        if (!StringUtils.hasText(sourceRef)) return null;
        return executionMapper.selectOne(new LambdaQueryWrapper<ManagedExecutionEntity>()
                .eq(ManagedExecutionEntity::getTenantId, tenantId)
                .eq(ManagedExecutionEntity::getProjectCode, projectCode)
                .eq(ManagedExecutionEntity::getSourceType, sourceType)
                .eq(ManagedExecutionEntity::getSourceRef, sourceRef));
    }

    private void requireSameCreate(ManagedExecutionEntity existing,
                                   String requestedBy,
                                   String provider,
                                   String profile,
                                   String modelRef,
                                   String acceptanceProfile,
                                   String objectiveSha256,
                                   int maxWallTime,
                                   int approvalTimeout) {
        if (!requestedBy.equals(existing.getRequestedByUserId())
                || !provider.equals(existing.getExecutorProvider())
                || !profile.equals(existing.getSandboxProfile())
                || !java.util.Objects.equals(modelRef, existing.getModelRef())
                || !acceptanceProfile.equals(existing.getAcceptanceProfile())
                || !objectiveSha256.equals(existing.getObjectiveSha256())
                || maxWallTime != existing.getMaxWallTimeSeconds()
                || approvalTimeout != existing.getApprovalTimeoutSeconds()) {
            throw conflict("MANAGED_EXECUTION_IDEMPOTENCY_CONFLICT",
                    "Managed Execution sourceRef was already used with a different request");
        }
    }

    @Transactional
    public ProvisioningReservation reserveProvisioning() {
        if (!properties.enabled() || properties.maxClusterConcurrency() < 1) return null;
        LocalDateTime now = LocalDateTime.now();
        Long id = executionMapper.findProvisionCandidateId(now);
        if (id == null) return null;
        ManagedExecutionEntity candidate = executionMapper.selectById(id);
        if (candidate == null || !ManagedExecutionStatus.QUEUED.name().equals(candidate.getStatus())) return null;
        String workerToken = tokenService.generate();
        String tokenDigest = tokenService.digest(workerToken);
        long minimumTtl = (long) candidate.getMaxWallTimeSeconds()
                + candidate.getApprovalTimeoutSeconds() + 300L;
        LocalDateTime expiresAt = now.plusSeconds(Math.max(properties.workerTokenTtlSeconds(), minimumTtl));
        if (executionMapper.issueWorkerToken(id, tokenDigest, expiresAt, now) != 1) return null;
        candidate.setWorkerTokenDigest(tokenDigest);
        candidate.setWorkerTokenExpiresAt(expiresAt);
        candidate.setProvisionAttemptCount((candidate.getProvisionAttemptCount() == null
                ? 0 : candidate.getProvisionAttemptCount()) + 1);
        ProvisioningRequest request = new ProvisioningRequest(
                candidate.getExecutionId(),
                workerToken,
                candidate.getTenantId(),
                candidate.getProjectCode(),
                candidate.getSourceType(),
                candidate.getSourceRef(),
                candidate.getExecutorProvider(),
                candidate.getSandboxProfile(),
                candidate.getAcceptanceProfile(),
                candidate.getMaxWallTimeSeconds());
        return new ProvisioningReservation(request, tokenDigest);
    }

    @Transactional
    public void markProvisionDispatched(ProvisioningReservation reservation, SandboxHandle handle) {
        if (reservation == null || handle == null) return;
        ManagedExecutionEntity entity = requiredExecution(reservation.request().executionId());
        if (!reservation.tokenDigest().equals(entity.getWorkerTokenDigest())
                || !ManagedExecutionStatus.QUEUED.name().equals(entity.getStatus())) {
            throw conflict("MANAGED_EXECUTION_PROVISION_FENCE_LOST",
                    "Managed execution provisioning fence was lost");
        }
        LocalDateTime now = LocalDateTime.now();
        if (executionMapper.recordSandboxDispatched(
                entity.getExecutionId(), reservation.tokenDigest(), handle.sandboxRef(), now) != 1) {
            throw conflict("MANAGED_EXECUTION_PROVISION_FENCE_LOST",
                    "Managed execution provisioning fence was lost");
        }
        entity.setSandboxRef(handle.sandboxRef());
        appendOutbox(entity, "MANAGED_EXECUTION_JOB_DISPATCHED", Map.of("status", entity.getStatus()),
                now);
    }

    @Transactional
    public void releaseProvisioning(ProvisioningReservation reservation, String errorMessage) {
        if (reservation == null) return;
        LocalDateTime now = LocalDateTime.now();
        int attempts = requiredExecution(reservation.request().executionId()).getProvisionAttemptCount();
        long delay = Math.min(300L, 5L * (1L << Math.min(6, Math.max(0, attempts - 1))));
        if (executionMapper.releaseProvisioning(
                reservation.request().executionId(),
                reservation.tokenDigest(),
                safeOperationalMessage(errorMessage),
                now,
                now.plusSeconds(delay)) == 1) {
            ManagedExecutionEntity entity = requiredExecution(reservation.request().executionId());
            appendOutbox(entity, "MANAGED_EXECUTION_PROVISION_FAILED", Map.of("status", entity.getStatus()), now);
        }
    }

    @Transactional
    public void markProvisionOutcomeUnknown(ProvisioningReservation reservation, SandboxHandle handle) {
        if (reservation == null || handle == null) return;
        LocalDateTime now = LocalDateTime.now();
        if (executionMapper.markProvisionOutcomeUnknown(
                reservation.request().executionId(), reservation.tokenDigest(), handle.sandboxRef(), now) != 1) {
            throw conflict("MANAGED_EXECUTION_PROVISION_FENCE_LOST",
                    "Managed execution provisioning fence was lost");
        }
        ManagedExecutionEntity entity = requiredExecution(reservation.request().executionId());
        appendOutbox(entity, "MANAGED_EXECUTION_PROVISION_OUTCOME_UNKNOWN",
                Map.of("status", entity.getStatus()), now);
    }

    @Transactional
    public int recoverExpiredProvisioningTokens() {
        return executionMapper.recoverExpiredProvisioningTokens(LocalDateTime.now());
    }

    @Transactional
    public CleanupReservation reserveCleanup() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> candidates = executionMapper.findCleanupCandidateIds(now, 1);
        if (candidates.isEmpty()) return null;
        Long id = candidates.get(0);
        if (executionMapper.claimCleanup(id, now, now.plusMinutes(2)) != 1) return null;
        ManagedExecutionEntity entity = executionMapper.selectById(id);
        if (entity == null) return null;
        return new CleanupReservation(id, entity.getExecutionId(), entity.getSandboxRef(),
                entity.getCleanupAttemptCount() == null ? 1 : entity.getCleanupAttemptCount());
    }

    @Transactional
    public void completeCleanup(CleanupReservation reservation) {
        if (reservation == null) return;
        LocalDateTime now = LocalDateTime.now();
        if (executionMapper.markCleanupCompleted(reservation.id(), now) == 1) {
            ManagedExecutionEntity entity = executionMapper.selectById(reservation.id());
            if (entity != null) {
                appendOutbox(entity, "MANAGED_EXECUTION_SANDBOX_CLEANED",
                        Map.of("status", entity.getStatus()), now);
            }
        }
    }

    @Transactional
    public void failCleanup(CleanupReservation reservation, String failureMessage) {
        if (reservation == null) return;
        LocalDateTime now = LocalDateTime.now();
        long delay = Math.min(900L, 10L * (1L << Math.min(6, Math.max(0, reservation.attemptCount() - 1))));
        if (executionMapper.markCleanupFailed(
                reservation.id(), safeCleanupMessage(failureMessage), now.plusSeconds(delay), now) == 1) {
            ManagedExecutionEntity entity = executionMapper.selectById(reservation.id());
            if (entity != null) {
                appendOutbox(entity, "MANAGED_EXECUTION_SANDBOX_CLEANUP_FAILED",
                        Map.of("status", entity.getStatus()), now);
            }
        }
    }

    public ExecutionView get(String executionId, String tenantId) {
        ManagedExecutionEntity entity = requiredExecution(executionId);
        requireTenant(entity, tenantId);
        return toView(entity);
    }

    public ManagedExecutionViews.ApprovalView approval(
            String executionId,
            String tenantId) {
        ManagedExecutionEntity entity = requiredExecution(executionId);
        requireTenant(entity, tenantId);
        return approvalService.view(entity);
    }

    @Transactional
    public ExecutionView cancel(String executionId, String tenantId, String reason) {
        ManagedExecutionEntity entity = requiredExecution(executionId);
        requireTenant(entity, tenantId);
        ManagedExecutionStatus status = ManagedExecutionStatus.parse(entity.getStatus());
        if (status.terminal() || status == ManagedExecutionStatus.CANCELLING) return toView(entity);
        String safeReason = boundedText(defaultText(reason, "Cancelled by operator"), 1_000);
        LocalDateTime now = LocalDateTime.now();
        if (executionMapper.requestCancel(entity.getExecutionId(), safeReason, now) != 1) {
            throw conflict("MANAGED_EXECUTION_STATE_CHANGED", "Managed execution state changed during cancellation");
        }
        entity = requiredExecution(executionId);
        appendOutbox(entity, "MANAGED_EXECUTION_CANCEL_REQUESTED", Map.of("status", entity.getStatus()), now);
        return toView(entity);
    }

    @Transactional
    public WorkerClaimView claim(String executionId, String workerToken, String workerId) {
        String trustedWorkerId = identifier(workerId, "workerId", 128);
        ManagedExecutionEntity entity = authenticateWorker(executionId, workerToken);
        String tokenDigest = tokenService.digest(workerToken);
        ManagedExecutionStatus status = ManagedExecutionStatus.parse(entity.getStatus());
        if (trustedWorkerId.equals(entity.getLeaseOwner())
                && (status == ManagedExecutionStatus.PROVISIONING || status == ManagedExecutionStatus.RUNNING)) {
            return toClaim(entity);
        }
        if (status != ManagedExecutionStatus.QUEUED) {
            throw conflict("MANAGED_EXECUTION_NOT_CLAIMABLE", "Managed execution is not claimable");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseExpiresAt = now.plusSeconds(properties.workerLeaseSeconds());
        if (executionMapper.claim(executionId, tokenDigest, trustedWorkerId, now, leaseExpiresAt) != 1) {
            throw conflict("MANAGED_EXECUTION_CLAIM_CONFLICT", "Managed execution was claimed by another worker");
        }
        entity = requiredExecution(executionId);
        appendOutbox(entity, "MANAGED_EXECUTION_PROVISIONING", Map.of("status", entity.getStatus()), now);
        return toClaim(entity);
    }

    @Transactional
    public WorkerMutationView heartbeat(String executionId, String workerToken, String workerId) {
        ManagedExecutionEntity entity = requireWorkerLease(executionId, workerToken, workerId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseExpiresAt = now.plusSeconds(properties.workerLeaseSeconds());
        if (executionMapper.heartbeat(executionId, tokenService.digest(workerToken), workerId,
                now, leaseExpiresAt) != 1) {
            throw conflict("MANAGED_EXECUTION_LEASE_LOST", "Managed execution worker lease was lost");
        }
        entity.setLeaseExpiresAt(leaseExpiresAt);
        entity.setLastHeartbeatAt(now);
        return toWorkerMutation(entity);
    }

    @Transactional
    public WorkerMutationView appendEvents(String executionId,
                                           String workerToken,
                                           String workerId,
                                           WorkerEventBatchRequest request) {
        ManagedExecutionEntity entity = requireWorkerLease(executionId, workerToken, workerId);
        List<WorkerEventV1> events = request == null ? List.of() : request.events();
        if (events.isEmpty() || events.size() > properties.maxEventBatchSize()) {
            throw invalid("Managed execution event batch size is invalid");
        }
        String tokenDigest = tokenService.digest(workerToken);
        int lastSequence = entity.getLastEventSequence() == null ? 0 : entity.getLastEventSequence();
        ManagedExecutionStatus currentStatus = ManagedExecutionStatus.parse(entity.getStatus());
        LocalDateTime now = LocalDateTime.now();
        Instant receivedAt = Instant.now();

        for (WorkerEventV1 event : events) {
            SanitizedEvent sanitized = payloadSanitizer.sanitize(executionId, event, receivedAt);
            if (sanitized.sequence() <= lastSequence) {
                verifyDuplicateEvent(executionId, sanitized);
                continue;
            }
            if (sanitized.sequence() != lastSequence + 1) {
                throw conflict("MANAGED_EVENT_SEQUENCE_GAP", "Managed execution event sequence contains a gap");
            }
            ManagedExecutionStatus nextStatus = transitionForEvent(currentStatus, sanitized);
            LocalDateTime leaseExpiresAt = now.plusSeconds(properties.workerLeaseSeconds());
            if (executionMapper.advanceEvent(executionId, tokenDigest, workerId, lastSequence,
                    sanitized.sequence(), nextStatus.name(), now, leaseExpiresAt) != 1) {
                throw conflict("MANAGED_EVENT_SEQUENCE_CONFLICT",
                        "Managed execution event sequence changed concurrently");
            }
            eventMapper.insert(toEventEntity(executionId, sanitized, now));
            entity.setStatus(nextStatus.name());
            ManagedExecutionApprovalService.ApprovalOpened opened = null;
            ManagedExecutionApprovalService.ApprovalClosed closed = null;
            if ("APPROVAL_REQUESTED".equals(sanitized.type())) {
                opened = approvalService.onRequested(entity, sanitized);
            } else if ("APPROVAL_RESOLVED".equals(sanitized.type())) {
                closed = approvalService.onResolved(entity, sanitized);
            }
            if (nextStatus != currentStatus) {
                appendOutbox(entity, "MANAGED_EXECUTION_STATUS_CHANGED",
                        Map.of("status", nextStatus.name(), "sequence", sanitized.sequence()), now);
            }
            if (opened != null) {
                appendOutbox(entity, "MANAGED_EXECUTION_APPROVAL_REQUESTED", Map.of(
                        "status", nextStatus.name(),
                        "sequence", sanitized.sequence(),
                        "interactionId", opened.interactionId(),
                        "approvalRequestId", opened.approvalRequestId(),
                        "approvalKind", opened.approvalKind()), now);
            }
            if (closed != null) {
                appendOutbox(entity, "MANAGED_EXECUTION_APPROVAL_RESOLVED", Map.of(
                        "status", nextStatus.name(),
                        "sequence", sanitized.sequence(),
                        "interactionId", closed.interactionId(),
                        "approvalRequestId", closed.approvalRequestId(),
                        "decision", closed.decision()), now);
            }
            lastSequence = sanitized.sequence();
            currentStatus = nextStatus;
            entity.setLastEventSequence(lastSequence);
            entity.setLeaseExpiresAt(leaseExpiresAt);
        }
        return toWorkerMutation(entity);
    }

    public WorkerCommandBatchView commands(String executionId,
                                           String workerToken,
                                           String workerId,
                                           long afterSequence) {
        ManagedExecutionEntity entity = requireWorkerLease(executionId, workerToken, workerId);
        long cursor = entity.getCommandSequence() == null ? 0L : entity.getCommandSequence();
        if (ManagedExecutionStatus.CANCELLING.name().equals(entity.getStatus()) && cursor > afterSequence) {
            return new WorkerCommandBatchView(cursor, List.of(new WorkerCommand(
                    cursor, "CANCEL", Map.of("reasonCode", "MANAGED_EXECUTION_CANCEL_REQUESTED"))));
        }
        if (ManagedExecutionStatus.WAITING_APPROVAL.name().equals(entity.getStatus())
                && StringUtils.hasText(entity.getApprovalDecision())
                && StringUtils.hasText(entity.getPendingApprovalRequestId())
                && cursor > afterSequence) {
            return new WorkerCommandBatchView(cursor, List.of(new WorkerCommand(
                    cursor, "APPROVAL_DECISION", Map.of(
                    "approvalRequestId", entity.getPendingApprovalRequestId(),
                    "decision", entity.getApprovalDecision()))));
        }
        return new WorkerCommandBatchView(Math.max(afterSequence, cursor), List.of());
    }

    @Transactional
    public ManagedExecutionViews.ApprovalDecisionView resolveApproval(
            String executionId,
            String tenantId,
            String actorUserId,
            String interactionId,
            ManagedExecutionViews.ApprovalDecisionRequest request) {
        ManagedExecutionEntity entity = requiredExecution(executionId);
        requireTenant(entity, tenantId);
        ManagedExecutionViews.ApprovalDecisionView decision =
                approvalService.resolve(entity, actorUserId, interactionId, request);
        appendOutbox(entity, "MANAGED_EXECUTION_APPROVAL_DECIDED", Map.of(
                "status", entity.getStatus(),
                "interactionId", decision.interactionId(),
                "approvalRequestId", decision.approvalRequestId(),
                "decision", decision.decision(),
                "expired", decision.expired()), LocalDateTime.now());
        return decision;
    }

    @Transactional
    public WorkerMutationView complete(String executionId,
                                       String workerToken,
                                       String workerId,
                                       WorkerCompleteRequest request) {
        ManagedExecutionEntity entity = authenticateWorker(executionId, workerToken);
        String outcome = enumValue(request == null ? null : request.outcome(),
                Set.of("SUCCEEDED", "FAILED", "CANCELLED"), "outcome");
        ManagedExecutionStatus current = ManagedExecutionStatus.parse(entity.getStatus());
        if (current.terminal()) {
            if (current.name().equals(outcome)) return toWorkerMutation(entity);
            throw conflict("MANAGED_EXECUTION_ALREADY_TERMINAL", "Managed execution is already terminal");
        }
        if ("SUCCEEDED".equals(outcome)
                && current == ManagedExecutionStatus.FINALIZING
                && entity.getLeaseOwner() == null) {
            persistArtifacts(entity, request.artifacts(), LocalDateTime.now());
            return finalizeVerifiedArtifacts(entity, LocalDateTime.now());
        }
        if (!workerId.equals(entity.getLeaseOwner())
                || entity.getLeaseExpiresAt() == null
                || !entity.getLeaseExpiresAt().isAfter(LocalDateTime.now())) {
            throw conflict("MANAGED_EXECUTION_LEASE_LOST", "Managed execution worker lease was lost");
        }

        LocalDateTime now = LocalDateTime.now();
        String nextStatus;
        String errorCode = null;
        String errorMessage = null;
        if ("SUCCEEDED".equals(outcome)) {
            if (current != ManagedExecutionStatus.FINALIZING) {
                throw conflict("MANAGED_EXECUTION_NOT_FINALIZING",
                        "Successful worker completion requires a completed Codex turn");
            }
            persistArtifacts(entity, request.artifacts(), now);
            nextStatus = ManagedExecutionStatus.FINALIZING.name();
        } else if ("CANCELLED".equals(outcome)) {
            nextStatus = ManagedExecutionStatus.CANCELLED.name();
            errorCode = "MANAGED_EXECUTION_CANCELLED";
            errorMessage = "Managed Executor worker cancelled";
        } else {
            nextStatus = ManagedExecutionStatus.FAILED.name();
            errorCode = safeErrorCode(request.errorCode());
            errorMessage = "Managed Executor worker failed";
        }
        if (executionMapper.completeByWorker(executionId, tokenService.digest(workerToken), workerId,
                nextStatus, errorCode, errorMessage, now) != 1) {
            throw conflict("MANAGED_EXECUTION_COMPLETE_CONFLICT", "Managed execution completion conflicted");
        }
        entity = requiredExecution(executionId);
        if ("SUCCEEDED".equals(outcome)) {
            return finalizeVerifiedArtifacts(entity, now);
        }
        appendOutbox(entity, "MANAGED_EXECUTION_TERMINAL", Map.of("status", entity.getStatus()), now);
        return toWorkerMutation(entity);
    }

    private WorkerMutationView finalizeVerifiedArtifacts(ManagedExecutionEntity entity, LocalDateTime now) {
        if (!artifactsReady(entity.getExecutionId())) {
            throw conflict("MANAGED_ARTIFACT_NOT_VERIFIED",
                    "Managed execution artifacts are not independently verified");
        }
        if (executionMapper.markArtifactsVerified(entity.getExecutionId(), now) != 1) {
            throw conflict("MANAGED_EXECUTION_COMPLETE_CONFLICT",
                    "Managed execution artifact finalization conflicted");
        }
        ManagedExecutionEntity succeeded = requiredExecution(entity.getExecutionId());
        appendOutbox(succeeded, "MANAGED_EXECUTION_SUCCEEDED", Map.of("status", succeeded.getStatus()), now);
        return toWorkerMutation(succeeded);
    }

    @Transactional
    public ExecutionView confirmArtifact(String executionId,
                                         String artifactId,
                                         String sha256,
                                         String validationStatus,
                                         String scanStatus,
                                         String rejectionCode) {
        String validation = enumValue(validationStatus, Set.of("VERIFIED", "REJECTED"), "validationStatus");
        String scan = enumValue(scanStatus, Set.of("CLEAN", "REJECTED", "NOT_REQUIRED"), "scanStatus");
        LocalDateTime now = LocalDateTime.now();
        if (artifactMapper.confirm(identifier(executionId, "executionId", 128),
                identifier(artifactId, "artifactId", 128), digest(sha256, "sha256"),
                validation, scan, optionalIdentifier(rejectionCode, "rejectionCode", 128), now) != 1) {
            throw new ManagedExecutionException(404, "MANAGED_ARTIFACT_NOT_FOUND",
                    "Managed execution artifact was not found");
        }
        ManagedExecutionEntity entity = requiredExecution(executionId);
        if ("REJECTED".equals(validation) || "REJECTED".equals(scan)) {
            if (executionMapper.markArtifactRejected(executionId, now) == 1) {
                entity = requiredExecution(executionId);
                appendOutbox(entity, "MANAGED_EXECUTION_FAILED", Map.of("status", entity.getStatus()), now);
            }
            return toView(entity);
        }
        if (artifactsReady(executionId) && executionMapper.markArtifactsVerified(executionId, now) == 1) {
            entity = requiredExecution(executionId);
            appendOutbox(entity, "MANAGED_EXECUTION_SUCCEEDED", Map.of("status", entity.getStatus()), now);
        }
        return toView(entity);
    }

    @Transactional
    public int expireLeases(int requestedLimit) {
        LocalDateTime now = LocalDateTime.now();
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        int expired = 0;
        for (Long id : executionMapper.findExpiredLeaseIds(now, limit)) {
            if (executionMapper.markTimedOut(id, now) == 1) {
                ManagedExecutionEntity entity = executionMapper.selectById(id);
                if (entity != null) {
                    appendOutbox(entity, "MANAGED_EXECUTION_TIMED_OUT", Map.of("status", entity.getStatus()), now);
                }
                expired++;
            }
        }
        return expired;
    }

    private void persistArtifacts(ManagedExecutionEntity execution,
                                  List<ArtifactDescriptor> descriptors,
                                  LocalDateTime now) {
        if (descriptors == null || descriptors.size() > 20) {
            throw invalid("Managed execution artifact list is invalid");
        }
        Set<String> types = new HashSet<>();
        for (ArtifactDescriptor descriptor : descriptors) {
            if (descriptor == null) throw invalid("Managed execution artifact descriptor is required");
            String type = enumValue(descriptor.artifactType(), ARTIFACT_TYPES, "artifactType");
            if (!types.add(type)) throw invalid("Managed execution artifact types must be unique");
            String artifactId = identifier(descriptor.artifactId(), "artifactId", 128);
            String objectKey = requiredText(descriptor.objectKey(), "objectKey", 1_000);
            String prefix = "managed-executions/" + execution.getExecutionId() + "/";
            if (!objectKey.startsWith(prefix) || objectKey.contains("..") || objectKey.contains("\\")) {
                throw invalid("Managed execution artifact objectKey is outside the execution prefix");
            }
            long size = descriptor.sizeBytes() == null ? -1L : descriptor.sizeBytes();
            if (size < 0 || size > 64L * 1024L * 1024L) {
                throw new ManagedExecutionException(413, "MANAGED_ARTIFACT_TOO_LARGE",
                        "Managed execution artifact exceeds the size limit");
            }
            ManagedArtifactEntity existing = artifactMapper.selectOne(
                    new LambdaQueryWrapper<ManagedArtifactEntity>()
                            .eq(ManagedArtifactEntity::getExecutionId, execution.getExecutionId())
                            .eq(ManagedArtifactEntity::getArtifactType, type));
            String artifactDigest = digest(descriptor.sha256(), "artifact sha256");
            String mediaType = requiredText(descriptor.mediaType(), "mediaType", 128);
            if (existing == null) {
                throw conflict("MANAGED_ARTIFACT_NOT_UPLOADED",
                        "Managed execution artifact must be uploaded before completion");
            }
            if (!artifactId.equals(existing.getArtifactId())
                    || !artifactDigest.equals(existing.getSha256())
                    || !objectKey.equals(existing.getObjectKey())
                    || existing.getSizeBytes() == null
                    || size != existing.getSizeBytes()
                    || !mediaType.equals(existing.getMediaType())
                    || !"VERIFIED".equals(existing.getValidationStatus())
                    || !"CLEAN".equals(existing.getScanStatus())) {
                throw conflict("MANAGED_ARTIFACT_CONFLICT",
                        "Managed execution artifact descriptor does not match verified evidence");
            }
        }
        if (!types.containsAll(REQUIRED_ARTIFACT_TYPES)) {
            throw invalid("Managed execution completion is missing required artifacts");
        }
    }

    private boolean artifactsReady(String executionId) {
        List<ManagedArtifactEntity> artifacts = artifactMapper.selectList(
                new LambdaQueryWrapper<ManagedArtifactEntity>()
                        .eq(ManagedArtifactEntity::getExecutionId, executionId));
        Map<String, ManagedArtifactEntity> byType = new LinkedHashMap<>();
        for (ManagedArtifactEntity artifact : artifacts) byType.put(artifact.getArtifactType(), artifact);
        boolean statusesReady = REQUIRED_ARTIFACT_TYPES.stream().allMatch(type -> {
            ManagedArtifactEntity artifact = byType.get(type);
            return artifact != null
                    && "VERIFIED".equals(artifact.getValidationStatus())
                    && "CLEAN".equals(artifact.getScanStatus());
        });
        if (!statusesReady) return false;
        artifactVerifier.verifyBundle(executionId, artifacts);
        return true;
    }

    ManagedExecutionEntity requireArtifactUploadLease(String executionId,
                                                       String workerToken,
                                                       String workerId) {
        ManagedExecutionEntity entity = requireWorkerLease(executionId, workerToken, workerId);
        if (!ManagedExecutionStatus.FINALIZING.name().equals(entity.getStatus())) {
            throw conflict("MANAGED_EXECUTION_NOT_FINALIZING",
                    "Managed execution artifacts may only be uploaded while finalizing");
        }
        return entity;
    }

    private ManagedExecutionEntity requireWorkerLease(String executionId, String workerToken, String workerId) {
        ManagedExecutionEntity entity = authenticateWorker(executionId, workerToken);
        if (!identifier(workerId, "workerId", 128).equals(entity.getLeaseOwner())
                || entity.getLeaseExpiresAt() == null
                || !entity.getLeaseExpiresAt().isAfter(LocalDateTime.now())) {
            throw conflict("MANAGED_EXECUTION_LEASE_LOST", "Managed execution worker lease was lost");
        }
        return entity;
    }

    private ManagedExecutionEntity authenticateWorker(String executionId, String workerToken) {
        ManagedExecutionEntity entity = requiredExecution(executionId);
        LocalDateTime now = LocalDateTime.now();
        if (!tokenService.matches(workerToken, entity.getWorkerTokenDigest())
                || entity.getWorkerTokenExpiresAt() == null
                || !entity.getWorkerTokenExpiresAt().isAfter(now)) {
            throw new ManagedExecutionException(401, "MANAGED_WORKER_AUTH_REQUIRED",
                    "Managed Executor worker authentication failed");
        }
        return entity;
    }

    private void verifyDuplicateEvent(String executionId, SanitizedEvent event) {
        ManagedExecutionEventEntity existing = eventMapper.selectOne(
                new LambdaQueryWrapper<ManagedExecutionEventEntity>()
                        .eq(ManagedExecutionEventEntity::getExecutionId, executionId)
                        .eq(ManagedExecutionEventEntity::getSequence, event.sequence()));
        if (existing == null
                || !event.eventId().equals(existing.getEventId())
                || !event.payloadSha256().equals(existing.getPayloadSha256())) {
            throw conflict("MANAGED_EVENT_REPLAY_MISMATCH",
                    "Managed execution event replay does not match the persisted event");
        }
    }

    private ManagedExecutionStatus transitionForEvent(ManagedExecutionStatus current, SanitizedEvent event) {
        if (current == ManagedExecutionStatus.CANCELLING) {
            return "TURN_COMPLETED".equals(event.type()) && "CANCELLED".equals(event.phase())
                    ? ManagedExecutionStatus.CANCELLED : current;
        }
        if (current.terminal()) return current;
        return switch (event.type()) {
            case "TURN_STARTED" -> current == ManagedExecutionStatus.PROVISIONING
                    ? ManagedExecutionStatus.RUNNING : current;
            case "APPROVAL_REQUESTED" -> ManagedExecutionStatus.WAITING_APPROVAL;
            case "APPROVAL_RESOLVED" -> current == ManagedExecutionStatus.WAITING_APPROVAL
                    ? ManagedExecutionStatus.RUNNING : current;
            case "APP_SERVER_ERROR" -> ManagedExecutionStatus.FAILED;
            case "TURN_COMPLETED" -> switch (event.phase()) {
                case "SUCCEEDED" -> ManagedExecutionStatus.FINALIZING;
                case "CANCELLED" -> ManagedExecutionStatus.CANCELLED;
                default -> ManagedExecutionStatus.FAILED;
            };
            default -> current;
        };
    }

    private ManagedExecutionEventEntity toEventEntity(String executionId,
                                                       SanitizedEvent event,
                                                       LocalDateTime receivedAt) {
        ManagedExecutionEventEntity entity = new ManagedExecutionEventEntity();
        entity.setExecutionId(executionId);
        entity.setSequence(event.sequence());
        entity.setEventId(event.eventId());
        entity.setEventType(event.type());
        entity.setPhase(event.phase());
        entity.setVisibility(event.visibility());
        entity.setPersistence("DURABLE");
        entity.setMessage(event.message());
        entity.setDataJson(event.dataJson());
        entity.setPayloadSha256(event.payloadSha256());
        entity.setOccurredAt(LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
        entity.setReceivedAt(receivedAt);
        return entity;
    }

    private WorkerClaimView toClaim(ManagedExecutionEntity entity) {
        return new WorkerClaimView(
                "reachai.managed-executor.claim.v1",
                entity.getExecutionId(),
                entity.getObjectiveText(),
                entity.getObjectiveSha256(),
                entity.getSandboxProfile(),
                entity.getExecutorProvider(),
                entity.getModelRef(),
                entity.getAcceptanceProfile(),
                entity.getMaxWallTimeSeconds() * 1_000,
                entity.getApprovalTimeoutSeconds() * 1_000,
                entity.getLeaseExpiresAt());
    }

    private WorkerMutationView toWorkerMutation(ManagedExecutionEntity entity) {
        return new WorkerMutationView(
                entity.getExecutionId(),
                entity.getStatus(),
                entity.getLastEventSequence() == null ? 0 : entity.getLastEventSequence(),
                entity.getCancelRequestedAt() != null || ManagedExecutionStatus.CANCELLING.name().equals(entity.getStatus()),
                entity.getLeaseExpiresAt());
    }

    private ExecutionView toView(ManagedExecutionEntity entity) {
        return new ExecutionView(
                entity.getExecutionId(),
                entity.getTenantId(),
                entity.getProjectCode(),
                entity.getRequestedByUserId(),
                entity.getSourceType(),
                entity.getSourceRef(),
                entity.getExecutorProvider(),
                entity.getSandboxProfile(),
                entity.getModelRef(),
                entity.getAcceptanceProfile(),
                entity.getObjectiveSha256(),
                entity.getStatus(),
                entity.getCleanupStatus(),
                entity.getPendingInteractionId(),
                entity.getPendingApprovalRequestId(),
                entity.getApprovalCount() == null ? 0 : entity.getApprovalCount(),
                entity.getPriority() == null ? 0 : entity.getPriority(),
                entity.getMaxWallTimeSeconds() == null ? 0 : entity.getMaxWallTimeSeconds(),
                entity.getApprovalTimeoutSeconds() == null ? 0 : entity.getApprovalTimeoutSeconds(),
                entity.getLastEventSequence() == null ? 0 : entity.getLastEventSequence(),
                entity.getCancelRequestedAt() != null,
                entity.getErrorCode(),
                entity.getErrorMessage(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getStartedAt(),
                entity.getFinalizingAt(),
                entity.getCompletedAt());
    }

    private ManagedExecutionEntity requiredExecution(String executionId) {
        String trustedExecutionId = identifier(executionId, "executionId", 128);
        ManagedExecutionEntity entity = executionMapper.selectOne(
                new LambdaQueryWrapper<ManagedExecutionEntity>()
                        .eq(ManagedExecutionEntity::getExecutionId, trustedExecutionId));
        if (entity == null) {
            throw new ManagedExecutionException(404, "MANAGED_EXECUTION_NOT_FOUND",
                    "Managed execution was not found");
        }
        return entity;
    }

    private void requireTenant(ManagedExecutionEntity entity, String tenantId) {
        if (!identifier(tenantId, "tenantId", 96).equals(entity.getTenantId())) {
            throw new ManagedExecutionException(404, "MANAGED_EXECUTION_NOT_FOUND",
                    "Managed execution was not found");
        }
    }

    private void requireCreationEnabled() {
        if (!properties.enabled() || properties.maxClusterConcurrency() < 1) {
            throw new ManagedExecutionException(503, "MANAGED_EXECUTOR_DISABLED",
                    "Managed Executor is disabled");
        }
        if (!sandboxProvisioner.available()) {
            throw new ManagedExecutionException(503, "MANAGED_SANDBOX_BACKEND_UNAVAILABLE",
                    "Managed Sandbox backend is unavailable");
        }
        if (!artifactStore.available()) {
            throw new ManagedExecutionException(503, "MANAGED_ARTIFACT_STORE_UNAVAILABLE",
                    "Managed Executor artifact store is unavailable");
        }
    }

    private void appendOutbox(ManagedExecutionEntity execution,
                              String eventType,
                              Map<String, Object> payload,
                              LocalDateTime now) {
        if (ManagedExecutionStatus.parse(execution.getStatus()).terminal()) {
            approvalService.onExecutionTerminal(execution);
        }
        runProjector.sync(execution);
        ManagedExecutionOutboxEntity outbox = new ManagedExecutionOutboxEntity();
        outbox.setEventId("mout_" + UUID.randomUUID().toString().replace("-", ""));
        outbox.setExecutionId(execution.getExecutionId());
        outbox.setEventType(eventType);
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("schema", "reachai.managed-execution.outbox.v1");
        body.put("executionId", execution.getExecutionId());
        body.put("tenantId", execution.getTenantId());
        body.put("projectCode", execution.getProjectCode());
        body.put("sourceType", execution.getSourceType());
        body.put("sourceRef", execution.getSourceRef());
        body.putAll(payload);
        try {
            outbox.setPayloadJson(objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException serialization) {
            throw new IllegalStateException("Managed execution outbox serialization failed", serialization);
        }
        outbox.setStatus("PENDING");
        outbox.setAttemptCount(0);
        outbox.setAvailableAt(now);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        outboxMapper.insert(outbox);
    }

    private String safeErrorCode(String value) {
        if (!StringUtils.hasText(value)) return "MANAGED_EXECUTION_WORKER_FAILED";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z0-9_]{1,128}") ? normalized : "MANAGED_EXECUTION_WORKER_FAILED";
    }

    private String identifier(String value, String field, int maximum) {
        String result = requiredText(value, field, maximum);
        if (!result.matches("[A-Za-z0-9._:-]+")) throw invalid(field + " contains unsupported characters");
        return result;
    }

    private String optionalIdentifier(String value, String field, int maximum) {
        return StringUtils.hasText(value) ? identifier(value, field, maximum) : null;
    }

    private String requiredText(String value, String field, int maximum) {
        if (!StringUtils.hasText(value)) throw invalid(field + " is required");
        String result = value.trim();
        if (result.codePointCount(0, result.length()) > maximum || result.chars().anyMatch(Character::isISOControl)) {
            throw invalid(field + " exceeds its limit or contains control characters");
        }
        return result;
    }

    private String requiredContent(String value, String field, int maximum) {
        if (!StringUtils.hasText(value)) throw invalid(field + " is required");
        String result = value.trim();
        boolean forbiddenControl = result.codePoints()
                .anyMatch(codePoint -> Character.isISOControl(codePoint)
                        && codePoint != '\n' && codePoint != '\r' && codePoint != '\t');
        if (result.codePointCount(0, result.length()) > maximum || forbiddenControl) {
            throw invalid(field + " exceeds its limit or contains unsupported control characters");
        }
        return result;
    }

    private String boundedText(String value, int maximum) {
        if (value == null) return null;
        return value.codePoints().limit(maximum)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    private String safeOperationalMessage(String value) {
        return "Managed Sandbox provision failed";
    }

    private String safeCleanupMessage(String value) {
        return "Managed Sandbox cleanup failed";
    }

    private String enumValue(String value, Set<String> allowed, String field) {
        if (!StringUtils.hasText(value)) throw invalid(field + " is required");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw invalid(field + " is invalid");
        return normalized;
    }

    private int bounded(Integer value, int fallback, int minimum, int maximum, String field) {
        int result = value == null ? fallback : value;
        if (result < minimum || result > maximum) throw invalid(field + " is outside the accepted range");
        return result;
    }

    private String digest(String value, String field) {
        if (value == null || !value.matches("[a-fA-F0-9]{64}")) throw invalid(field + " must be SHA-256 hex");
        return value.toLowerCase(Locale.ROOT);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private ManagedExecutionException invalid(String message) {
        return new ManagedExecutionException(400, "MANAGED_EXECUTION_REQUEST_INVALID", message);
    }

    private ManagedExecutionException conflict(String code, String message) {
        return new ManagedExecutionException(409, code, message);
    }

    public record ProvisioningReservation(ProvisioningRequest request, String tokenDigest) {
    }

    public record CleanupReservation(Long id, String executionId, String sandboxRef, int attemptCount) {
    }
}

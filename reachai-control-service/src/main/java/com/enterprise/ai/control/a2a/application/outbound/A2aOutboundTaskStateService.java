package com.enterprise.ai.control.a2a.application.outbound;

import com.enterprise.ai.control.a2a.application.identity.A2aCredentialBinding;
import com.enterprise.ai.control.a2a.application.port.A2aContentCipher;
import com.enterprise.ai.control.a2a.application.port.A2aCredentialRepository;
import com.enterprise.ai.control.a2a.application.port.A2aOutboundProtocolCodec;
import com.enterprise.ai.control.a2a.application.port.A2aOutboundExecutionRepository;
import com.enterprise.ai.control.a2a.application.port.A2aPrincipalRepository;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteAgentRepository;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteAuthenticationPlanner;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteProtocolTransport;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository;
import com.enterprise.ai.control.a2a.application.port.A2aTransportAuditRepository;
import com.enterprise.ai.control.a2a.application.port.A2aTrustProfileRepository;
import com.enterprise.ai.control.a2a.application.task.A2aContentBinding;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aPrincipalType;
import com.enterprise.ai.control.a2a.domain.A2aTaskLifecycle;
import com.enterprise.ai.control.a2a.domain.A2aTaskState;
import com.enterprise.ai.control.a2a.domain.identity.A2aCredential;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipal;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipalStatus;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgent;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentHealth;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentRevision;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentStatus;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteInterface;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteRevisionReviewStatus;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.enterprise.ai.control.a2a.application.outbound.A2aOutboundDelegationContracts.SendRequest;
import static com.enterprise.ai.control.a2a.application.outbound.A2aOutboundDelegationContracts.SendResponse;
import static com.enterprise.ai.control.a2a.application.outbound.A2aOutboundPolicyAuthorizer.AcceptedPolicy;
import static com.enterprise.ai.control.a2a.application.port.A2aOutboundProtocolCodec.DecodedArtifact;
import static com.enterprise.ai.control.a2a.application.port.A2aOutboundProtocolCodec.DecodedMessage;
import static com.enterprise.ai.control.a2a.application.port.A2aOutboundProtocolCodec.DecodedResponse;
import static com.enterprise.ai.control.a2a.application.port.A2aOutboundProtocolCodec.EncodedRequest;
import static com.enterprise.ai.control.a2a.application.port.A2aOutboundExecutionRepository.OutboundExecution;
import static com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.ArtifactRecord;
import static com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.ContextRecord;
import static com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.MessageRecord;
import static com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.TaskEventRecord;
import static com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.TaskRecord;

/** Durable outbound Task projection. No network call is made from this transactional service. */
@Service
@RequiredArgsConstructor
public class A2aOutboundTaskStateService {

    private static final A2aDirection OUTBOUND = A2aDirection.OUTBOUND;
    private static final Pattern RUNTIME_AGENT_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final A2aTaskRepository tasks;
    private final A2aOutboundExecutionRepository outboundExecutions;
    private final A2aPrincipalRepository principals;
    private final A2aTrustProfileRepository trustProfiles;
    private final A2aRemoteAgentRepository remoteAgents;
    private final A2aCredentialRepository credentials;
    private final A2aRemoteAuthenticationPlanner authenticationPlanner;
    private final A2aOutboundProtocolCodec protocolCodec;
    private final A2aOutboundPolicyAuthorizer policyAuthorizer;
    private final A2aContentCipher contentCipher;
    private final A2aTransportAuditRepository transportAudit;
    private final ObjectMapper objectMapper;
    private final A2aHubProperties properties;
    private final Clock clock;

    @Transactional
    public PreparedSend prepare(SendRequest request) {
        requireRequest(request);
        LocalDateTime now = now();
        A2aPrincipal principal = requireLocalAgentPrincipal(request);
        A2aTrustProfile principalTrust = requireTrust(principal.trustProfileId());
        A2aRemoteAgent remoteAgent = requireRemoteAgent(request);
        A2aRemoteAgentRevision revision = requireRemoteRevision(request, remoteAgent);

        MessageRecord duplicate = tasks.findMessage(
                OUTBOUND, principal.id(), principal.tenantScope(), request.messageId()).orElse(null);
        if (duplicate != null) {
            TaskRecord task = tasks.findTaskByRefId(
                            OUTBOUND, principal.id(), principal.tenantScope(),
                            required(duplicate.taskRefId(), "message.taskRefId"))
                    .orElseThrow(() -> new A2aDomainException("A2A_TASK_NOT_FOUND",
                            "the idempotent outbound Task is unavailable"));
            ContextRecord context = requireContext(task, principal, request, remoteAgent, revision);
            validateRequestedTaskContext(request, task, context);
            OutboundExecution accepted = requireMatchingExecution(
                    request, task, principalTrust);
            A2aRemoteInterface remoteInterface = revision.requireInterface(
                    accepted.remoteInterfaceKey());
            EncodedRequest encoded = encode(request, remoteInterface, context, task);
            if (!Objects.equals(duplicate.idempotencySha256(), encoded.idempotencySha256())) {
                throw new A2aDomainException("A2A_MESSAGE_IDEMPOTENCY_CONFLICT",
                        "messageId was already used with different outbound content");
            }
            return PreparedSend.replay(result(task, context, true), task.executionId());
        }

        TaskRecord continuation = request.taskId() == null || request.taskId().isBlank()
                ? null : requireTaskForContinuation(principal, request, remoteAgent, revision);
        ContextRecord context = continuation == null
                ? findRequestedContext(principal, request, remoteAgent, revision)
                : requireContext(continuation, principal, request, remoteAgent, revision);
        if (continuation != null && continuation.remoteTaskId() == null) {
            throw new A2aDomainException("A2A_REMOTE_TASK_ID_MISSING",
                    "the local Task has no fixed remote Task id for continuation");
        }
        TargetBinding target = continuation == null
                ? newTarget(remoteAgent, revision, now)
                : existingTarget(request, continuation, principalTrust, revision, now);
        A2aRemoteInterface remoteInterface = target.remoteInterface();
        A2aTrustProfile remoteTrust = target.remoteTrust();
        A2aCredential credential = target.credential();
        A2aRemoteAuthenticationPlanner.Binding authentication = target.authentication();
        EncodedRequest encoded = encode(request, remoteInterface, context, continuation);
        A2aOutboundPolicyAuthorizer.EffectivePolicy policy = policyAuthorizer.authorize(
                request, principal, principalTrust, remoteAgent, revision, remoteInterface,
                remoteTrust, authentication, encoded.requestBody().length, now);
        if (continuation != null) {
            policy = constrainToAcceptedTask(
                    policy, target.execution(), continuation, encoded.requestBody().length);
        }

        if (context == null) {
            context = tasks.saveContext(new ContextRecord(
                    null, opaque("ctx"), OUTBOUND, principal.id(), principal.tenantScope(),
                    null, remoteAgent.id(), revision.id(), null, request.runtimeSessionId(),
                    "ACTIVE", now, policy.retentionExpiresAt(), null, null));
        }
        if (continuation == null
                && tasks.countNonTerminalOutboundTasks(principal.id(), remoteAgent.id())
                >= policy.maxConcurrentTasks()) {
            throw new A2aDomainException("A2A_CONCURRENT_TASK_LIMIT_EXCEEDED",
                    "the effective outbound concurrent Task limit has been reached");
        }

        A2aContentCipher.EncryptedContent encrypted = contentCipher.encrypt(
                encoded.canonicalMessage(), A2aContentBinding.message(
                        OUTBOUND, principal.id(), principal.tenantScope(), request.messageId()));
        TaskRecord task;
        long messageSequence;
        if (continuation == null) {
            String taskId = opaque("task");
            task = tasks.saveTask(new TaskRecord(
                    null, taskId, OUTBOUND, principal.id(), principal.tenantScope(),
                    context.id(), context.contextId(), null, null,
                    remoteAgent.id(), revision.id(), null,
                    request.messageId(), encoded.idempotencySha256(),
                    A2aTaskState.TASK_STATE_SUBMITTED, 0, 2, opaque("outbound"),
                    request.runtimeSessionId(), safeIdentifier(request.traceId()), null,
                    encoded.safeSummary(), null, null, "NONE", null, 0,
                    now, null, null, policy.retentionExpiresAt(),
                    now.plus(Duration.ofMillis(policy.timeoutMs())), null, null));
            tasks.saveEvent(event(task, 1, "TASK_CREATED", null,
                    A2aTaskState.TASK_STATE_SUBMITTED, "LOCAL_AGENT", principal.principalKey(),
                    "TASK", task.taskId(), "Outbound Task accepted", now));
            outboundExecutions.save(new OutboundExecution(
                    null, task.id(), request.bindingId(), request.agentConfigVersionId(),
                    principalTrust.id(), remoteTrust.id(), target.remoteInterface().interfaceKey(),
                    target.securitySchemeKey(), credential == null ? null : credential.id(),
                    policy.protocolSkillId(), policy.classification(),
                    safeListJson(policy.acceptedOutputModes()),
                    historyLength(request.historyLength()), policy.maxRequestBytes(),
                    policy.maxResponseBytes(), policy.maxArtifactBytes(), policy.timeoutMs(),
                    "PENDING", 0, null, null, null, null, null, null, null, null));
            messageSequence = 2;
        } else {
            task = continuation;
            messageSequence = task.lastEventSequence() + 1;
            task = tasks.saveTask(copyTask(task, task.state(), messageSequence,
                    task.remoteTaskId(), task.statusMessageSummary(), null, null,
                    task.cancelPhase(), task.cancelRequestedAt(), task.attemptCount(),
                    task.startedAt(), task.completedAt(),
                    now.plus(Duration.ofMillis(policy.timeoutMs()))));
        }
        tasks.saveMessage(new MessageRecord(
                null, request.messageId(), OUTBOUND, principal.id(), principal.tenantScope(),
                context.id(), task.id(), "ROLE_USER", encrypted.ciphertext(), null,
                encrypted.keyId(), encrypted.nonce(), encoded.messageSha256(),
                encoded.idempotencySha256(), encoded.messageBytes(), policy.classification(),
                encoded.safeSummary(), safeMetadata(policy),
                earlier(task.retentionExpiresAt(), policy.retentionExpiresAt()), null, now));
        tasks.saveEvent(event(task, messageSequence, "MESSAGE_ADDED", null, null,
                "LOCAL_AGENT", principal.principalKey(), "MESSAGE", request.messageId(),
                encoded.safeSummary(), now));
        tasks.touchContext(context.id(), now);
        return PreparedSend.ready(
                opaque("request"), task.id(), task.taskId(), task.executionId(), context.id(),
                context.contextId(), principal, remoteAgent, revision, remoteInterface,
                authentication, credential, encoded, policy,
                safeIdentifier(request.traceId()));
    }

    @Transactional
    public void markDeliveryStarted(PreparedSend prepared) {
        if (prepared == null || prepared.replayResponse() != null) return;
        TaskRecord task = lockPrepared(prepared);
        if (task.state().terminal()) {
            throw new A2aDomainException("A2A_OUTBOUND_TASK_TERMINAL",
                    "the outbound Task became terminal before delivery");
        }
        A2aTaskState next = task.state() == A2aTaskState.TASK_STATE_SUBMITTED
                || task.state() == A2aTaskState.TASK_STATE_INPUT_REQUIRED
                ? A2aTaskState.TASK_STATE_WORKING : task.state();
        A2aTaskLifecycle.requireTransition(task.state(), next);
        LocalDateTime now = now();
        long sequence = task.lastEventSequence() + 1;
        TaskRecord started = tasks.saveTask(copyTask(
                task, next, sequence, task.remoteTaskId(), "Outbound delivery started",
                null, null, task.cancelPhase(), task.cancelRequestedAt(),
                task.attemptCount() + 1, task.startedAt() == null ? now : task.startedAt(),
                null, task.deadlineAt()));
        tasks.saveEvent(event(started, sequence, "OUTBOUND_DELIVERY_STARTED",
                task.state(), next, "SYSTEM", null, "TASK", task.taskId(),
                "Outbound delivery started", now));
    }

    @Transactional
    public PreparedTaskOperation preparePoll(long taskRefId, String workerId) {
        if (taskRefId <= 0 || workerId == null || workerId.isBlank()) {
            throw new A2aDomainException("A2A_OUTBOUND_POLL_INVALID",
                    "a claimed outbound Task and worker id are required");
        }
        TaskRecord task = tasks.lockTaskByRefId(taskRefId)
                .filter(value -> value.direction() == OUTBOUND)
                .orElseThrow(() -> new A2aDomainException("A2A_TASK_NOT_FOUND",
                        "the claimed outbound Task is unavailable"));
        OutboundExecution execution = requireOutboundExecution(task.id(), true);
        if (!workerId.equals(execution.leaseOwner())) {
            throw new A2aDomainException("A2A_OUTBOUND_POLL_LEASE_LOST",
                    "the outbound polling lease is no longer owned by this worker");
        }
        ContextRecord context = requireTaskContext(task);
        if (task.state().terminal()) {
            outboundExecutions.markTerminal(task.id());
            return PreparedTaskOperation.replay(result(task, context, false));
        }
        if (task.deadlineAt() != null && !task.deadlineAt().isAfter(now())) {
            throw new A2aDomainException("A2A_TASK_DEADLINE_EXCEEDED",
                    "the outbound Task deadline has been reached");
        }
        return operation(task, context, execution, "tasks:get", workerId, null, null);
    }

    @Transactional
    public void failClaimedPollPreparation(
            long taskRefId, String workerId, A2aDomainException failure) {
        if (taskRefId <= 0 || workerId == null || workerId.isBlank()) return;
        TaskRecord task = tasks.lockTaskByRefId(taskRefId)
                .filter(value -> value.direction() == OUTBOUND)
                .orElse(null);
        if (task == null) return;
        OutboundExecution execution = requireOutboundExecution(task.id(), true);
        if (!workerId.equals(execution.leaseOwner())) {
            throw new A2aDomainException("A2A_OUTBOUND_POLL_LEASE_LOST",
                    "the outbound polling lease was lost during preparation failure handling");
        }
        if (task.state().terminal()) {
            outboundExecutions.markTerminal(task.id());
            return;
        }
        String code = safeCode(failure == null ? null : failure.code());
        String summary = safe("Outbound tasks:get preparation failed: " + code);
        A2aTaskLifecycle.requireTransition(task.state(), A2aTaskState.TASK_STATE_FAILED);
        LocalDateTime now = now();
        long sequence = task.lastEventSequence() + 1;
        TaskRecord failed = tasks.saveTask(copyTask(
                task, A2aTaskState.TASK_STATE_FAILED, sequence, task.remoteTaskId(),
                summary, code, summary, task.cancelPhase(), task.cancelRequestedAt(),
                task.attemptCount(), task.startedAt(), now, task.deadlineAt()));
        tasks.saveEvent(event(failed, sequence, "REMOTE_TASK_POLL_PREPARATION_FAILED",
                task.state(), A2aTaskState.TASK_STATE_FAILED, "SYSTEM", null,
                "TASK", task.taskId(), summary, now));
        outboundExecutions.markTerminal(task.id());
    }

    @Transactional
    public PreparedTaskOperation prepareCancellation(
            String executionId, String actorType, String actorId) {
        if (executionId == null || executionId.isBlank()) {
            throw new A2aDomainException("A2A_OUTBOUND_CANCEL_INVALID",
                    "outbound executionId is required");
        }
        TaskRecord task = tasks.lockTaskByExecutionId(executionId.trim())
                .filter(value -> value.direction() == OUTBOUND)
                .orElseThrow(() -> new A2aDomainException("A2A_TASK_NOT_FOUND",
                        "the outbound Task is unavailable"));
        OutboundExecution execution = requireOutboundExecution(task.id(), true);
        ContextRecord context = requireTaskContext(task);
        if (task.state().terminal() || !"NONE".equals(task.cancelPhase())) {
            return PreparedTaskOperation.replay(result(task, context, true));
        }
        String workerId = opaque("cancel");
        LocalDateTime now = now();
        if (!outboundExecutions.claimNow(
                task.id(), workerId, now, now.plus(pollLease()))) {
            throw new A2aDomainException("A2A_OUTBOUND_OPERATION_IN_PROGRESS",
                    "another outbound Task operation is currently in progress");
        }
        long sequence = task.lastEventSequence() + 1;
        TaskRecord requested = tasks.saveTask(copyTask(
                task, task.state(), sequence, task.remoteTaskId(), "Cancellation requested",
                task.errorCode(), task.errorSummary(), "REQUESTED", now,
                task.attemptCount(), task.startedAt(), task.completedAt(), task.deadlineAt()));
        tasks.saveEvent(event(requested, sequence, "CANCEL_REQUESTED",
                task.state(), task.state(), safeActorType(actorType), safeIdentifier(actorId),
                "TASK", task.taskId(), "Cancellation requested", now));
        return operation(requested, context, execution, "tasks:cancel", workerId,
                safeActorType(actorType), safeIdentifier(actorId));
    }

    private PreparedTaskOperation operation(
            TaskRecord task,
            ContextRecord context,
            OutboundExecution execution,
            String operation,
            String workerId,
            String actorType,
            String actorId) {
        if (task.remoteTaskId() == null || task.remoteTaskId().isBlank()) {
            throw new A2aDomainException("A2A_REMOTE_TASK_ID_MISSING",
                    "the outbound Task has no fixed remote Task id");
        }
        A2aPrincipal principal = principals.lockActiveById(task.principalId())
                .filter(value -> value.status() == A2aPrincipalStatus.ACTIVE)
                .filter(value -> value.principalType() == A2aPrincipalType.LOCAL_AGENT)
                .orElseThrow(() -> new A2aDomainException("A2A_LOCAL_AGENT_PRINCIPAL_NOT_ACTIVE",
                        "the outbound Task Principal is no longer active"));
        if (principal.trustProfileId() != execution.principalTrustProfileId()) {
            throw new A2aDomainException("A2A_OUTBOUND_PRINCIPAL_POLICY_CHANGED",
                    "the outbound Task Principal no longer matches its acceptance policy");
        }
        A2aRemoteAgent remoteAgent = remoteAgents.findById(required(task.remoteAgentId(), "task.remoteAgentId"))
                .filter(value -> value.status() == A2aRemoteAgentStatus.TRUSTED)
                .orElseThrow(() -> new A2aDomainException("A2A_REMOTE_AGENT_NOT_CALLABLE",
                        "the outbound Task remote Agent is no longer trusted"));
        A2aRemoteAgentRevision revision = remoteAgents.findRevision(
                        remoteAgent.id(), required(task.remoteRevisionId(), "task.remoteRevisionId"))
                .filter(value -> value.reviewStatus() == A2aRemoteRevisionReviewStatus.APPROVED
                        || value.reviewStatus() == A2aRemoteRevisionReviewStatus.SUPERSEDED)
                .orElseThrow(() -> new A2aDomainException("A2A_REMOTE_REVISION_NOT_APPROVED",
                        "the fixed outbound Task revision is no longer trusted"));
        A2aRemoteInterface remoteInterface = revision.requireInterface(execution.remoteInterfaceKey());
        A2aTrustProfile principalTrust = requireTrust(execution.principalTrustProfileId());
        A2aTrustProfile remoteTrust = requireTrust(execution.remoteTrustProfileId());
        A2aCredential credential = execution.credentialId() == null ? null
                : credentials.findById(execution.credentialId()).orElseThrow(() ->
                    new A2aDomainException("A2A_OUTBOUND_CREDENTIAL_NOT_FOUND",
                            "the fixed outbound Task credential is unavailable"));
        A2aRemoteAuthenticationPlanner.Binding authentication = authenticationPlanner.requireBinding(
                revision.card().securitySchemesJson(), revision.card().securityRequirementsJson(),
                execution.remoteSecuritySchemeKey(), credential, now());
        AcceptedPolicy accepted = new AcceptedPolicy(
                execution.protocolSkillId(), execution.contentClassification(),
                stringList(execution.acceptedOutputModesJson()), execution.timeoutMs(),
                execution.maxRequestBytes(), execution.maxResponseBytes(),
                execution.maxArtifactBytes());
        A2aOutboundPolicyAuthorizer.EffectivePolicy policy = policyAuthorizer.authorizeTaskOperation(
                operation, principal, principalTrust, remoteAgent, revision, remoteInterface,
                remoteTrust, authentication, accepted, now());
        return PreparedTaskOperation.ready(
                opaque("request"), task.id(), task.taskId(), task.executionId(),
                context.id(), context.contextId(), task.remoteTaskId(), principal,
                remoteAgent, revision, remoteInterface, authentication, credential,
                execution, policy, operation, workerId, actorType, actorId, task.traceId());
    }

    @Transactional
    public SendResponse complete(
            PreparedSend prepared,
            A2aRemoteProtocolTransport.Response transport,
            DecodedResponse decoded) {
        if (prepared == null || transport == null || decoded == null) {
            throw new A2aDomainException("A2A_OUTBOUND_RESULT_INVALID",
                    "outbound completion requires transport and protocol results");
        }
        TaskRecord current = lockPrepared(prepared);
        if (current.state().terminal()) {
            ContextRecord context = requirePreparedContext(prepared, current);
            return result(current, context, false);
        }
        validateDecodedResources(prepared.policy(), decoded);
        ContextRecord context = requirePreparedContext(prepared, current);
        context = bindRemoteContext(context, decoded.remoteContextId(), now());
        String remoteTaskId = bindRemoteTaskId(current.remoteTaskId(), decoded.remoteTaskId());

        LocalDateTime now = now();
        long sequence = current.lastEventSequence();
        for (DecodedMessage message : decoded.messages()) {
            if (!"ROLE_AGENT".equals(message.role())) continue;
            MessageRecord existing = tasks.findMessage(
                    OUTBOUND, current.principalId(), current.tenantScope(), message.messageId())
                    .orElse(null);
            if (existing != null) {
                if (!existing.payloadSha256().equals(message.payloadSha256())) {
                    throw new A2aDomainException("A2A_INVALID_AGENT_RESPONSE",
                            "remote response reused messageId with conflicting content");
                }
                continue;
            }
            A2aContentCipher.EncryptedContent encrypted = contentCipher.encrypt(
                    message.canonicalJson(), A2aContentBinding.message(
                            OUTBOUND, current.principalId(), current.tenantScope(), message.messageId()));
            tasks.saveMessage(new MessageRecord(
                    null, message.messageId(), OUTBOUND, current.principalId(), current.tenantScope(),
                    context.id(), current.id(), message.role(), encrypted.ciphertext(), null,
                    encrypted.keyId(), encrypted.nonce(), message.payloadSha256(),
                    message.payloadSha256(), message.payloadBytes(), prepared.policy().classification(),
                    safe(message.safeSummary()), "{}", prepared.policy().retentionExpiresAt(), null, now));
            sequence++;
            tasks.saveEvent(event(current, sequence, "MESSAGE_ADDED", null, null,
                    "REMOTE_AGENT", prepared.remoteAgent().remoteAgentKey(), "MESSAGE",
                    message.messageId(), message.safeSummary(), now));
        }
        List<ArtifactRecord> existingArtifacts = tasks.findArtifacts(
                OUTBOUND, current.principalId(), current.tenantScope(), current.id());
        for (DecodedArtifact artifact : decoded.artifacts()) {
            ArtifactRecord existing = existingArtifacts.stream()
                    .filter(value -> value.artifactId().equals(artifact.artifactId()))
                    .findFirst().orElse(null);
            if (existing != null) {
                if (!existing.payloadSha256().equals(artifact.payloadSha256())) {
                    throw new A2aDomainException("A2A_INVALID_AGENT_RESPONSE",
                            "remote response reused artifactId with conflicting content");
                }
                continue;
            }
            A2aContentCipher.EncryptedContent encrypted = contentCipher.encrypt(
                    artifact.canonicalJson(), A2aContentBinding.artifact(
                            OUTBOUND, current.principalId(), current.tenantScope(),
                            current.taskId(), artifact.artifactId()));
            tasks.saveArtifact(new ArtifactRecord(
                    null, current.id(), artifact.artifactId(), artifact.name(), artifact.description(),
                    encrypted.ciphertext(), null, encrypted.keyId(), encrypted.nonce(),
                    artifact.payloadSha256(), artifact.payloadBytes(), artifact.mediaTypesJson(),
                    prepared.policy().classification(), safe(artifact.safeSummary()), 0, true,
                    prepared.policy().retentionExpiresAt(), null, now, now));
            sequence++;
            tasks.saveEvent(event(current, sequence, "ARTIFACT_UPDATED", null, null,
                    "REMOTE_AGENT", prepared.remoteAgent().remoteAgentKey(), "ARTIFACT",
                    artifact.artifactId(), artifact.safeSummary(), now));
        }

        A2aTaskState next = decoded.state() == A2aTaskState.TASK_STATE_SUBMITTED
                ? A2aTaskState.TASK_STATE_WORKING : decoded.state();
        A2aTaskLifecycle.requireTransition(current.state(), next);
        String statusSummary = safe(decoded.safeStatusSummary());
        String remoteErrorCode = switch (next) {
            case TASK_STATE_FAILED -> "A2A_REMOTE_TASK_FAILED";
            case TASK_STATE_REJECTED -> "A2A_REMOTE_TASK_REJECTED";
            default -> null;
        };
        String remoteErrorSummary = remoteErrorCode == null ? null
                : (statusSummary == null ? "Remote Agent returned " + next.name() : statusSummary);
        sequence++;
        TaskRecord updated = tasks.saveTask(copyTask(
                current, next, sequence, remoteTaskId, statusSummary,
                remoteErrorCode, remoteErrorSummary, current.cancelPhase(), current.cancelRequestedAt(),
                current.attemptCount(), current.startedAt() == null ? now : current.startedAt(),
                next.terminal() ? now : null, current.deadlineAt()));
        tasks.saveEvent(event(updated, sequence, "STATE_CHANGED", current.state(), next,
                "REMOTE_AGENT", prepared.remoteAgent().remoteAgentKey(), "TASK",
                updated.taskId(), decoded.safeStatusSummary(), now));
        tasks.touchContext(context.id(), now);
        if (prepared.credential() != null) {
            credentials.markUsed(prepared.credential().id(), now);
        }
        saveTransportAudit(prepared, transport, true, null, decoded.safeStatusSummary(), now);
        updatePollingProjection(updated, now);
        return result(updated, context, false);
    }

    @Transactional
    public SendResponse fail(
            PreparedSend prepared,
            A2aRemoteProtocolTransport.Response transport,
            A2aDomainException failure) {
        TaskRecord current = lockPrepared(prepared);
        ContextRecord context = requirePreparedContext(prepared, current);
        if (current.state().terminal()) return result(current, context, false);
        String code = safeCode(failure == null ? null : failure.code());
        String summary = "A2A_OUTBOUND_DELIVERY_UNCERTAIN".equals(code)
                ? "Remote delivery outcome is uncertain; automatic retry is disabled"
                : "Outbound A2A call failed: " + code;
        A2aTaskLifecycle.requireTransition(current.state(), A2aTaskState.TASK_STATE_FAILED);
        LocalDateTime now = now();
        long sequence = current.lastEventSequence() + 1;
        TaskRecord failed = tasks.saveTask(copyTask(
                current, A2aTaskState.TASK_STATE_FAILED, sequence, current.remoteTaskId(),
                summary, code, summary, current.cancelPhase(), current.cancelRequestedAt(),
                current.attemptCount(), current.startedAt(), now, current.deadlineAt()));
        tasks.saveEvent(event(failed, sequence,
                "A2A_OUTBOUND_DELIVERY_UNCERTAIN".equals(code)
                        ? "OUTBOUND_DELIVERY_UNCERTAIN" : "OUTBOUND_DELIVERY_FAILED",
                current.state(), A2aTaskState.TASK_STATE_FAILED, "SYSTEM", null,
                "TASK", current.taskId(), summary, now));
        saveTransportAudit(prepared, transport, false, code, summary, now);
        outboundExecutions.markTerminal(failed.id());
        return result(failed, context, false);
    }

    @Transactional
    public SendResponse completeTaskOperation(
            PreparedTaskOperation prepared,
            A2aRemoteProtocolTransport.Response transport,
            DecodedResponse decoded,
            byte[] requestBody) {
        if (prepared == null || prepared.replay() || transport == null || decoded == null) {
            throw new A2aDomainException("A2A_OUTBOUND_RESULT_INVALID",
                    "outbound Task completion requires a prepared operation and protocol result");
        }
        TaskRecord current = lockTaskOperation(prepared);
        OutboundExecution execution = lockTaskExecution(prepared, current);
        ContextRecord context = requireTaskContext(current);
        if (current.state().terminal()) {
            outboundExecutions.markTerminal(current.id());
            return result(current, context, false);
        }
        validateDecodedResources(prepared.policy(), decoded);
        context = bindRemoteContext(context, decoded.remoteContextId(), now());
        String remoteTaskId = bindRemoteTaskId(current.remoteTaskId(), decoded.remoteTaskId());
        if (remoteTaskId == null || !remoteTaskId.equals(prepared.remoteTaskId())) {
            throw new A2aDomainException("A2A_INVALID_AGENT_RESPONSE",
                    "remote Task operation changed the fixed Task id");
        }

        LocalDateTime now = now();
        LocalDateTime retention = earlier(current.retentionExpiresAt(),
                prepared.policy().retentionExpiresAt());
        long sequence = current.lastEventSequence();
        for (DecodedMessage message : decoded.messages()) {
            if (!"ROLE_AGENT".equals(message.role())) continue;
            MessageRecord existing = tasks.findMessage(
                    OUTBOUND, current.principalId(), current.tenantScope(), message.messageId())
                    .orElse(null);
            if (existing != null) {
                if (!existing.payloadSha256().equals(message.payloadSha256())) {
                    throw new A2aDomainException("A2A_INVALID_AGENT_RESPONSE",
                            "remote response reused messageId with conflicting content");
                }
                continue;
            }
            A2aContentCipher.EncryptedContent encrypted = contentCipher.encrypt(
                    message.canonicalJson(), A2aContentBinding.message(
                            OUTBOUND, current.principalId(), current.tenantScope(), message.messageId()));
            tasks.saveMessage(new MessageRecord(
                    null, message.messageId(), OUTBOUND, current.principalId(), current.tenantScope(),
                    context.id(), current.id(), message.role(), encrypted.ciphertext(), null,
                    encrypted.keyId(), encrypted.nonce(), message.payloadSha256(),
                    message.payloadSha256(), message.payloadBytes(), prepared.policy().classification(),
                    safe(message.safeSummary()), "{}", retention, null, now));
            sequence++;
            tasks.saveEvent(event(current, sequence, "MESSAGE_ADDED", null, null,
                    "REMOTE_AGENT", prepared.remoteAgent().remoteAgentKey(), "MESSAGE",
                    message.messageId(), message.safeSummary(), now));
        }
        List<ArtifactRecord> existingArtifacts = tasks.findArtifacts(
                OUTBOUND, current.principalId(), current.tenantScope(), current.id());
        for (DecodedArtifact artifact : decoded.artifacts()) {
            ArtifactRecord existing = existingArtifacts.stream()
                    .filter(value -> value.artifactId().equals(artifact.artifactId()))
                    .findFirst().orElse(null);
            if (existing != null) {
                if (!existing.payloadSha256().equals(artifact.payloadSha256())) {
                    throw new A2aDomainException("A2A_INVALID_AGENT_RESPONSE",
                            "remote response reused artifactId with conflicting content");
                }
                continue;
            }
            A2aContentCipher.EncryptedContent encrypted = contentCipher.encrypt(
                    artifact.canonicalJson(), A2aContentBinding.artifact(
                            OUTBOUND, current.principalId(), current.tenantScope(),
                            current.taskId(), artifact.artifactId()));
            tasks.saveArtifact(new ArtifactRecord(
                    null, current.id(), artifact.artifactId(), artifact.name(), artifact.description(),
                    encrypted.ciphertext(), null, encrypted.keyId(), encrypted.nonce(),
                    artifact.payloadSha256(), artifact.payloadBytes(), artifact.mediaTypesJson(),
                    prepared.policy().classification(), safe(artifact.safeSummary()), 0, true,
                    retention, null, now, now));
            sequence++;
            tasks.saveEvent(event(current, sequence, "ARTIFACT_UPDATED", null, null,
                    "REMOTE_AGENT", prepared.remoteAgent().remoteAgentKey(), "ARTIFACT",
                    artifact.artifactId(), artifact.safeSummary(), now));
        }

        A2aTaskState next = decoded.state() == A2aTaskState.TASK_STATE_SUBMITTED
                ? A2aTaskState.TASK_STATE_WORKING : decoded.state();
        A2aTaskLifecycle.requireTransition(current.state(), next);
        String statusSummary = safe(decoded.safeStatusSummary());
        String remoteErrorCode = switch (next) {
            case TASK_STATE_FAILED -> "A2A_REMOTE_TASK_FAILED";
            case TASK_STATE_REJECTED -> "A2A_REMOTE_TASK_REJECTED";
            default -> null;
        };
        String remoteErrorSummary = remoteErrorCode == null ? null
                : (statusSummary == null ? "Remote Agent returned " + next.name() : statusSummary);
        sequence++;
        String cancelPhase = "tasks:cancel".equals(prepared.operation())
                ? "ACCEPTED" : current.cancelPhase();
        TaskRecord updated = tasks.saveTask(copyTask(
                current, next, sequence, remoteTaskId, statusSummary,
                remoteErrorCode, remoteErrorSummary, cancelPhase, current.cancelRequestedAt(),
                current.attemptCount(), current.startedAt() == null ? now : current.startedAt(),
                next.terminal() ? now : null, current.deadlineAt()));
        String eventType = current.state() == next
                ? ("tasks:cancel".equals(prepared.operation())
                    ? "CANCEL_ACCEPTED" : "REMOTE_TASK_POLLED")
                : "STATE_CHANGED";
        tasks.saveEvent(event(updated, sequence, eventType, current.state(), next,
                "REMOTE_AGENT", prepared.remoteAgent().remoteAgentKey(), "TASK",
                updated.taskId(), decoded.safeStatusSummary(), now));
        tasks.touchContext(context.id(), now);
        if (prepared.credential() != null) credentials.markUsed(prepared.credential().id(), now);
        saveTaskOperationAudit(prepared, transport, requestBody, true,
                null, decoded.safeStatusSummary(), now);
        finishTaskOperation(updated, execution, prepared.operation(), prepared.workerId(),
                now, null, null);
        return result(updated, context, false);
    }

    @Transactional
    public SendResponse failTaskOperation(
            PreparedTaskOperation prepared,
            A2aRemoteProtocolTransport.Response transport,
            byte[] requestBody,
            A2aDomainException failure) {
        if (prepared == null || prepared.replay()) {
            throw new A2aDomainException("A2A_OUTBOUND_PREPARATION_INVALID",
                    "outbound Task operation preparation is invalid");
        }
        TaskRecord current = lockTaskOperation(prepared);
        OutboundExecution execution = lockTaskExecution(prepared, current);
        ContextRecord context = requireTaskContext(current);
        if (current.state().terminal()) {
            outboundExecutions.markTerminal(current.id());
            return result(current, context, false);
        }
        String code = safeCode(failure == null ? null : failure.code());
        String summary = safe("Outbound " + prepared.operation() + " failed: " + code);
        LocalDateTime now = now();
        long sequence = current.lastEventSequence() + 1;
        if ("tasks:cancel".equals(prepared.operation())) {
            TaskRecord unknown = tasks.saveTask(copyTask(
                    current, current.state(), sequence, current.remoteTaskId(),
                    "Remote cancellation outcome is unknown", current.errorCode(),
                    current.errorSummary(), "UNKNOWN", current.cancelRequestedAt(),
                    current.attemptCount(), current.startedAt(), current.completedAt(),
                    current.deadlineAt()));
            tasks.saveEvent(event(unknown, sequence, "CANCEL_OUTCOME_UNKNOWN",
                    current.state(), current.state(), "SYSTEM", null, "TASK", current.taskId(),
                    "Remote cancellation outcome is unknown; automatic retry is disabled", now));
            saveTaskOperationAudit(prepared, transport, requestBody, false, code, summary, now);
            finishTaskOperation(unknown, execution, prepared.operation(), prepared.workerId(),
                    now, code, summary);
            return result(unknown, context, false);
        }

        if (!retryablePoll(code) || (current.deadlineAt() != null
                && !current.deadlineAt().isAfter(now.plus(pollDelay(execution.pollAttemptCount() + 1))))) {
            A2aTaskLifecycle.requireTransition(current.state(), A2aTaskState.TASK_STATE_FAILED);
            TaskRecord failed = tasks.saveTask(copyTask(
                    current, A2aTaskState.TASK_STATE_FAILED, sequence, current.remoteTaskId(),
                    summary, code, summary, current.cancelPhase(), current.cancelRequestedAt(),
                    current.attemptCount(), current.startedAt(), now, current.deadlineAt()));
            tasks.saveEvent(event(failed, sequence, "REMOTE_TASK_POLL_FAILED",
                    current.state(), A2aTaskState.TASK_STATE_FAILED, "SYSTEM", null,
                    "TASK", current.taskId(), summary, now));
            saveTaskOperationAudit(prepared, transport, requestBody, false, code, summary, now);
            outboundExecutions.markTerminal(current.id());
            return result(failed, context, false);
        }

        TaskRecord observed = tasks.saveTask(copyTask(
                current, current.state(), sequence, current.remoteTaskId(),
                current.statusMessageSummary(), current.errorCode(), current.errorSummary(),
                current.cancelPhase(), current.cancelRequestedAt(), current.attemptCount(),
                current.startedAt(), current.completedAt(), current.deadlineAt()));
        tasks.saveEvent(event(observed, sequence, "REMOTE_TASK_POLL_RETRY_SCHEDULED",
                current.state(), current.state(), "SYSTEM", null, "TASK", current.taskId(),
                summary, now));
        saveTaskOperationAudit(prepared, transport, requestBody, false, code, summary, now);
        outboundExecutions.schedule(
                current.id(), prepared.workerId(),
                now.plus(pollDelay(execution.pollAttemptCount() + 1)), now,
                execution.pollAttemptCount() + 1, code, summary);
        return result(observed, context, false);
    }

    @Transactional
    public boolean expireOutboundTask(String executionId) {
        TaskRecord task = tasks.lockTaskByExecutionId(executionId)
                .filter(value -> value.direction() == OUTBOUND)
                .orElse(null);
        if (task == null || task.state().terminal()
                || task.deadlineAt() == null || task.deadlineAt().isAfter(now())) {
            return false;
        }
        A2aTaskLifecycle.requireTransition(task.state(), A2aTaskState.TASK_STATE_FAILED);
        LocalDateTime now = now();
        long sequence = task.lastEventSequence() + 1;
        TaskRecord failed = tasks.saveTask(copyTask(
                task, A2aTaskState.TASK_STATE_FAILED, sequence, task.remoteTaskId(),
                "Outbound Task deadline was exceeded", "A2A_TASK_DEADLINE_EXCEEDED",
                "Outbound Task deadline was exceeded before a terminal remote state was observed",
                task.cancelPhase(), task.cancelRequestedAt(), task.attemptCount(),
                task.startedAt(), now, task.deadlineAt()));
        tasks.saveEvent(event(failed, sequence, "TASK_TIMED_OUT",
                task.state(), A2aTaskState.TASK_STATE_FAILED, "SYSTEM", null,
                "TASK", task.taskId(), failed.errorSummary(), now));
        outboundExecutions.markTerminal(task.id());
        return true;
    }

    private void updatePollingProjection(TaskRecord task, LocalDateTime now) {
        if (task.state().terminal()) {
            outboundExecutions.markTerminal(task.id());
        } else if (task.state() == A2aTaskState.TASK_STATE_WORKING) {
            outboundExecutions.activate(task.id(), now.plus(initialPollDelay()));
        } else {
            outboundExecutions.pause(task.id(), "A2A_OUTBOUND_TASK_AWAITING_INPUT",
                    "remote Task is waiting for input or authorization");
        }
    }

    private TaskRecord lockTaskOperation(PreparedTaskOperation prepared) {
        if (prepared.taskRefId() == null || prepared.executionId() == null) {
            throw new A2aDomainException("A2A_OUTBOUND_PREPARATION_INVALID",
                    "outbound Task operation preparation is invalid");
        }
        return tasks.lockTaskByExecutionId(prepared.executionId())
                .filter(value -> Objects.equals(value.id(), prepared.taskRefId()))
                .filter(value -> value.direction() == OUTBOUND)
                .orElseThrow(() -> new A2aDomainException("A2A_TASK_NOT_FOUND",
                        "the prepared outbound Task is unavailable"));
    }

    private OutboundExecution lockTaskExecution(
            PreparedTaskOperation prepared, TaskRecord task) {
        OutboundExecution execution = requireOutboundExecution(task.id(), true);
        if (prepared.workerId() == null || !prepared.workerId().equals(execution.leaseOwner())) {
            throw new A2aDomainException("A2A_OUTBOUND_POLL_LEASE_LOST",
                    "the outbound Task operation lease was lost before acknowledgement");
        }
        return execution;
    }

    private void finishTaskOperation(
            TaskRecord task,
            OutboundExecution execution,
            String operation,
            String workerId,
            LocalDateTime now,
            String errorCode,
            String errorSummary) {
        if (task.state().terminal()) {
            outboundExecutions.markTerminal(task.id());
        } else if (task.state() == A2aTaskState.TASK_STATE_WORKING) {
            if ("tasks:get".equals(operation)) {
                int attempts = execution.pollAttemptCount() + 1;
                outboundExecutions.schedule(task.id(), workerId,
                        now.plus(pollDelay(attempts)), now, attempts,
                        errorCode, errorSummary);
            } else {
                outboundExecutions.activate(task.id(), now.plus(initialPollDelay()));
            }
        } else {
            outboundExecutions.pause(task.id(), "A2A_OUTBOUND_TASK_AWAITING_INPUT",
                    "remote Task is waiting for input or authorization");
        }
    }

    private Duration pollDelay(int attemptCount) {
        Duration initial = initialPollDelay();
        Duration configuredMax = properties.getOutbound().getMaxPollDelay();
        Duration maximum = configuredMax == null || configuredMax.isZero()
                || configuredMax.isNegative() ? Duration.ofSeconds(15) : configuredMax;
        long initialMillis = Math.max(250L, initial.toMillis());
        long maxMillis = Math.max(initialMillis, maximum.toMillis());
        int exponent = Math.max(0, Math.min(attemptCount - 1, 10));
        long multiplier = 1L << exponent;
        long candidate = initialMillis > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE : initialMillis * multiplier;
        return Duration.ofMillis(Math.min(candidate, maxMillis));
    }

    private boolean retryablePoll(String code) {
        return "A2A_OUTBOUND_DELIVERY_UNCERTAIN".equals(code)
                || (code != null && code.matches("A2A_REMOTE_OPERATION_HTTP_5[0-9]{2}"));
    }

    private LocalDateTime earlier(LocalDateTime first, LocalDateTime second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isBefore(second) ? first : second;
    }

    private void saveTaskOperationAudit(
            PreparedTaskOperation prepared,
            A2aRemoteProtocolTransport.Response response,
            byte[] requestBody,
            boolean success,
            String code,
            String summary,
            LocalDateTime now) {
        byte[] body = requestBody == null ? new byte[0] : requestBody;
        byte[] requestEvidence = body.length > 0 ? body
                : (prepared.operation() + "\n" + prepared.remoteTaskId())
                    .getBytes(StandardCharsets.UTF_8);
        Map<String, Object> requestMetadata = new LinkedHashMap<>();
        requestMetadata.put("localTaskId", prepared.taskId());
        requestMetadata.put("requestFingerprintSha256", sha256(requestEvidence));
        requestMetadata.put("pollAttempt", prepared.outboundExecution().pollAttemptCount());
        if (prepared.actorType() != null) requestMetadata.put("actorType", prepared.actorType());
        Map<String, Object> responseMetadata = new LinkedHashMap<>();
        responseMetadata.put("success", success);
        if (summary != null) responseMetadata.put("summary", safe(summary));
        transportAudit.save(new A2aTransportAuditRepository.TransportAuditRecord(
                prepared.requestId(), OUTBOUND.name(), prepared.principal().id(), null,
                prepared.remoteAgent().id(), prepared.taskRefId(),
                prepared.remoteInterface().protocolBinding(),
                prepared.remoteInterface().protocolVersion(), prepared.operation(), success,
                response == null ? null : response.httpStatus(), code,
                response == null ? null : response.latencyMs(), (long) body.length,
                response == null ? null : (long) response.body().length,
                sha256(requestEvidence), response == null ? null : sha256(response.body()),
                safeJson(requestMetadata), safeJson(responseMetadata), null,
                safe(summary), prepared.traceId(), now));
    }

    private Duration initialPollDelay() {
        Duration configured = properties.getOutbound().getInitialPollDelay();
        return configured == null || configured.isZero() || configured.isNegative()
                ? Duration.ofSeconds(2) : configured;
    }

    private Duration pollLease() {
        Duration configured = properties.getOutbound().getPollLease();
        return configured == null || configured.isZero() || configured.isNegative()
                ? Duration.ofSeconds(30) : configured;
    }

    private A2aPrincipal requireLocalAgentPrincipal(SendRequest request) {
        A2aPrincipal principal = principals.lockActiveById(required(request.principalId(), "principalId"))
                .filter(value -> value.status() == A2aPrincipalStatus.ACTIVE)
                .orElseThrow(() -> new A2aDomainException("A2A_LOCAL_AGENT_PRINCIPAL_NOT_ACTIVE",
                        "an active LOCAL_AGENT Principal is required"));
        if (principal.principalType() != A2aPrincipalType.LOCAL_AGENT) {
            throw new A2aDomainException("A2A_LOCAL_AGENT_PRINCIPAL_REQUIRED",
                    "outbound delegation requires a LOCAL_AGENT Principal");
        }
        String boundAgentId = principal.attributes().get("runtimeAgentId");
        if (!request.runtimeAgentId().equals(boundAgentId)) {
            throw new A2aDomainException("A2A_LOCAL_AGENT_PRINCIPAL_MISMATCH",
                    "the Principal is not bound to the requesting Runtime Agent");
        }
        if (principal.credentialId() != null) {
            throw new A2aDomainException("A2A_LOCAL_AGENT_PRINCIPAL_INVALID",
                    "LOCAL_AGENT Principal attestation cannot depend on an inbound credential");
        }
        return principal;
    }

    private A2aRemoteAgent requireRemoteAgent(SendRequest request) {
        A2aRemoteAgent agent = remoteAgents.findById(required(request.remoteAgentId(), "remoteAgentId"))
                .orElseThrow(() -> new A2aDomainException("A2A_REMOTE_AGENT_NOT_FOUND",
                        "the fixed remote Agent is unavailable"));
        if (agent.status() != A2aRemoteAgentStatus.TRUSTED) {
            throw new A2aDomainException("A2A_REMOTE_AGENT_NOT_CALLABLE",
                    "the remote Agent is not trusted");
        }
        return agent;
    }

    private A2aRemoteAgentRevision requireRemoteRevision(
            SendRequest request, A2aRemoteAgent remoteAgent) {
        A2aRemoteAgentRevision revision = remoteAgents.findRevision(
                        remoteAgent.id(), required(request.remoteAgentRevisionId(), "remoteAgentRevisionId"))
                .orElseThrow(() -> new A2aDomainException("A2A_REMOTE_REVISION_NOT_FOUND",
                        "the fixed remote Agent revision is unavailable"));
        if (revision.reviewStatus() != A2aRemoteRevisionReviewStatus.APPROVED
                && revision.reviewStatus() != A2aRemoteRevisionReviewStatus.SUPERSEDED) {
            throw new A2aDomainException("A2A_REMOTE_REVISION_NOT_APPROVED",
                    "the fixed remote Agent revision was never approved");
        }
        return revision;
    }

    private A2aTrustProfile requireTrust(long id) {
        return trustProfiles.findActiveById(id).orElseThrow(() ->
                new A2aDomainException("A2A_TRUST_PROFILE_NOT_ACTIVE",
                        "an effective outbound Trust Profile is no longer active"));
    }

    private TargetBinding newTarget(
            A2aRemoteAgent remoteAgent,
            A2aRemoteAgentRevision revision,
            LocalDateTime now) {
        if (remoteAgent.healthStatus() == A2aRemoteAgentHealth.UNREACHABLE
                || remoteAgent.currentRevisionId() == null
                || !remoteAgent.currentRevisionId().equals(revision.id())
                || revision.reviewStatus() != A2aRemoteRevisionReviewStatus.APPROVED) {
            throw new A2aDomainException("A2A_REMOTE_AGENT_NOT_CALLABLE",
                    "a new outbound Task requires the current approved and reachable remote revision");
        }
        A2aRemoteInterface remoteInterface = revision.requireInterface(
                remoteAgent.preferredInterfaceKey());
        A2aTrustProfile remoteTrust = requireTrust(required(
                remoteAgent.trustProfileId(), "remoteAgent.trustProfileId"));
        A2aCredential credential = remoteAgent.credentialId() == null ? null
                : credentials.findById(remoteAgent.credentialId()).orElseThrow(() ->
                    new A2aDomainException("A2A_OUTBOUND_CREDENTIAL_NOT_FOUND",
                            "the reviewed outbound credential is unavailable"));
        A2aRemoteAuthenticationPlanner.Binding authentication = authenticationPlanner.requireBinding(
                revision.card().securitySchemesJson(), revision.card().securityRequirementsJson(),
                remoteAgent.preferredSecuritySchemeKey(), credential, now);
        return new TargetBinding(remoteInterface, remoteTrust, credential, authentication,
                remoteAgent.preferredSecuritySchemeKey(), null);
    }

    private TargetBinding existingTarget(
            SendRequest request,
            TaskRecord task,
            A2aTrustProfile principalTrust,
            A2aRemoteAgentRevision revision,
            LocalDateTime now) {
        OutboundExecution execution = requireMatchingExecution(request, task, principalTrust);
        A2aRemoteInterface remoteInterface = revision.requireInterface(
                execution.remoteInterfaceKey());
        A2aTrustProfile remoteTrust = requireTrust(execution.remoteTrustProfileId());
        A2aCredential credential = execution.credentialId() == null ? null
                : credentials.findById(execution.credentialId()).orElseThrow(() ->
                    new A2aDomainException("A2A_OUTBOUND_CREDENTIAL_NOT_FOUND",
                            "the fixed outbound Task credential is unavailable"));
        A2aRemoteAuthenticationPlanner.Binding authentication = authenticationPlanner.requireBinding(
                revision.card().securitySchemesJson(), revision.card().securityRequirementsJson(),
                execution.remoteSecuritySchemeKey(), credential, now);
        return new TargetBinding(remoteInterface, remoteTrust, credential, authentication,
                execution.remoteSecuritySchemeKey(), execution);
    }

    private OutboundExecution requireMatchingExecution(
            SendRequest request,
            TaskRecord task,
            A2aTrustProfile principalTrust) {
        OutboundExecution execution = requireOutboundExecution(task.id(), true);
        if (execution.runtimeBindingId() != request.bindingId()
                || execution.agentConfigVersionId() != request.agentConfigVersionId()
                || execution.principalTrustProfileId() != principalTrust.id()) {
            throw new A2aDomainException("A2A_OUTBOUND_TASK_BINDING_MISMATCH",
                    "the continuation does not match the Task acceptance snapshot");
        }
        return execution;
    }

    private A2aOutboundPolicyAuthorizer.EffectivePolicy constrainToAcceptedTask(
            A2aOutboundPolicyAuthorizer.EffectivePolicy current,
            OutboundExecution accepted,
            TaskRecord task,
            long requestBytes) {
        if (accepted == null
                || !Objects.equals(current.protocolSkillId(), accepted.protocolSkillId())
                || !Objects.equals(current.classification(), accepted.contentClassification())) {
            throw new A2aDomainException("A2A_OUTBOUND_TASK_POLICY_MISMATCH",
                    "the continuation changed the accepted AgentSkill or data classification");
        }
        List<String> acceptedOutputs = stringList(accepted.acceptedOutputModesJson());
        for (String output : current.acceptedOutputModes()) {
            if (!supports(acceptedOutputs, output)) {
                throw new A2aDomainException("A2A_OUTBOUND_TASK_POLICY_MISMATCH",
                        "the continuation requested an output mode outside the Task snapshot");
            }
        }
        long maxRequestBytes = Math.min(current.maxRequestBytes(), accepted.maxRequestBytes());
        if (requestBytes <= 0 || requestBytes > maxRequestBytes) {
            throw new A2aDomainException("A2A_OUTBOUND_REQUEST_TOO_LARGE",
                    "the continuation exceeds the accepted Task request limit");
        }
        return new A2aOutboundPolicyAuthorizer.EffectivePolicy(
                current.tenantScope(), current.classification(), current.acceptedOutputModes(),
                current.protocolSkillId(), Math.min(current.timeoutMs(), accepted.timeoutMs()),
                earlier(task.retentionExpiresAt(), current.retentionExpiresAt()),
                maxRequestBytes, Math.min(current.maxResponseBytes(), accepted.maxResponseBytes()),
                Math.min(current.maxArtifactBytes(), accepted.maxArtifactBytes()),
                current.maxConcurrentTasks());
    }

    private TaskRecord requireTaskForContinuation(
            A2aPrincipal principal,
            SendRequest request,
            A2aRemoteAgent agent,
            A2aRemoteAgentRevision revision) {
        TaskRecord task = tasks.lockTask(
                        OUTBOUND, principal.id(), principal.tenantScope(), request.taskId().trim())
                .orElseThrow(() -> new A2aDomainException("A2A_TASK_NOT_FOUND",
                        "the outbound Task is unavailable"));
        if (!Objects.equals(task.remoteAgentId(), agent.id())
                || !Objects.equals(task.remoteRevisionId(), revision.id())) {
            throw new A2aDomainException("A2A_OUTBOUND_TASK_BINDING_MISMATCH",
                    "the Task is not bound to the fixed remote Agent revision");
        }
        if (task.state() != A2aTaskState.TASK_STATE_INPUT_REQUIRED) {
            throw new A2aDomainException("A2A_OUTBOUND_TASK_NOT_CONTINUABLE",
                    "only INPUT_REQUIRED outbound Tasks accept another message");
        }
        return task;
    }

    private ContextRecord findRequestedContext(
            A2aPrincipal principal,
            SendRequest request,
            A2aRemoteAgent agent,
            A2aRemoteAgentRevision revision) {
        if (request.contextId() == null || request.contextId().isBlank()) return null;
        ContextRecord context = tasks.findContext(
                        OUTBOUND, principal.id(), principal.tenantScope(), request.contextId().trim())
                .orElseThrow(() -> new A2aDomainException("A2A_CONTEXT_NOT_FOUND",
                        "the outbound context is unavailable"));
        validateContext(context, request, agent, revision);
        return context;
    }

    private ContextRecord requireContext(
            TaskRecord task,
            A2aPrincipal principal,
            SendRequest request,
            A2aRemoteAgent agent,
            A2aRemoteAgentRevision revision) {
        ContextRecord context = tasks.findContext(
                        OUTBOUND, principal.id(), principal.tenantScope(), task.contextId())
                .filter(value -> value.id() == task.contextRefId())
                .orElseThrow(() -> new A2aDomainException("A2A_CONTEXT_NOT_FOUND",
                        "the outbound Task context is unavailable"));
        validateContext(context, request, agent, revision);
        return context;
    }

    private void validateContext(
            ContextRecord context,
            SendRequest request,
            A2aRemoteAgent agent,
            A2aRemoteAgentRevision revision) {
        if (!"ACTIVE".equals(context.status())
                || !Objects.equals(context.remoteAgentId(), agent.id())
                || !Objects.equals(context.remoteRevisionId(), revision.id())
                || !Objects.equals(context.runtimeSessionId(), request.runtimeSessionId())) {
            throw new A2aDomainException("A2A_OUTBOUND_CONTEXT_BINDING_MISMATCH",
                    "the context is not active or belongs to another fixed delegation binding");
        }
    }

    private void validateRequestedTaskContext(
            SendRequest request, TaskRecord task, ContextRecord context) {
        if (request.taskId() != null && !request.taskId().isBlank()
                && !task.taskId().equals(request.taskId().trim())) {
            throw new A2aDomainException("A2A_MESSAGE_IDEMPOTENCY_CONFLICT",
                    "messageId belongs to a different local Task");
        }
        if (request.contextId() != null && !request.contextId().isBlank()
                && !context.contextId().equals(request.contextId().trim())) {
            throw new A2aDomainException("A2A_MESSAGE_IDEMPOTENCY_CONFLICT",
                    "messageId belongs to a different local context");
        }
    }

    private EncodedRequest encode(
            SendRequest request,
            A2aRemoteInterface remoteInterface,
            ContextRecord context,
            TaskRecord task) {
        return protocolCodec.encodeTextMessage(
                remoteInterface.tenant(), request.messageId(),
                context == null ? null : context.remoteContextId(),
                task == null ? null : task.remoteTaskId(), request.text(),
                request.protocolSkillId(), request.contentClassification(),
                request.acceptedOutputModes(), historyLength(request.historyLength()));
    }

    private ContextRecord bindRemoteContext(
            ContextRecord current, String received, LocalDateTime now) {
        String normalized = blank(received);
        if (normalized == null) return current;
        if (current.remoteContextId() != null
                && !current.remoteContextId().equals(normalized)) {
            throw new A2aDomainException("A2A_INVALID_AGENT_RESPONSE",
                    "remote response changed the fixed context id");
        }
        if (current.remoteContextId() != null) return current;
        return tasks.saveContext(new ContextRecord(
                current.id(), current.contextId(), current.direction(), current.principalId(),
                current.tenantScope(), current.publicationId(), current.remoteAgentId(),
                current.remoteRevisionId(), normalized, current.runtimeSessionId(), current.status(),
                now, current.expiresAt(), current.createdAt(), current.updatedAt()));
    }

    private String bindRemoteTaskId(String current, String received) {
        String normalized = blank(received);
        if (current != null && normalized != null && !current.equals(normalized)) {
            throw new A2aDomainException("A2A_INVALID_AGENT_RESPONSE",
                    "remote response changed the fixed Task id");
        }
        return current == null ? normalized : current;
    }

    private void validateDecodedResources(
            A2aOutboundPolicyAuthorizer.EffectivePolicy policy,
            DecodedResponse decoded) {
        for (DecodedMessage message : decoded.messages()) {
            if (message.payloadBytes() > policy.maxArtifactBytes()
                    || !sha256(message.canonicalJson()).equals(message.payloadSha256())) {
                throw new A2aDomainException("A2A_INVALID_AGENT_RESPONSE",
                        "remote Message failed size or integrity validation");
            }
            if ("ROLE_AGENT".equals(message.role())) {
                requireOutputModes(policy.acceptedOutputModes(), message.mediaTypes());
            }
        }
        for (DecodedArtifact artifact : decoded.artifacts()) {
            if (artifact.payloadBytes() > policy.maxArtifactBytes()
                    || !sha256(artifact.canonicalJson()).equals(artifact.payloadSha256())) {
                throw new A2aDomainException("A2A_INVALID_AGENT_RESPONSE",
                        "remote Artifact failed size or integrity validation");
            }
            requireOutputModes(policy.acceptedOutputModes(), artifact.mediaTypes());
        }
    }

    private void requireOutputModes(List<String> allowed, List<String> received) {
        for (String mediaType : received == null ? List.<String>of() : received) {
            if (!supports(allowed, mediaType)) {
                throw new A2aDomainException("A2A_INVALID_AGENT_RESPONSE",
                        "remote response returned an undeclared output media type");
            }
        }
    }

    private boolean supports(List<String> allowed, String requested) {
        String normalized = requested == null ? "" : requested.trim().toLowerCase(java.util.Locale.ROOT);
        for (String candidate : allowed == null ? List.<String>of() : allowed) {
            String value = candidate == null ? "" : candidate.trim().toLowerCase(java.util.Locale.ROOT);
            if ("*/*".equals(value) || value.equals(normalized)) return true;
            int slash = value.indexOf('/');
            if (slash > 0 && value.endsWith("/*")
                    && normalized.startsWith(value.substring(0, slash + 1))) return true;
        }
        return false;
    }

    private TaskRecord lockPrepared(PreparedSend prepared) {
        if (prepared == null || prepared.executionId() == null) {
            throw new A2aDomainException("A2A_OUTBOUND_PREPARATION_INVALID",
                    "outbound preparation is invalid");
        }
        return tasks.lockTaskByExecutionId(prepared.executionId())
                .filter(value -> Objects.equals(value.id(), prepared.taskRefId()))
                .filter(value -> value.direction() == OUTBOUND)
                .orElseThrow(() -> new A2aDomainException("A2A_TASK_NOT_FOUND",
                        "the prepared outbound Task is unavailable"));
    }

    private ContextRecord requirePreparedContext(PreparedSend prepared, TaskRecord task) {
        return tasks.findContext(
                        OUTBOUND, task.principalId(), task.tenantScope(), prepared.contextId())
                .filter(value -> Objects.equals(value.id(), prepared.contextRefId()))
                .orElseThrow(() -> new A2aDomainException("A2A_CONTEXT_NOT_FOUND",
                        "the prepared outbound context is unavailable"));
    }

    private ContextRecord requireTaskContext(TaskRecord task) {
        return tasks.findContext(
                        OUTBOUND, task.principalId(), task.tenantScope(), task.contextId())
                .filter(value -> Objects.equals(value.id(), task.contextRefId()))
                .orElseThrow(() -> new A2aDomainException("A2A_CONTEXT_NOT_FOUND",
                        "the outbound Task context is unavailable"));
    }

    private OutboundExecution requireOutboundExecution(long taskRefId, boolean locked) {
        return (locked
                ? outboundExecutions.lockByTaskRefId(taskRefId)
                : outboundExecutions.findByTaskRefId(taskRefId))
                .orElseThrow(() -> new A2aDomainException("A2A_OUTBOUND_EXECUTION_NOT_FOUND",
                        "the outbound Task acceptance snapshot is unavailable"));
    }

    private SendResponse result(TaskRecord task, ContextRecord context, boolean replay) {
        List<String> messages = new ArrayList<>();
        for (MessageRecord message : tasks.findMessages(
                OUTBOUND, task.principalId(), task.tenantScope(), task.id(), 100)) {
            if ("ROLE_AGENT".equals(message.role())) {
                String value = decryptMessage(message);
                if (value != null) messages.add(value);
            }
        }
        List<String> artifacts = new ArrayList<>();
        for (ArtifactRecord artifact : tasks.findArtifacts(
                OUTBOUND, task.principalId(), task.tenantScope(), task.id())) {
            String value = decryptArtifact(task, artifact);
            if (value != null) artifacts.add(value);
        }
        return new SendResponse(
                "reachai.a2a-hub.outbound-delegation.v1", task.taskId(), context.contextId(),
                task.remoteTaskId(), context.remoteContextId(), task.state().name(),
                task.statusMessageSummary(), task.errorCode(), replay, messages, artifacts);
    }

    private String decryptMessage(MessageRecord message) {
        if (!retained(message.retentionExpiresAt(), message.contentDeletedAt())
                || message.payloadCiphertext() == null) return null;
        byte[] plaintext = contentCipher.decrypt(new A2aContentCipher.EncryptedContent(
                        message.encryptionKeyId(), message.encryptionNonce(), message.payloadCiphertext()),
                A2aContentBinding.message(
                        OUTBOUND, message.principalId(), message.tenantScope(), message.messageId()));
        try {
            if (!sha256(plaintext).equals(message.payloadSha256())) {
                throw new A2aDomainException("A2A_CONTENT_INTEGRITY_FAILED",
                        "retained outbound Message failed integrity verification");
            }
            return new String(plaintext, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private String decryptArtifact(TaskRecord task, ArtifactRecord artifact) {
        if (!retained(artifact.retentionExpiresAt(), artifact.contentDeletedAt())
                || artifact.payloadCiphertext() == null) return null;
        byte[] plaintext = contentCipher.decrypt(new A2aContentCipher.EncryptedContent(
                        artifact.encryptionKeyId(), artifact.encryptionNonce(), artifact.payloadCiphertext()),
                A2aContentBinding.artifact(
                        OUTBOUND, task.principalId(), task.tenantScope(),
                        task.taskId(), artifact.artifactId()));
        try {
            if (!sha256(plaintext).equals(artifact.payloadSha256())) {
                throw new A2aDomainException("A2A_CONTENT_INTEGRITY_FAILED",
                        "retained outbound Artifact failed integrity verification");
            }
            return new String(plaintext, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private TaskRecord copyTask(
            TaskRecord task,
            A2aTaskState state,
            long sequence,
            String remoteTaskId,
            String summary,
            String errorCode,
            String errorSummary,
            String cancelPhase,
            LocalDateTime cancelRequestedAt,
            int attemptCount,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            LocalDateTime deadlineAt) {
        return new TaskRecord(
                task.id(), task.taskId(), task.direction(), task.principalId(), task.tenantScope(),
                task.contextRefId(), task.contextId(), task.publicationId(), task.publicationRevisionId(),
                task.remoteAgentId(), task.remoteRevisionId(), remoteTaskId,
                task.originMessageId(), task.originPayloadSha256(), state, task.stateVersion(),
                sequence, task.executionId(), task.runtimeRunId(), task.traceId(),
                task.runtimeInteractionId(), summary, errorCode, errorSummary,
                cancelPhase, cancelRequestedAt, attemptCount, task.submittedAt(), startedAt,
                completedAt, task.retentionExpiresAt(), deadlineAt, task.createdAt(), task.updatedAt());
    }

    private TaskEventRecord event(
            TaskRecord task,
            long sequence,
            String type,
            A2aTaskState from,
            A2aTaskState to,
            String actorType,
            String actorId,
            String resourceType,
            String resourceId,
            String summary,
            LocalDateTime now) {
        return new TaskEventRecord(
                null, task.id(), sequence, opaque("evt"), type, from, to, actorType,
                safe(actorId), resourceType, safeIdentifier(resourceId), safe(summary), null,
                null, task.traceId(), now);
    }

    private void saveTransportAudit(
            PreparedSend prepared,
            A2aRemoteProtocolTransport.Response response,
            boolean success,
            String code,
            String summary,
            LocalDateTime now) {
        transportAudit.save(new A2aTransportAuditRepository.TransportAuditRecord(
                prepared.requestId(), OUTBOUND.name(), prepared.principal().id(), null,
                prepared.remoteAgent().id(), prepared.taskRefId(),
                prepared.remoteInterface().protocolBinding(), prepared.remoteInterface().protocolVersion(),
                "message:send", success, response == null ? null : response.httpStatus(),
                code, response == null ? null : response.latencyMs(),
                (long) prepared.encoded().requestBody().length,
                response == null ? null : (long) response.body().length,
                sha256(prepared.encoded().requestBody()),
                response == null ? null : sha256(response.body()),
                safeJson(Map.of(
                        "messageFingerprintSha256", prepared.encoded().idempotencySha256(),
                        "protocolSkillId", prepared.policy().protocolSkillId())),
                safeJson(Map.of("summary", safe(summary), "success", success)),
                null, safe(summary), prepared.traceId(), now));
    }

    private String safeMetadata(A2aOutboundPolicyAuthorizer.EffectivePolicy policy) {
        return safeJson(Map.of(
                "protocolSkillId", policy.protocolSkillId(),
                "contentClassification", policy.classification()));
    }

    private String safeJson(Map<String, ?> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? Map.of() : values);
        } catch (Exception failure) {
            throw new A2aDomainException("A2A_SAFE_METADATA_INVALID",
                    "safe outbound metadata could not be serialized");
        }
    }

    private String safeListJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception failure) {
            throw new A2aDomainException("A2A_SAFE_METADATA_INVALID",
                    "safe outbound list metadata could not be serialized");
        }
    }

    private List<String> stringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> values = objectMapper.readValue(json, STRING_LIST);
            return values == null ? List.of() : values.stream()
                    .filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty())
                    .distinct().toList();
        } catch (Exception failure) {
            throw new A2aDomainException("A2A_OUTBOUND_POLICY_SNAPSHOT_INVALID",
                    "the outbound Task output-mode snapshot is invalid");
        }
    }

    private String safeActorType(String value) {
        String normalized = value == null || value.isBlank()
                ? "SYSTEM" : value.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.matches("[A-Z][A-Z0-9_]{1,31}") ? normalized : "SYSTEM";
    }

    private void requireRequest(SendRequest request) {
        if (request == null || request.bindingId() == null || request.bindingId() <= 0
                || request.agentConfigVersionId() == null || request.agentConfigVersionId() <= 0) {
            throw new A2aDomainException("A2A_OUTBOUND_REQUEST_INVALID",
                    "bindingId and agentConfigVersionId are required");
        }
        if (request.runtimeAgentId() == null
                || !RUNTIME_AGENT_ID.matcher(request.runtimeAgentId()).matches()) {
            throw new A2aDomainException("A2A_RUNTIME_AGENT_ID_INVALID",
                    "runtimeAgentId is invalid");
        }
        requireIdentifier(request.runtimeSessionId(), "runtimeSessionId");
        requireIdentifier(request.messageId(), "messageId");
        optionalIdentifier(request.contextId(), "contextId");
        optionalIdentifier(request.taskId(), "taskId");
        optionalIdentifier(request.traceId(), "traceId");
        if (request.text() == null || request.text().isBlank()) {
            throw new A2aDomainException("A2A_OUTBOUND_MESSAGE_REQUIRED",
                    "outbound text is required");
        }
    }

    private int historyLength(Integer value) {
        int normalized = value == null ? 20 : value;
        if (normalized < 0 || normalized > 100) {
            throw new A2aDomainException("A2A_HISTORY_LENGTH_INVALID",
                    "historyLength must be between 0 and 100");
        }
        return normalized;
    }

    private void requireIdentifier(String value, String field) {
        if (value == null || value.isBlank() || value.trim().length() > 128
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new A2aDomainException("A2A_IDENTIFIER_INVALID",
                    field + " is required and must not exceed 128 characters");
        }
    }

    private void optionalIdentifier(String value, String field) {
        if (value != null && !value.isBlank()) requireIdentifier(value, field);
    }

    private long required(Long value, String field) {
        if (value == null || value <= 0) {
            throw new A2aDomainException("A2A_REFERENCE_INVALID", field + " must be positive");
        }
        return value;
    }

    private boolean retained(LocalDateTime expiresAt, LocalDateTime deletedAt) {
        return deletedAt == null && (expiresAt == null || !expiresAt.isBefore(now()));
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safe(String value) {
        if (value == null) return null;
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    private String safeIdentifier(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    private String safeCode(String value) {
        String normalized = value == null || value.isBlank()
                ? "A2A_OUTBOUND_CALL_FAILED" : value.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.matches("[A-Z0-9_]{3,96}")
                ? normalized : "A2A_OUTBOUND_CALL_FAILED";
    }

    private String opaque(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public record PreparedSend(
            String requestId,
            Long taskRefId,
            String taskId,
            String executionId,
            Long contextRefId,
            String contextId,
            A2aPrincipal principal,
            A2aRemoteAgent remoteAgent,
            A2aRemoteAgentRevision remoteRevision,
            A2aRemoteInterface remoteInterface,
            A2aRemoteAuthenticationPlanner.Binding authentication,
            A2aCredential credential,
            EncodedRequest encoded,
            A2aOutboundPolicyAuthorizer.EffectivePolicy policy,
            String traceId,
            SendResponse replayResponse) {

        static PreparedSend ready(
                String requestId,
                Long taskRefId,
                String taskId,
                String executionId,
                Long contextRefId,
                String contextId,
                A2aPrincipal principal,
                A2aRemoteAgent remoteAgent,
                A2aRemoteAgentRevision remoteRevision,
                A2aRemoteInterface remoteInterface,
                A2aRemoteAuthenticationPlanner.Binding authentication,
                A2aCredential credential,
                EncodedRequest encoded,
                A2aOutboundPolicyAuthorizer.EffectivePolicy policy,
                String traceId) {
            return new PreparedSend(requestId, taskRefId, taskId, executionId,
                    contextRefId, contextId, principal, remoteAgent, remoteRevision,
                    remoteInterface, authentication, credential, encoded, policy, traceId, null);
        }

        static PreparedSend replay(SendResponse response, String executionId) {
            return new PreparedSend(null, null, response.taskId(), executionId,
                    null, response.contextId(), null, null, null, null,
                    null, null, null, null, null, response);
        }

        public boolean replay() {
            return replayResponse != null;
        }
    }

    public record PreparedTaskOperation(
            String requestId,
            Long taskRefId,
            String taskId,
            String executionId,
            Long contextRefId,
            String contextId,
            String remoteTaskId,
            A2aPrincipal principal,
            A2aRemoteAgent remoteAgent,
            A2aRemoteAgentRevision remoteRevision,
            A2aRemoteInterface remoteInterface,
            A2aRemoteAuthenticationPlanner.Binding authentication,
            A2aCredential credential,
            OutboundExecution outboundExecution,
            A2aOutboundPolicyAuthorizer.EffectivePolicy policy,
            String operation,
            String workerId,
            String actorType,
            String actorId,
            String traceId,
            SendResponse replayResponse) {

        static PreparedTaskOperation ready(
                String requestId, Long taskRefId, String taskId, String executionId,
                Long contextRefId, String contextId, String remoteTaskId,
                A2aPrincipal principal, A2aRemoteAgent remoteAgent,
                A2aRemoteAgentRevision remoteRevision, A2aRemoteInterface remoteInterface,
                A2aRemoteAuthenticationPlanner.Binding authentication, A2aCredential credential,
                OutboundExecution outboundExecution,
                A2aOutboundPolicyAuthorizer.EffectivePolicy policy,
                String operation, String workerId, String actorType, String actorId,
                String traceId) {
            return new PreparedTaskOperation(
                    requestId, taskRefId, taskId, executionId, contextRefId, contextId,
                    remoteTaskId, principal, remoteAgent, remoteRevision, remoteInterface,
                    authentication, credential, outboundExecution, policy, operation,
                    workerId, actorType, actorId, traceId, null);
        }

        static PreparedTaskOperation replay(SendResponse response) {
            return new PreparedTaskOperation(
                    null, null, response.taskId(), null, null, response.contextId(),
                    response.remoteTaskId(), null, null, null, null, null, null,
                    null, null, null, null, null, null, null, response);
        }

        public boolean replay() {
            return replayResponse != null;
        }
    }

    private record TargetBinding(
            A2aRemoteInterface remoteInterface,
            A2aTrustProfile remoteTrust,
            A2aCredential credential,
            A2aRemoteAuthenticationPlanner.Binding authentication,
            String securitySchemeKey,
            OutboundExecution execution) {
    }
}

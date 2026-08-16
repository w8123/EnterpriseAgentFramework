package com.enterprise.ai.control.context;

import com.enterprise.ai.control.client.runtime.RuntimeSessionRetentionGateway;
import com.enterprise.ai.control.context.MemoryErasureStore.DomainSpec;
import com.enterprise.ai.control.context.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Durable, fail-closed coordinator for all Agent-memory erasure ownership domains. */
@Service
public class MemoryErasureOrchestrationService {

    public static final String CONFIRMATION = "ERASE_ALL_AGENT_MEMORY_DOMAINS";
    public static final String CONTROL_PERSONAL_MEMORY = "CONTROL_PERSONAL_MEMORY";
    public static final String CONTROL_CANDIDATE_OUTBOX = "CONTROL_CANDIDATE_OUTBOX";
    public static final String KNOWLEDGE_PERSONAL_PROJECTION = "KNOWLEDGE_PERSONAL_PROJECTION";
    public static final String RUNTIME_SESSION_STATE = "RUNTIME_SESSION_STATE";
    public static final String RUNTIME_CONVERSATION_LEDGER = "RUNTIME_CONVERSATION_LEDGER";
    public static final String RUNOPS_TRACE_INTERACTION = "RUNOPS_TRACE_INTERACTION";
    public static final String BUSINESS_INDEX = "BUSINESS_INDEX";
    public static final String BUSINESS_SOURCE_SYSTEM = "BUSINESS_SOURCE_SYSTEM";
    public static final String BACKUP_EXPIRY_AND_RESTORE = "BACKUP_EXPIRY_AND_RESTORE";

    private static final Set<String> MANUAL_RESULT_CODES = Set.of(
            "ERASED", "NOT_APPLICABLE", "RETAINED_LEGAL");
    private static final Set<String> AUTOMATED_DOMAINS = Set.of(
            CONTROL_PERSONAL_MEMORY,
            CONTROL_CANDIDATE_OUTBOX,
            KNOWLEDGE_PERSONAL_PROJECTION,
            RUNTIME_SESSION_STATE,
            RUNTIME_CONVERSATION_LEDGER);
    private static final List<DomainSpec> DOMAIN_SPECS = List.of(
            new DomainSpec(CONTROL_PERSONAL_MEMORY, "reachai-control-service",
                    "AUTOMATED", "PENDING"),
            new DomainSpec(CONTROL_CANDIDATE_OUTBOX, "reachai-control-service",
                    "AUTOMATED", "PENDING"),
            new DomainSpec(KNOWLEDGE_PERSONAL_PROJECTION, "reachai-knowledge-service",
                    "AUTOMATED", "PENDING"),
            new DomainSpec(RUNTIME_SESSION_STATE, "reachai-runtime-service",
                    "AUTOMATED", "PENDING"),
            new DomainSpec(RUNTIME_CONVERSATION_LEDGER, "reachai-runtime-service",
                    "AUTOMATED", "PENDING"),
            new DomainSpec(RUNOPS_TRACE_INTERACTION, "domain-owner-review",
                    "MANUAL_EVIDENCE", "WAITING_EVIDENCE"),
            new DomainSpec(BUSINESS_INDEX, "reachai-knowledge-service",
                    "MANUAL_EVIDENCE", "WAITING_EVIDENCE"),
            new DomainSpec(BUSINESS_SOURCE_SYSTEM, "business-data-owner",
                    "MANUAL_EVIDENCE", "WAITING_EVIDENCE"),
            new DomainSpec(BACKUP_EXPIRY_AND_RESTORE, "infrastructure-dba",
                    "MANUAL_EVIDENCE", "WAITING_EVIDENCE"));
    private static final Map<String, String> EXPECTED_DOMAIN_MODES = DOMAIN_SPECS.stream()
            .collect(Collectors.toUnmodifiableMap(
                    DomainSpec::domainCode, DomainSpec::executionMode));

    private final MemoryErasureStore store;
    private final PersonalMemoryService personalMemoryService;
    private final ContextMemoryOutboxMapper outboxMapper;
    private final PersonalMemoryKnowledgeErasureClient knowledgeClient;
    private final RuntimeSessionRetentionGateway runtimeGateway;
    private final PersonalMemoryErasureIdentity identity;

    public MemoryErasureOrchestrationService(
            MemoryErasureStore store,
            PersonalMemoryService personalMemoryService,
            ContextMemoryOutboxMapper outboxMapper,
            PersonalMemoryKnowledgeErasureClient knowledgeClient,
            RuntimeSessionRetentionGateway runtimeGateway,
            PersonalMemoryErasureIdentity identity) {
        this.store = store;
        this.personalMemoryService = personalMemoryService;
        this.outboxMapper = outboxMapper;
        this.knowledgeClient = knowledgeClient;
        this.runtimeGateway = runtimeGateway;
        this.identity = identity;
    }

    public RequestView create(CreateCommand command, String actorId) {
        if (command == null || !CONFIRMATION.equals(command.confirmation())) {
            throw badRequest("explicit cross-domain memory erasure confirmation is required");
        }
        String tenant = tenantId(command.tenantId());
        String runtimeUserId = opaqueId(command.runtimeUserId(), "runtimeUserId", 128);
        String clientRequestId = machineId(command.clientRequestId(), "clientRequestId", 64);
        String reasonCode = reasonCode(command.reasonCode());
        String referenceId = machineId(command.referenceId(), "referenceId", 128);
        String normalizedActor = opaqueId(actorId, "actorId", 128);
        String targetHash = identity.hash(tenant, runtimeUserId);
        LocalDateTime now = LocalDateTime.now();
        MemoryErasureRequestEntity request = new MemoryErasureRequestEntity();
        request.setRequestId(UUID.randomUUID().toString());
        request.setClientRequestId(clientRequestId);
        request.setTenantId(tenant);
        request.setRuntimeUserId(runtimeUserId);
        request.setRuntimeUserHash(targetHash);
        request.setReasonCode(reasonCode);
        request.setReferenceId(referenceId);
        request.setStatus("REQUESTED");
        request.setAttemptCount(0);
        request.setNextAttemptAt(now);
        request.setRequestedByHash(identity.hash(tenant, "actor:" + normalizedActor));
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        MemoryErasureStore.CreateResult result = store.createOrGet(request, DOMAIN_SPECS);
        MemoryErasureRequestEntity persisted = result.request();
        if (!targetHash.equals(persisted.getRuntimeUserHash())
                || !reasonCode.equals(persisted.getReasonCode())
                || !referenceId.equals(persisted.getReferenceId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "clientRequestId already belongs to a different erasure request");
        }
        return view(persisted, result.created());
    }

    public RequestView get(String requestId) {
        return view(requireRequest(requestId), false);
    }

    public RequestView attest(String requestId,
                              String domainCode,
                              EvidenceCommand command) {
        MemoryErasureRequestEntity request = requireRequest(requestId);
        if (command == null) {
            throw badRequest("memory erasure evidence is required");
        }
        if (request.getAutomatedCompletedAt() == null
                || !("ACTION_REQUIRED".equals(request.getStatus())
                || request.getStatus().startsWith("COMPLETED"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "automated memory erasure domains must complete before manual evidence");
        }
        String domain = machineId(domainCode, "domainCode", 64).toUpperCase(Locale.ROOT);
        String resultCode = machineId(command.resultCode(), "resultCode", 64)
                .toUpperCase(Locale.ROOT);
        if (!MANUAL_RESULT_CODES.contains(resultCode)) {
            throw badRequest("unsupported manual memory erasure resultCode");
        }
        String evidence = machineId(command.evidenceReference(), "evidenceReference", 128);
        MemoryErasureDomainEntity target = requireManualDomain(request.getId(), domain);
        if (completed(target)) {
            if (resultCode.equals(target.getResultCode())
                    && evidence.equals(target.getEvidenceReference())) {
                refreshAfterEvidence(request.getId());
                return get(requestId);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "manual memory erasure evidence is immutable after completion");
        }
        try {
            store.attestDomain(request.getId(), domain, resultCode, evidence, LocalDateTime.now());
        } catch (IllegalStateException concurrentAttestation) {
            MemoryErasureDomainEntity persisted = requireManualDomain(request.getId(), domain);
            if (!completed(persisted)
                    || !resultCode.equals(persisted.getResultCode())
                    || !evidence.equals(persisted.getEvidenceReference())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "manual memory erasure evidence was completed concurrently");
            }
        }
        refreshAfterEvidence(request.getId());
        return get(requestId);
    }

    public RequestView retry(String requestId) {
        MemoryErasureRequestEntity request = requireRequest(requestId);
        store.resetForRetry(request.getId(), LocalDateTime.now());
        return get(requestId);
    }

    public ProcessingResult processDue(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        LocalDateTime now = LocalDateTime.now();
        List<Long> ids = store.dueRequestIds(now, limit);
        int claimed = 0;
        int completed = 0;
        int notCompleted = 0;
        for (Long id : ids) {
            String leaseOwner = UUID.randomUUID().toString();
            MemoryErasureRequestEntity request = store.claim(id, leaseOwner, now, 300);
            if (request == null) {
                continue;
            }
            claimed++;
            try {
                String status = processClaimed(request, leaseOwner);
                if (status.startsWith("COMPLETED")) completed++;
                else notCompleted++;
            } catch (RuntimeException failure) {
                notCompleted++;
                finishUnexpectedFailure(request, leaseOwner);
            }
        }
        return new ProcessingResult(ids.size(), claimed, completed, notCompleted);
    }

    private String processClaimed(MemoryErasureRequestEntity request, String leaseOwner) {
        if (!StringUtils.hasText(request.getRuntimeUserId())) {
            throw new IllegalStateException("memory erasure target was scrubbed before automation completed");
        }
        String tenant = request.getTenantId();
        String user = request.getRuntimeUserId();
        List<MemoryErasureDomainEntity> initialDomains = store.domains(request.getId());
        requireCompleteDomainInventory(initialDomains);
        Map<String, MemoryErasureDomainEntity> domains = domainMap(initialDomains);
        runControlErasure(request, tenant, user, domains.get(CONTROL_PERSONAL_MEMORY));
        domains = domainMap(request.getId());
        runOutboxProof(request, domains);
        domains = domainMap(request.getId());
        runKnowledgeProof(request, tenant, user, domains);
        runRuntimeErasure(request, tenant, user, domains);
        return finishFromDomains(request, leaseOwner);
    }

    private void runControlErasure(MemoryErasureRequestEntity request,
                                   String tenant,
                                   String user,
                                   MemoryErasureDomainEntity domain) {
        if (completed(domain)) return;
        try {
            PersonalMemoryService.EraseAllView result =
                    personalMemoryService.eraseAllForOrchestration(
                            new PersonalMemoryPrincipal(tenant, user, null, null, null),
                            new PersonalMemoryService.EraseAllCommand(
                                    PersonalMemoryService.ERASE_ALL_CONFIRMATION,
                                    request.getReasonCode(), null, null, null),
                            request.getRequestId());
            store.updateDomain(request.getId(), CONTROL_PERSONAL_MEMORY,
                    "COMPLETED", result.idempotent() ? "ALREADY_ERASED" : "ERASED",
                    (long) result.memoriesErased(), null, null, LocalDateTime.now());
            store.updateDomain(request.getId(), CONTROL_CANDIDATE_OUTBOX,
                    "WAITING_DEPENDENCY", "CANONICAL_ERASED_DELIVERY_PENDING",
                    (long) result.candidatesErased(), null, null, LocalDateTime.now());
        } catch (RuntimeException failure) {
            store.updateDomain(request.getId(), CONTROL_PERSONAL_MEMORY,
                    "WAITING_DEPENDENCY", null, null,
                    "CONTROL_ERASE_FAILED", null, LocalDateTime.now());
        }
    }

    private void runOutboxProof(MemoryErasureRequestEntity request,
                                Map<String, MemoryErasureDomainEntity> domains) {
        if (!completed(domains.get(CONTROL_PERSONAL_MEMORY))
                || completed(domains.get(CONTROL_CANDIDATE_OUTBOX))) return;
        try {
            PersonalMemoryErasureOutboxStatusRow status =
                    outboxMapper.selectErasureDeliveryStatus(request.getRequestId());
            long total = count(status == null ? null : status.getTotalCount());
            long pending = count(status == null ? null : status.getPendingCount());
            long dead = count(status == null ? null : status.getDeadCount());
            long other = count(status == null ? null : status.getOtherCount());
            long terminal = count(status == null ? null : status.getPublishedCount())
                    + count(status == null ? null : status.getSupersededCount());
            if (dead > 0 || other > 0) {
                store.updateDomain(request.getId(), CONTROL_CANDIDATE_OUTBOX,
                        "FAILED", null, total,
                        dead > 0 ? "OUTBOX_DEAD" : "OUTBOX_UNKNOWN_STATUS",
                        null, LocalDateTime.now());
            } else if (pending > 0 || terminal != total) {
                store.updateDomain(request.getId(), CONTROL_CANDIDATE_OUTBOX,
                        "WAITING_DEPENDENCY", "DELIVERY_PENDING", total,
                        null, null, LocalDateTime.now());
            } else {
                store.updateDomain(request.getId(), CONTROL_CANDIDATE_OUTBOX,
                        "COMPLETED", total == 0 ? "NO_PROJECTION_EVENTS" : "DELIVERED",
                        total, null, null, LocalDateTime.now());
            }
        } catch (RuntimeException failure) {
            store.updateDomain(request.getId(), CONTROL_CANDIDATE_OUTBOX,
                    "WAITING_DEPENDENCY", null, null,
                    "OUTBOX_PROOF_FAILED", null, LocalDateTime.now());
        }
    }

    private void runKnowledgeProof(MemoryErasureRequestEntity request,
                                   String tenant,
                                   String user,
                                   Map<String, MemoryErasureDomainEntity> domains) {
        if (!completed(domains.get(CONTROL_CANDIDATE_OUTBOX))
                || completed(domains.get(KNOWLEDGE_PERSONAL_PROJECTION))) return;
        try {
            PersonalMemoryKnowledgeErasureClient.OwnerProjectionStatus status =
                    knowledgeClient.status(tenant, user);
            String expectedHash = identity.hash(tenant, user);
            if (!expectedHash.equals(status.runtimeUserHash())) {
                throw new IllegalStateException("Knowledge owner proof identity mismatch");
            }
            if (status.projectionErased()) {
                store.updateDomain(request.getId(), KNOWLEDGE_PERSONAL_PROJECTION,
                        "COMPLETED", "PROJECTION_ERASED", status.deletedCount(),
                        null, null, LocalDateTime.now());
            } else {
                store.updateDomain(request.getId(), KNOWLEDGE_PERSONAL_PROJECTION,
                        "WAITING_DEPENDENCY", "PROJECTION_NOT_ERASED", status.activeCount(),
                        status.unsafeDeletedVectorCount() > 0
                                ? "UNSAFE_DELETED_VECTOR" : null,
                        null, LocalDateTime.now());
            }
        } catch (RuntimeException failure) {
            store.updateDomain(request.getId(), KNOWLEDGE_PERSONAL_PROJECTION,
                    "WAITING_DEPENDENCY", null, null,
                    "KNOWLEDGE_PROOF_FAILED", null, LocalDateTime.now());
        }
    }

    private void runRuntimeErasure(MemoryErasureRequestEntity request,
                                   String tenant,
                                   String user,
                                   Map<String, MemoryErasureDomainEntity> domains) {
        if (completed(domains.get(RUNTIME_SESSION_STATE))
                && completed(domains.get(RUNTIME_CONVERSATION_LEDGER))) return;
        try {
            ResponseEntity<Map<String, Object>> response = runtimeGateway.eraseOwner(
                    tenant, user, request.getReasonCode(), request.getReferenceId(), 100,
                    request.getRequestedByHash());
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("Runtime owner erasure returned a non-success status");
            }
            Map<String, Object> body = response.getBody();
            boolean complete = booleanValue(body, "complete");
            long remaining = longValue(body, "remaining");
            long erased = longValue(body, "erased");
            long legalHold = longValue(body, "legalHoldBlocked");
            long failed = longValue(body, "failed");
            long cumulativeErased = Math.max(
                    count(domains.get(RUNTIME_SESSION_STATE) == null
                            ? null : domains.get(RUNTIME_SESSION_STATE).getAffectedCount()),
                    count(domains.get(RUNTIME_CONVERSATION_LEDGER) == null
                            ? null : domains.get(RUNTIME_CONVERSATION_LEDGER).getAffectedCount()))
                    + erased;
            String status;
            String resultCode;
            String failureCode = null;
            if (complete && remaining == 0) {
                status = "COMPLETED";
                resultCode = "RUNTIME_ERASED";
            } else if (legalHold > 0) {
                status = "BLOCKED_LEGAL_HOLD";
                resultCode = "LEGAL_HOLD";
                failureCode = "RUNTIME_SESSION_LEGAL_HOLD";
            } else {
                status = "WAITING_DEPENDENCY";
                resultCode = "RUNTIME_RETRY_REQUIRED";
                if (failed > 0) failureCode = "RUNTIME_ERASE_FAILED";
            }
            LocalDateTime now = LocalDateTime.now();
            store.updateDomain(request.getId(), RUNTIME_SESSION_STATE,
                    status, resultCode, cumulativeErased, failureCode, null, now);
            store.updateDomain(request.getId(), RUNTIME_CONVERSATION_LEDGER,
                    status, resultCode, cumulativeErased, failureCode, null, now);
        } catch (RuntimeException failure) {
            LocalDateTime now = LocalDateTime.now();
            store.updateDomain(request.getId(), RUNTIME_SESSION_STATE,
                    "WAITING_DEPENDENCY", null, null,
                    "RUNTIME_ERASE_CALL_FAILED", null, now);
            store.updateDomain(request.getId(), RUNTIME_CONVERSATION_LEDGER,
                    "WAITING_DEPENDENCY", null, null,
                    "RUNTIME_ERASE_CALL_FAILED", null, now);
        }
    }

    private String finishFromDomains(MemoryErasureRequestEntity request, String leaseOwner) {
        List<MemoryErasureDomainEntity> domains = store.domains(request.getId());
        if (!completeDomainInventory(domains)) {
            store.finishAttempt(request.getId(), leaseOwner, "FAILED", null,
                    "DOMAIN_INVENTORY_INCOMPLETE", false, false, false,
                    LocalDateTime.now());
            return "FAILED";
        }
        boolean automatedComplete = domains.stream()
                .filter(domain -> AUTOMATED_DOMAINS.contains(domain.getDomainCode()))
                .allMatch(MemoryErasureOrchestrationService::completed);
        boolean allComplete = domains.stream().allMatch(MemoryErasureOrchestrationService::completed);
        boolean retained = domains.stream().anyMatch(
                domain -> "RETAINED_LEGAL".equals(domain.getResultCode()));
        boolean legalHold = domains.stream().anyMatch(
                domain -> "BLOCKED_LEGAL_HOLD".equals(domain.getStatus()));
        boolean hardFailure = domains.stream().anyMatch(domain -> "FAILED".equals(domain.getStatus()));
        String status;
        LocalDateTime nextAttempt = null;
        String failureCode = null;
        if (allComplete) {
            status = retained ? "COMPLETED_WITH_RETENTION" : "COMPLETED";
        } else if (legalHold) {
            status = "BLOCKED_LEGAL_HOLD";
            failureCode = "RUNTIME_SESSION_LEGAL_HOLD";
        } else if (hardFailure || value(request.getAttemptCount()) >= 100) {
            status = "FAILED";
            failureCode = hardFailure ? "DOMAIN_FAILED" : "MAX_ATTEMPTS_EXCEEDED";
        } else if (automatedComplete) {
            status = "ACTION_REQUIRED";
        } else {
            status = "RETRY";
            nextAttempt = LocalDateTime.now().plusSeconds(retryDelay(value(request.getAttemptCount())));
        }
        LocalDateTime now = LocalDateTime.now();
        store.finishAttempt(request.getId(), leaseOwner, status, nextAttempt, failureCode,
                automatedComplete, automatedComplete, allComplete, now);
        return status;
    }

    private void finishUnexpectedFailure(MemoryErasureRequestEntity request, String leaseOwner) {
        try {
            boolean exhausted = value(request.getAttemptCount()) >= 100;
            store.finishAttempt(request.getId(), leaseOwner,
                    exhausted ? "FAILED" : "RETRY",
                    exhausted ? null : LocalDateTime.now().plusSeconds(
                            retryDelay(value(request.getAttemptCount()))),
                    "ORCHESTRATION_FAILED", false, false, false, LocalDateTime.now());
        } catch (RuntimeException ignored) {
            // The lease expiry is the final recovery path; never log request or owner identity.
        }
    }

    private void refreshAfterEvidence(Long requestId) {
        List<MemoryErasureDomainEntity> domains = store.domains(requestId);
        requireCompleteDomainInventory(domains);
        boolean allComplete = domains.stream().allMatch(MemoryErasureOrchestrationService::completed);
        if (!allComplete) return;
        boolean retained = domains.stream().anyMatch(
                domain -> "RETAINED_LEGAL".equals(domain.getResultCode()));
        store.updateRequestStatus(requestId,
                retained ? "COMPLETED_WITH_RETENTION" : "COMPLETED",
                true, LocalDateTime.now());
    }

    private RequestView view(MemoryErasureRequestEntity request, boolean created) {
        List<DomainView> domains = store.domains(request.getId()).stream()
                .map(domain -> new DomainView(
                        domain.getDomainCode(), domain.getOwnerService(), domain.getExecutionMode(),
                        domain.getStatus(), domain.getResultCode(), domain.getAffectedCount(),
                        domain.getEvidenceReference(), value(domain.getAttemptCount()),
                        domain.getLastFailureCode(), domain.getCompletedAt(), domain.getUpdatedAt()))
                .toList();
        return new RequestView(
                request.getRequestId(), request.getClientRequestId(), request.getTenantId(),
                request.getRuntimeUserHash(), request.getStatus(), request.getReasonCode(),
                request.getReferenceId(), value(request.getAttemptCount()),
                request.getLastFailureCode(), request.getAutomatedCompletedAt(),
                request.getCompletedAt(), request.getCreatedAt(), request.getUpdatedAt(),
                created, domains);
    }

    private MemoryErasureRequestEntity requireRequest(String requestId) {
        String normalized = machineId(requestId, "requestId", 64);
        MemoryErasureRequestEntity request = store.findByRequestId(normalized);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "memory erasure request was not found");
        }
        return request;
    }

    private Map<String, MemoryErasureDomainEntity> domainMap(Long requestId) {
        return domainMap(store.domains(requestId));
    }

    private MemoryErasureDomainEntity requireManualDomain(Long requestId, String domainCode) {
        MemoryErasureDomainEntity domain = store.domains(requestId).stream()
                .filter(value -> domainCode.equals(value.getDomainCode()))
                .findFirst()
                .orElseThrow(() -> badRequest("manual memory erasure domain was not found"));
        if (!"MANUAL_EVIDENCE".equals(domain.getExecutionMode())) {
            throw badRequest("automated memory erasure domains do not accept manual evidence");
        }
        return domain;
    }

    private Map<String, MemoryErasureDomainEntity> domainMap(
            List<MemoryErasureDomainEntity> domains) {
        return domains.stream().collect(Collectors.toMap(
                MemoryErasureDomainEntity::getDomainCode,
                Function.identity(),
                (left, right) -> left,
                LinkedHashMap::new));
    }

    private static void requireCompleteDomainInventory(
            List<MemoryErasureDomainEntity> domains) {
        if (!completeDomainInventory(domains)) {
            throw new IllegalStateException("memory erasure domain inventory is incomplete");
        }
    }

    private static boolean completeDomainInventory(
            List<MemoryErasureDomainEntity> domains) {
        if (domains == null || domains.size() != EXPECTED_DOMAIN_MODES.size()) {
            return false;
        }
        Map<String, String> actual = new LinkedHashMap<>();
        for (MemoryErasureDomainEntity domain : domains) {
            if (domain == null || domain.getDomainCode() == null
                    || domain.getExecutionMode() == null
                    || actual.put(domain.getDomainCode(), domain.getExecutionMode()) != null) {
                return false;
            }
        }
        return EXPECTED_DOMAIN_MODES.equals(actual);
    }

    private static boolean completed(MemoryErasureDomainEntity domain) {
        return domain != null && "COMPLETED".equals(domain.getStatus());
    }

    private static long count(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private static int value(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static long retryDelay(int attempt) {
        return Math.min(300L, 5L * (1L << Math.min(6, Math.max(0, attempt - 1))));
    }

    private static boolean booleanValue(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof Boolean result) return result;
        if (value instanceof String text
                && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
            return Boolean.parseBoolean(text);
        }
        throw new IllegalStateException("Runtime owner erasure response is missing " + key);
    }

    private static long longValue(Map<String, Object> body, String key) {
        Object value = body.get(key);
        long result;
        if (value instanceof Number number) result = number.longValue();
        else try { result = Long.parseLong(String.valueOf(value)); }
        catch (Exception failure) {
            throw new IllegalStateException("Runtime owner erasure response is missing " + key);
        }
        if (result < 0) {
            throw new IllegalStateException("Runtime owner erasure response contains a negative count");
        }
        return result;
    }

    private static String tenantId(String value) {
        String normalized = machineId(value, "tenantId", 96);
        if (!normalized.matches("[A-Za-z0-9._:-]+")) {
            throw badRequest("tenantId contains unsupported characters");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String reasonCode(String value) {
        String normalized = machineId(value, "reasonCode", 64).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_.:-]*")) {
            throw badRequest("reasonCode must be machine-readable");
        }
        return normalized;
    }

    private static String opaqueId(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) throw badRequest(field + " is required");
        String normalized = value.trim();
        if (normalized.length() > maxLength
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw badRequest(field + " is invalid");
        }
        return normalized;
    }

    private static String machineId(String value, String field, int maxLength) {
        String normalized = opaqueId(value, field, maxLength);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            throw badRequest(field + " must be machine-readable");
        }
        return normalized;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record CreateCommand(String confirmation,
                                String clientRequestId,
                                String tenantId,
                                String runtimeUserId,
                                String reasonCode,
                                String referenceId) {
    }

    public record EvidenceCommand(String resultCode, String evidenceReference) {
    }

    public record DomainView(String domainCode,
                             String ownerService,
                             String executionMode,
                             String status,
                             String resultCode,
                             Long affectedCount,
                             String evidenceReference,
                             int attemptCount,
                             String lastFailureCode,
                             LocalDateTime completedAt,
                             LocalDateTime updatedAt) {
    }

    public record RequestView(String requestId,
                              String clientRequestId,
                              String tenantId,
                              String runtimeUserHash,
                              String status,
                              String reasonCode,
                              String referenceId,
                              int attemptCount,
                              String lastFailureCode,
                              LocalDateTime automatedCompletedAt,
                              LocalDateTime completedAt,
                              LocalDateTime createdAt,
                              LocalDateTime updatedAt,
                              boolean created,
                              List<DomainView> domains) {
    }

    public record ProcessingResult(int candidates, int claimed, int completed, int notCompleted) {
    }
}

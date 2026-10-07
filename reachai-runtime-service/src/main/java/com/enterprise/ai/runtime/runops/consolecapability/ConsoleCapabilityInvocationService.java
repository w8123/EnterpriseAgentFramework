package com.enterprise.ai.runtime.runops.consolecapability;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.runops.RuntimeRunEntity;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceRootService;
import com.enterprise.ai.runtime.trace.WorkflowTraceSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Independent Console trial-call root. The only retry policy is read-back by invocationId:
 * once the dispatch claim is durable, this service never sends the same operation again.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConsoleCapabilityInvocationService {

    private static final String IDENTITY_MODE = "PROJECT_CREDENTIAL_NO_BUSINESS_IDENTITY";
    private static final int DEFAULT_RESULT_MAX_BYTES = 64 * 1024;
    private static final int DEFAULT_RESULT_TTL_HOURS = 24;
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Set<String> SECRET_KEY_PARTS = Set.of(
            "password", "secret", "token", "credential", "authorization", "apikey", "api_key", "cookie");

    private final ConsoleCapabilityInvocationMapper invocationMapper;
    private final RuntimeRunMapper runMapper;
    private final RuntimeTraceRootService traceRoots;
    private final RuntimeCapabilityCatalogClient capabilityCatalog;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    @Value("${reachai.runtime.console-capability.result-max-bytes:65536}")
    private int configuredResultMaxBytes = DEFAULT_RESULT_MAX_BYTES;
    @Value("${reachai.runtime.console-capability.result-ttl-hours:24}")
    private int configuredResultTtlHours = DEFAULT_RESULT_TTL_HOURS;

    public ConsoleCapabilityInvocationContracts.InvocationOutcome invoke(
            ConsoleCapabilityInvocationContracts.InvocationCommand command) {
        requireSideEffectConfirmation(command);
        Claim claim = claim(command);
        if (!claim.owner()) {
            // A duplicate POST is a read-back operation, not a second execution.
            // It must obey the same result-retention boundary as the explicit GET.
            return outcome(claim.entity());
        }
        ConsoleCapabilityInvocationEntity dispatching = markDispatching(command.invocationId());
        if (dispatching == null) {
            return outcome(require(command.invocationId()));
        }
        if (!"DISPATCHING".equals(dispatching.getStatus())) {
            return outcome(require(command.invocationId()));
        }
        final CapabilityInvocationResponse response;
        try {
            response = capabilityCatalog.invokeTool(command.qualifiedName(), capabilityRequest(command,
                    dispatching.getTraceId()));
        } catch (RuntimeException uncertain) {
            log.warn("Console Capability dispatch is unconfirmed invocationId={} type={}", command.invocationId(),
                    uncertain.getClass().getSimpleName());
            return finishUnknown(dispatching, "CONSOLE_CAPABILITY_DISPATCH_UNKNOWN", null);
        }
        // A terminal persistence fault is deliberately not converted into another dispatch. The
        // durable DISPATCHING fence remains queryable and expiry can later converge it to UNKNOWN.
        return finishFromCapability(dispatching, command, response);
    }

    /** Returns only an actor-owned, result-safe record; a missing/foreign id is indistinguishable. */
    public ConsoleCapabilityInvocationContracts.InvocationOutcome get(String invocationId, String platformActorId) {
        ConsoleCapabilityInvocationEntity entity = invocationMapper.selectByInvocationId(normalizeInvocationId(invocationId));
        if (entity == null || "HTTP_API".equals(entity.getTargetType())
                || !safeEquals(entity.getPlatformActorId(), platformActorId)) return null;
        convergeExpiredInvocation(entity.getInvocationId());
        entity = invocationMapper.selectByInvocationId(entity.getInvocationId());
        if (entity == null || "HTTP_API".equals(entity.getTargetType())
                || !safeEquals(entity.getPlatformActorId(), platformActorId)) return null;
        return outcome(entity);
    }

    /**
     * The invocation row is the durable idempotency/audit fence, but the safe result body is not.
     * Clear bodies independently so expiry cannot be bypassed merely because nobody reads a record.
     */
    @Scheduled(fixedDelayString = "${reachai.runtime.console-capability.result-cleanup-fixed-delay-ms:60000}",
            initialDelayString = "${reachai.runtime.console-capability.result-cleanup-initial-delay-ms:60000}")
    public void clearExpiredResults() {
        try {
            LocalDateTime now = LocalDateTime.now();
            invocationMapper.update(null, Wrappers.<ConsoleCapabilityInvocationEntity>lambdaUpdate()
                    .le(ConsoleCapabilityInvocationEntity::getResultExpiresAt, now)
                    .isNotNull(ConsoleCapabilityInvocationEntity::getResultJson)
                    .set(ConsoleCapabilityInvocationEntity::getResultJson, null)
                    .set(ConsoleCapabilityInvocationEntity::getUpdatedAt, now));
        } catch (RuntimeException unavailableBeforeMigration) {
            // Same rolling-upgrade safety as recovery: a stale binary/table order must not make
            // scheduler infrastructure fail the Runtime startup or permit a resend.
            log.warn("Console Capability result cleanup is unavailable: {}",
                    unavailableBeforeMigration.getClass().getSimpleName());
        }
    }

    /** Startup only converges records which have passed their individual dispatch deadline. */
    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverUnconfirmedDispatches() {
        try {
            recoverExpiredInvocations();
        } catch (RuntimeException unavailableBeforeMigration) {
            // Deployments run the SQL upgrade before this Runtime. Do not turn an older binary/table mismatch
            // into an unrelated startup outage; no dispatch can be recovered or retried from this branch.
            log.warn("Console Capability UNKNOWN recovery is unavailable: {}",
                    unavailableBeforeMigration.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${reachai.runtime.console-capability.expiry-recovery-fixed-delay-ms:60000}",
            initialDelayString = "${reachai.runtime.console-capability.expiry-recovery-initial-delay-ms:60000}")
    public void recoverExpiredInvocations() {
        try {
            List<ConsoleCapabilityInvocationEntity> candidates = invocationMapper.selectList(
                    Wrappers.<ConsoleCapabilityInvocationEntity>lambdaQuery()
                            .in(ConsoleCapabilityInvocationEntity::getStatus, List.of("ACCEPTED", "DISPATCHING"))
                            .ne(ConsoleCapabilityInvocationEntity::getTargetType, "HTTP_API")
                            .le(ConsoleCapabilityInvocationEntity::getDeadlineEpochMs, System.currentTimeMillis())
                            .orderByAsc(ConsoleCapabilityInvocationEntity::getDeadlineEpochMs)
                            .last("LIMIT 100"));
            for (ConsoleCapabilityInvocationEntity candidate : candidates) {
                convergeExpiredInvocation(candidate.getInvocationId());
            }
        } catch (RuntimeException unavailableBeforeMigration) {
            log.warn("Console Capability deadline recovery is unavailable: {}",
                    unavailableBeforeMigration.getClass().getSimpleName());
        }
    }

    /** Conditional, per-record expiry convergence; it never claims or resends an outbound request. */
    private void convergeExpiredInvocation(String invocationId) {
        transaction().executeWithoutResult(tx -> {
            ConsoleCapabilityInvocationEntity entity = invocationMapper.selectByInvocationIdForUpdate(invocationId);
            if (entity == null || System.currentTimeMillis() <= deadlineFor(entity)) return;
            LocalDateTime now = LocalDateTime.now();
            if ("ACCEPTED".equals(entity.getStatus())) {
                finishTerminalInTransaction(entity, "NOT_DISPATCHED", "NOT_DISPATCHED",
                        "CONSOLE_CAPABILITY_DEADLINE_EXPIRED", null, false, now, null);
            } else if ("DISPATCHING".equals(entity.getStatus())) {
                finishTerminalInTransaction(entity, "UNKNOWN", "UNCONFIRMED",
                        "CONSOLE_CAPABILITY_DISPATCH_UNKNOWN", null, false, now, null);
            }
        });
    }

    private Claim claim(ConsoleCapabilityInvocationContracts.InvocationCommand command) {
        try {
            Claim result = transaction().execute(status -> claimInTransaction(command));
            if (result == null) throw new IllegalStateException("Console invocation claim did not complete");
            return result;
        } catch (DuplicateKeyException duplicate) {
            ConsoleCapabilityInvocationEntity existing = require(command.invocationId());
            assertSameInvocation(existing, command);
            return new Claim(existing, false);
        }
    }

    private Claim claimInTransaction(ConsoleCapabilityInvocationContracts.InvocationCommand command) {
        ConsoleCapabilityInvocationEntity existing = invocationMapper.selectByInvocationIdForUpdate(command.invocationId());
        if (existing != null) {
            assertSameInvocation(existing, command);
            return new Claim(existing, false);
        }
        LocalDateTime now = LocalDateTime.now();
        String traceId = "console-" + UUID.randomUUID().toString().replace("-", "");
        String rootSpanId = "root-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        RuntimeRunEntity run = newRun(command, traceId, rootSpanId, now);
        if (runMapper.insert(run) != 1 || run.getId() == null) {
            throw new IllegalStateException("Console Capability Run persistence failed");
        }
        traceRoots.startConsoleCapability(RuntimeTraceRootService.Start.builder()
                .traceId(traceId).spanId(rootSpanId).spanType("CONSOLE_CAPABILITY").runtimeType("CAPABILITY")
                .toolName(command.qualifiedName()).projectCode(command.projectCode())
                .input(Map.of("entryType", "CONSOLE", "invocationId", command.invocationId()))
                .metadataJson(json(Map.of(
                        "invocationId", command.invocationId(),
                        "expectedContractHash", command.expectedContractHash(),
                        "expectedExecutionRevision", command.expectedExecutionRevision(),
                        "identityMode", IDENTITY_MODE,
                        "platformActorId", command.platformActorId())))
                .startedAt(now).build());

        ConsoleCapabilityInvocationEntity entity = new ConsoleCapabilityInvocationEntity();
        entity.setInvocationId(command.invocationId());
        entity.setPlatformActorId(command.platformActorId());
        entity.setProjectId(command.projectId());
        entity.setProjectCode(command.projectCode());
        entity.setQualifiedName(command.qualifiedName());
        entity.setTargetType("BUSINESS_METHOD");
        entity.setExpectedContractHash(command.expectedContractHash());
        entity.setExpectedExecutionRevision(command.expectedExecutionRevision());
        entity.setInputFingerprint(fingerprint(command.input()));
        entity.setDeadlineEpochMs(command.deadlineEpochMs());
        entity.setRunId(run.getId());
        entity.setTraceId(traceId);
        entity.setIdentityMode(IDENTITY_MODE);
        entity.setSideEffect(command.sideEffect());
        entity.setConfirmedSideEffect(command.confirmedSideEffect());
        entity.setStatus("ACCEPTED");
        entity.setDispatchStage("PERSISTED");
        entity.setResultTruncated(false);
        entity.setStartedAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        if (invocationMapper.insert(entity) != 1 || entity.getId() == null) {
            throw new IllegalStateException("Console Capability invocation persistence failed");
        }
        return new Claim(entity, true);
    }

    /** Commits DISPATCHING before the outbound HMAC request. */
    private ConsoleCapabilityInvocationEntity markDispatching(String invocationId) {
        return transaction().execute(status -> {
            ConsoleCapabilityInvocationEntity entity = invocationMapper.selectByInvocationIdForUpdate(invocationId);
            if (entity == null || !"ACCEPTED".equals(entity.getStatus())) return null;
            LocalDateTime now = LocalDateTime.now();
            if (System.currentTimeMillis() > deadlineFor(entity)) {
                finishTerminalInTransaction(entity, "NOT_DISPATCHED", "NOT_DISPATCHED",
                        "CONSOLE_CAPABILITY_DEADLINE_EXPIRED", null, false, now, null);
                return require(invocationId);
            }
            int updated = invocationMapper.update(null, Wrappers.<ConsoleCapabilityInvocationEntity>lambdaUpdate()
                    .eq(ConsoleCapabilityInvocationEntity::getId, entity.getId())
                    .eq(ConsoleCapabilityInvocationEntity::getStatus, "ACCEPTED")
                    .set(ConsoleCapabilityInvocationEntity::getStatus, "DISPATCHING")
                    .set(ConsoleCapabilityInvocationEntity::getDispatchStage, "DISPATCHING")
                    .set(ConsoleCapabilityInvocationEntity::getDispatchedAt, now)
                    .set(ConsoleCapabilityInvocationEntity::getUpdatedAt, now));
            return updated == 1 ? require(invocationId) : null;
        });
    }

    private long deadlineFor(ConsoleCapabilityInvocationEntity entity) {
        return entity.getDeadlineEpochMs() == null ? 0L : entity.getDeadlineEpochMs();
    }

    private ConsoleCapabilityInvocationContracts.InvocationOutcome finishFromCapability(
            ConsoleCapabilityInvocationEntity entity,
            ConsoleCapabilityInvocationContracts.InvocationCommand command,
            CapabilityInvocationResponse response) {
        if (response == null || !command.invocationId().equals(response.invocationId())
                || !command.qualifiedName().equals(response.qualifiedName())) {
            return finishUnknown(entity, "CONSOLE_CAPABILITY_CORRELATION_UNKNOWN", null);
        }
        SensitiveValueRedactor redactor = new SensitiveValueRedactor(command.input(), command.sensitiveInputNames());
        return switch (response.status()) {
            case SUCCEEDED -> finishKnown(entity, "SUCCEEDED", "CONFIRMED", null,
                    result(response, redactor), false, response.latencyMs());
            case BUSINESS_FAILED -> finishKnown(entity, "BUSINESS_FAILED", "CONFIRMED",
                    safeCode(response.code(), redactor, "CAPABILITY_BUSINESS_RESPONSE_FAILED"),
                    result(response, redactor), false, response.latencyMs());
            case REJECTED -> finishKnown(entity, "NOT_DISPATCHED", "NOT_DISPATCHED",
                    safeCode(response.code(), redactor, "CAPABILITY_REJECTED"),
                    rejectionResult(response, redactor), false, response.latencyMs());
            case TECHNICAL_FAILED -> finishUnknown(entity,
                    safeCode(response.code(), redactor, "CONSOLE_CAPABILITY_DISPATCH_UNKNOWN"), response.latencyMs());
        };
    }

    private ConsoleCapabilityInvocationContracts.InvocationOutcome finishKnown(ConsoleCapabilityInvocationEntity entity,
                                                                                 String status,
                                                                                 String stage,
                                                                                 String code,
                                                                                 Object result,
                                                                                 boolean resultTruncated,
                                                                                 Long latencyHint) {
        LocalDateTime now = LocalDateTime.now();
        SafeResult safe = safeResult(result, entity.getInputFingerprint(), resultTruncated);
        transaction().executeWithoutResult(tx -> finishTerminalInTransaction(entity, status, stage, code,
                safe.json(), safe.truncated(), now, latencyHint));
        return outcome(require(entity.getInvocationId()));
    }

    private ConsoleCapabilityInvocationContracts.InvocationOutcome finishUnknown(ConsoleCapabilityInvocationEntity entity,
                                                                                   String code,
                                                                                   Long latencyHint) {
        LocalDateTime now = LocalDateTime.now();
        transaction().executeWithoutResult(tx -> finishTerminalInTransaction(entity, "UNKNOWN", "UNCONFIRMED", code,
                null, false, now, latencyHint));
        return outcome(require(entity.getInvocationId()));
    }

    private int updateTerminal(ConsoleCapabilityInvocationEntity entity,
                                String status,
                                String stage,
                                String code,
                                boolean unknown,
                                String resultJson,
                                boolean resultTruncated,
                                LocalDateTime now,
                                Long latencyHint) {
        long latency = latencyHint == null ? elapsed(entity.getStartedAt(), now) : Math.max(0, latencyHint);
        LocalDateTime expiry = resultJson == null ? null : now.plusHours(resultTtlHours());
        return invocationMapper.update(null, Wrappers.<ConsoleCapabilityInvocationEntity>lambdaUpdate()
                .eq(ConsoleCapabilityInvocationEntity::getId, entity.getId())
                .in(ConsoleCapabilityInvocationEntity::getStatus, List.of("ACCEPTED", "DISPATCHING"))
                .set(ConsoleCapabilityInvocationEntity::getStatus, status)
                .set(ConsoleCapabilityInvocationEntity::getDispatchStage, stage)
                .set(ConsoleCapabilityInvocationEntity::getResultJson, resultJson)
                .set(ConsoleCapabilityInvocationEntity::getResultTruncated, resultTruncated)
                .set(ConsoleCapabilityInvocationEntity::getResultExpiresAt, expiry)
                .set(ConsoleCapabilityInvocationEntity::getErrorCode, code)
                .set(ConsoleCapabilityInvocationEntity::getErrorMessage,
                        code == null ? null : WorkflowTraceSanitizer.sanitizeRejectionSummary(code))
                .set(ConsoleCapabilityInvocationEntity::getLatencyMs, latency)
                .set(ConsoleCapabilityInvocationEntity::getEndedAt, now)
                .set(ConsoleCapabilityInvocationEntity::getUpdatedAt, now));
    }

    private void finishTerminalInTransaction(ConsoleCapabilityInvocationEntity entity,
                                             String status,
                                             String stage,
                                             String code,
                                             String resultJson,
                                             boolean resultTruncated,
                                             LocalDateTime now,
                                             Long latencyHint) {
        int changed = updateTerminal(entity, status, stage, code, "UNKNOWN".equals(status), resultJson,
                resultTruncated, now, latencyHint);
        if (changed == 0) return;
        finishTraceAndRun(entity, status, stage, code, now, latencyHint, resultJson != null);
    }

    private void finishTraceAndRun(ConsoleCapabilityInvocationEntity entity,
                                   String status,
                                   String stage,
                                   String code,
                                   LocalDateTime now,
                                   Long latencyHint,
                                   boolean hasResult) {
        RuntimeRunEntity run = runMapper.selectById(entity.getRunId());
        if (run == null || !safeEquals(run.getTraceId(), entity.getTraceId()) || !StringUtils.hasText(run.getRootSpanId())) {
            throw new IllegalStateException("Console Capability Run/Trace linkage is missing");
        }
        String traceStatus = "SUCCEEDED".equals(status) ? "SUCCESS" : "UNKNOWN".equals(status) ? "ERROR" : "FAILED";
        boolean traceFinished = traceRoots.finishConsoleCapability(
                new RuntimeTraceRootService.Handle(null, entity.getTraceId(), run.getRootSpanId(), entity.getStartedAt()),
                new RuntimeTraceRootService.Completion(traceStatus, code, hasResult ? "[omitted]" : "", now,
                        json(Map.of("status", status, "dispatchStage", stage))));
        if (!traceFinished) throw new IllegalStateException("Console Capability Trace terminal update failed");
        long latency = latencyHint == null ? elapsed(entity.getStartedAt(), now) : Math.max(0, latencyHint);
        boolean success = "SUCCEEDED".equals(status);
        int runChanged = runMapper.update(null, Wrappers.<RuntimeRunEntity>lambdaUpdate()
                .eq(RuntimeRunEntity::getId, entity.getRunId())
                .eq(RuntimeRunEntity::getTraceId, entity.getTraceId())
                .eq(RuntimeRunEntity::getStatus, "RUNNING")
                .set(RuntimeRunEntity::getStatus, success ? "COMPLETED" : "FAILED")
                .set(RuntimeRunEntity::getOutputSummary, hasResult ? "[omitted]" : "")
                .set(RuntimeRunEntity::getErrorCode, success ? null : code)
                .set(RuntimeRunEntity::getErrorMessage, success ? null : WorkflowTraceSanitizer.sanitizeRejectionSummary(code))
                .set(RuntimeRunEntity::getLatencyMs, Math.toIntExact(Math.min(Integer.MAX_VALUE, latency)))
                .set(RuntimeRunEntity::getEndedAt, now)
                .set(RuntimeRunEntity::getUpdatedAt, now));
        if (runChanged != 1) throw new IllegalStateException("Console Capability Run terminal update failed");
    }

    private RuntimeRunEntity newRun(ConsoleCapabilityInvocationContracts.InvocationCommand command,
                                    String traceId,
                                    String rootSpanId,
                                    LocalDateTime now) {
        RuntimeRunEntity run = new RuntimeRunEntity();
        run.setTraceId(traceId);
        run.setRunType("CONSOLE_CAPABILITY");
        run.setEntryType("CONSOLE");
        run.setStatus("RUNNING");
        run.setProjectId(command.projectId());
        run.setProjectCode(command.projectCode());
        run.setRuntimeType("CAPABILITY");
        run.setRootSpanId(rootSpanId);
        run.setInputSummary(json(Map.of("inputFingerprint", fingerprint(command.input()),
                "parameterCount", command.input().size())));
        run.setSnapshotJson(json(Map.of("qualifiedName", command.qualifiedName(),
                "expectedContractHash", command.expectedContractHash(),
                "expectedExecutionRevision", command.expectedExecutionRevision(), "identityMode", IDENTITY_MODE,
                "sideEffect", command.sideEffect())));
        run.setMetadataJson(json(Map.of("platformActorId", command.platformActorId(),
                "invocationId", command.invocationId())));
        run.setTokenCost(0);
        run.setPlanCount(0);
        run.setReplanCount(0);
        run.setWorkflowCallCount(0);
        run.setToolCallCount(1);
        run.setGuardDenyCount(0);
        run.setApprovalCount(0);
        run.setStartedAt(now);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        return run;
    }

    private Map<String, Object> capabilityRequest(ConsoleCapabilityInvocationContracts.InvocationCommand command,
                                                   String traceId) {
        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("consoleCapabilityInvocation", true);
        constraints.put("expectedQualifiedName", command.qualifiedName());
        constraints.put("expectedProjectCode", command.projectCode());
        constraints.put("expectedProjectId", command.projectId());
        constraints.put("expectedContractHash", command.expectedContractHash());
        constraints.put("expectedExecutionRevision", command.expectedExecutionRevision());
        constraints.put("requireSignedInvocation", true);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("invocationId", command.invocationId());
        request.put(RuntimeCapabilityCatalogClient.TRUSTED_CONSOLE_INVOCATION_ATTRIBUTE, command);
        request.put("input", command.input());
        request.put("context", Map.of());
        request.put("constraints", constraints);
        request.put("deadlineEpochMs", command.deadlineEpochMs());
        request.put("idempotencyKey", command.invocationId());
        request.put("traceContext", Map.of("traceId", traceId, "entryType", "CONSOLE"));
        return request;
    }

    private Object result(CapabilityInvocationResponse response, SensitiveValueRedactor redactor) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("data", response.data());
        if (response.businessCode() != null) value.put("businessCode", response.businessCode());
        if (response.safeMetadata() != null && !response.safeMetadata().isEmpty()) value.put("metadata", response.safeMetadata());
        return redact(value, redactor, null);
    }

    private Object rejectionResult(CapabilityInvocationResponse response, SensitiveValueRedactor redactor) {
        if (response.safeMetadata() == null || response.safeMetadata().isEmpty()) return null;
        return redact(Map.of("metadata", response.safeMetadata()), redactor, null);
    }

    private SafeResult safeResult(Object result, String inputFingerprint, boolean preTruncated) {
        if (result == null) return new SafeResult(null, preTruncated);
        try {
            String encoded = objectMapper.writeValueAsString(result);
            if (encoded.getBytes(StandardCharsets.UTF_8).length <= resultMaxBytes()) {
                return new SafeResult(encoded, preTruncated);
            }
            return new SafeResult(objectMapper.writeValueAsString(Map.of(
                    "truncated", true, "reason", "RESULT_MAX_BYTES", "inputFingerprint", inputFingerprint)), true);
        } catch (Exception serialization) {
            return new SafeResult(json(Map.of("truncated", true, "reason", "RESULT_SERIALIZATION_FAILED")), true);
        }
    }

    private Object redact(Object value, SensitiveValueRedactor redactor, String fieldName) {
        if (fieldName != null && redactor.sensitiveName(fieldName)) return "[redacted]";
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> out = new LinkedHashMap<>();
            raw.forEach((key, item) -> {
                String name = String.valueOf(key);
                out.put(name, redact(item, redactor, name));
            });
            return out;
        }
        if (value instanceof Iterable<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) out.add(redact(item, redactor, fieldName));
            return out;
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> out = new ArrayList<>(length);
            for (int i = 0; i < length; i++) out.add(redact(java.lang.reflect.Array.get(value, i), redactor, fieldName));
            return out;
        }
        return redactor.contains(value) ? "[redacted]" : value;
    }

    private ConsoleCapabilityInvocationContracts.InvocationOutcome outcome(ConsoleCapabilityInvocationEntity entity) {
        if (("ACCEPTED".equals(entity.getStatus()) || "DISPATCHING".equals(entity.getStatus()))
                && System.currentTimeMillis() > deadlineFor(entity)) {
            convergeExpiredInvocation(entity.getInvocationId());
            entity = require(entity.getInvocationId());
        }
        LocalDateTime now = LocalDateTime.now();
        boolean expired = entity.getResultExpiresAt() != null && !entity.getResultExpiresAt().isAfter(now);
        if (expired && StringUtils.hasText(entity.getResultJson())) {
            // Make a directly read expired row converge immediately; scheduled cleanup handles
            // records which are never read again.
            invocationMapper.update(null, Wrappers.<ConsoleCapabilityInvocationEntity>lambdaUpdate()
                    .eq(ConsoleCapabilityInvocationEntity::getId, entity.getId())
                    .isNotNull(ConsoleCapabilityInvocationEntity::getResultJson)
                    .set(ConsoleCapabilityInvocationEntity::getResultJson, null)
                    .set(ConsoleCapabilityInvocationEntity::getUpdatedAt, now));
            entity.setResultJson(null);
        }
        Object result = expired ? null : readResult(entity.getResultJson());
        boolean terminal = !"ACCEPTED".equals(entity.getStatus()) && !"DISPATCHING".equals(entity.getStatus());
        return new ConsoleCapabilityInvocationContracts.InvocationOutcome(
                ConsoleCapabilityInvocationContracts.CONTRACT_VERSION,
                entity.getInvocationId(), entity.getRunId(), entity.getTraceId(), entity.getProjectCode(),
                entity.getProjectId(), entity.getQualifiedName(), entity.getIdentityMode(), entity.getStatus(), entity.getDispatchStage(), terminal,
                entity.getErrorCode(), expired ? "[result-expired]" : entity.getErrorMessage(), result,
                Boolean.TRUE.equals(entity.getResultTruncated()), entity.getResultExpiresAt() == null ? null
                        : entity.getResultExpiresAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), entity.getLatencyMs());
    }

    private Object readResult(String encoded) {
        if (!StringUtils.hasText(encoded)) return null;
        try { return objectMapper.readValue(encoded, Object.class); }
        catch (Exception ignored) { return Map.of("truncated", true, "reason", "RESULT_UNAVAILABLE"); }
    }

    private void assertSameInvocation(ConsoleCapabilityInvocationEntity existing,
                                      ConsoleCapabilityInvocationContracts.InvocationCommand command) {
        if ("HTTP_API".equals(existing.getTargetType())
                || !safeEquals(existing.getPlatformActorId(), command.platformActorId())
                || !safeEquals(existing.getProjectId(), command.projectId())
                || !safeEquals(existing.getProjectCode(), command.projectCode())
                || !safeEquals(existing.getQualifiedName(), command.qualifiedName())
                || !safeEquals(existing.getExpectedContractHash(), command.expectedContractHash())
                || !safeEquals(existing.getExpectedExecutionRevision(), command.expectedExecutionRevision())
                || !safeEquals(existing.getInputFingerprint(), fingerprint(command.input()))
                || !safeEquals(existing.getSideEffect(), command.sideEffect())
                || !safeEquals(existing.getConfirmedSideEffect(), command.confirmedSideEffect())) {
            throw new InvocationConflictException();
        }
    }

    private void requireSideEffectConfirmation(ConsoleCapabilityInvocationContracts.InvocationCommand command) {
        String sideEffect = command.sideEffect();
        boolean readOnly = "NONE".equals(sideEffect) || "READ".equals(sideEffect) || "READ_ONLY".equals(sideEffect);
        if (!readOnly && !command.confirmedSideEffect()) {
            throw new SideEffectConfirmationRequiredException();
        }
    }

    private ConsoleCapabilityInvocationEntity require(String invocationId) {
        ConsoleCapabilityInvocationEntity result = invocationMapper.selectByInvocationId(invocationId);
        if (result == null) throw new IllegalStateException("Console Capability invocation disappeared");
        return result;
    }

    private String fingerprint(Map<String, Object> input) {
        try {
            JsonNode canonical = canonical(objectMapper.valueToTree(input == null ? Map.of() : input));
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Console invocation input is not serializable", failure);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value instanceof ObjectNode object) {
            ObjectNode out = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> out.set(name, canonical(object.get(name))));
            return out;
        }
        if (value instanceof ArrayNode array) {
            ArrayNode out = objectMapper.createArrayNode();
            array.forEach(item -> out.add(canonical(item)));
            return out;
        }
        return value;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Console Capability safe metadata serialization failed", failure); }
    }

    private TransactionTemplate transaction() { return new TransactionTemplate(transactionManager); }

    private long elapsed(LocalDateTime start, LocalDateTime end) {
        return Math.max(0, ChronoUnit.MILLIS.between(start == null ? end : start, end));
    }

    private String normalizeInvocationId(String invocationId) {
        try { return UUID.fromString(invocationId).toString(); }
        catch (Exception invalid) { throw new IllegalArgumentException("invocationId must be a UUID", invalid); }
    }

    private boolean safeEquals(Object left, Object right) {
        return java.util.Objects.equals(left, right);
    }

    private String safeCode(String value, SensitiveValueRedactor redactor, String fallback) {
        if (!StringUtils.hasText(value) || !SAFE_CODE.matcher(value.trim()).matches() || redactor.contains(value.trim())) {
            return fallback;
        }
        return value.trim();
    }

    private int resultMaxBytes() { return Math.min(DEFAULT_RESULT_MAX_BYTES, Math.max(1024, configuredResultMaxBytes)); }
    private int resultTtlHours() { return Math.min(DEFAULT_RESULT_TTL_HOURS, Math.max(1, configuredResultTtlHours)); }

    /** In-memory only: it is built from this request and is never stored with the invocation. */
    private static final class SensitiveValueRedactor {
        private final Set<String> declaredPaths = new LinkedHashSet<>();
        private final Set<String> values = new LinkedHashSet<>();

        private SensitiveValueRedactor(Map<String, Object> input, List<String> declaredSensitive) {
            if (declaredSensitive != null) {
                for (String path : declaredSensitive) {
                    if (StringUtils.hasText(path)) declaredPaths.add(normalize(path));
                }
            }
            collect(input == null ? Map.of() : input, "");
        }

        private void collect(Object value, String path) {
            if (value instanceof Map<?, ?> map) {
                map.forEach((key, item) -> {
                    String name = String.valueOf(key);
                    String child = path.isEmpty() ? name : path + "." + name;
                    if (declared(child) || secretField(name)) collectLeafValues(item);
                    else collect(item, child);
                });
                return;
            }
            if (value instanceof Iterable<?> iterable) {
                for (Object item : iterable) collect(item, path + "[]");
                return;
            }
            if (value != null && value.getClass().isArray()) {
                int length = java.lang.reflect.Array.getLength(value);
                for (int index = 0; index < length; index++) collect(java.lang.reflect.Array.get(value, index), path + "[]");
            }
        }

        private void collectLeafValues(Object value) {
            if (value instanceof Map<?, ?> map) { map.forEach((key, item) -> collectLeafValues(item)); return; }
            if (value instanceof Iterable<?> iterable) { for (Object item : iterable) collectLeafValues(item); return; }
            if (value != null && value.getClass().isArray()) {
                int length = java.lang.reflect.Array.getLength(value);
                for (int index = 0; index < length; index++) collectLeafValues(java.lang.reflect.Array.get(value, index));
                return;
            }
            if (value != null && StringUtils.hasText(String.valueOf(value))) values.add(String.valueOf(value));
        }

        private boolean sensitiveName(String name) { return secretField(name) || declared(name); }
        private boolean declared(String path) {
            String normalized = normalize(path);
            return declaredPaths.stream().anyMatch(value -> value.equals(normalized)
                    || !value.contains(".") && value.equals(lastSegment(normalized)));
        }

        private boolean contains(Object value) {
            if (value == null || values.isEmpty()) return false;
            String rendered = String.valueOf(value);
            return values.stream().anyMatch(secret -> rendered.contains(secret));
        }

        private static boolean secretField(String name) {
            if (!StringUtils.hasText(name)) return false;
            String lower = name.trim().toLowerCase(Locale.ROOT);
            return SECRET_KEY_PARTS.stream().anyMatch(lower::contains);
        }

        private static String normalize(String path) {
            return path == null ? "" : path.trim().toLowerCase(Locale.ROOT).replaceAll("\\[\\d+\\]", "[]");
        }

        private static String lastSegment(String path) {
            int index = path.lastIndexOf('.');
            return index < 0 ? path.replace("[]", "") : path.substring(index + 1).replace("[]", "");
        }
    }

    private record Claim(ConsoleCapabilityInvocationEntity entity, boolean owner) { }
    private record SafeResult(String json, boolean truncated) { }

    public static class InvocationConflictException extends RuntimeException { }
    public static class SideEffectConfirmationRequiredException extends RuntimeException { }
}

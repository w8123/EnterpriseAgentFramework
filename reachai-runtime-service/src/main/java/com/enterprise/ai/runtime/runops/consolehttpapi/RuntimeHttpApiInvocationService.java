package com.enterprise.ai.runtime.runops.consolehttpapi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.common.capability.HttpApiConsolePolicy;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialRuntime;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.runops.RuntimeRunEntity;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.runops.consolecapability.ConsoleCapabilityInvocationEntity;
import com.enterprise.ai.runtime.runops.consolecapability.ConsoleCapabilityInvocationMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceRootService;
import com.enterprise.ai.runtime.trace.WorkflowTraceSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Durable single-dispatch HTTP API Console root, sharing the Console attempt ledger and Run/Trace. */
@Service
@Slf4j
@RequiredArgsConstructor
public class RuntimeHttpApiInvocationService {
    private static final String TARGET = "HTTP_API";
    private static final int RESULT_LIMIT = 64 * 1024;
    private static final int RESULT_TTL_HOURS = 24;
    private final ConsoleCapabilityInvocationMapper attempts;
    private final RuntimeRunMapper runs;
    private final RuntimeTraceRootService traces;
    private final RuntimeHttpApiConnectionService connections;
    private final WorkflowHttpClient http;
    private final ObjectMapper json;
    private final PlatformTransactionManager transactions;

    public HttpApiConsoleContracts.InvocationOutcome invoke(HttpApiConsoleContracts.InvocationCommand command) {
        validate(command);
        ConsoleCapabilityInvocationEntity prior = attempts.selectByInvocationId(command.invocationId());
        if (prior != null) {
            assertSame(prior, command);
            return outcome(prior);
        }
        Prepared prepared = prepare(command);
        Claim claim = claim(command, prepared);
        ConsoleCapabilityInvocationEntity claimed = claim.row();
        if (!claim.owner()) return outcome(claimed);
        if (!"ACCEPTED".equals(claimed.getStatus())) return outcome(claimed);
        // A duplicate caller may have acquired the attempt in the intervening transaction.
        if (!Objects.equals(claimed.getCredentialRevision(), prepared.credentialRevision())) {
            return outcome(claimed);
        }
        try {
            Prepared current = prepare(command);
            if (!Objects.equals(prepared.credentialRevision(), current.credentialRevision())) {
                return finish(claimed, "NOT_DISPATCHED", "NOT_DISPATCHED",
                        "HTTP_API_CREDENTIAL_REVISION_CHANGED", null, null, null);
            }
        } catch (RuntimeException changed) {
            return finish(claimed, "NOT_DISPATCHED", "NOT_DISPATCHED",
                    "HTTP_API_PRE_DISPATCH_CHANGED", null, null, null);
        }
        if (!markDispatching(command.invocationId())) return outcome(require(command.invocationId()));
        WorkflowHttpClient.HttpExecutionResult response;
        try {
            boolean write = "WRITE".equals(prepared.bound().sideEffect());
            response = http.execute(new WorkflowHttpClient.HttpExecutionRequest(prepared.bound().method(),
                    prepared.connection().getOrigin() + prepared.bound().encodedRoute(),
                    prepared.bound().queryParams(), prepared.bound().jsonBody() == null
                            ? Map.of("Accept", "application/json")
                            : Map.of("Accept", "application/json", "Content-Type", "application/json"),
                    prepared.bound().jsonBody() == null ? "NONE" : "JSON",
                    prepared.bound().jsonBody() == null ? null : json(prepared.bound().jsonBody()),
                    30_000, prepared.connection().getCredentialRef(),
                    WorkflowExecutionIdentity.fromAgent(command.projectId(), command.projectCode()),
                    prepared.credentialRevision(), !write));
        } catch (RuntimeException unconfirmed) {
            log.warn("HTTP API Console dispatch unconfirmed invocationId={} failureType={}",
                    command.invocationId(), unconfirmed.getClass().getSimpleName());
            return finish(claimed, "UNKNOWN", "UNCONFIRMED", "HTTP_API_DISPATCH_UNKNOWN", null, null, null);
        }
        if (response.statusCode() > 0) {
            if ("WRITE".equals(prepared.bound().sideEffect()) && response.success()
                    && !(response.parsedBody() instanceof Map<?, ?>)) {
                return finish(claimed, "UNKNOWN", "UNCONFIRMED", "HTTP_API_RESULT_UNCONFIRMED",
                        response.statusCode(), response.durationMs(), null);
            }
            String status = response.success() ? "SUCCEEDED" : "HTTP_FAILED";
            Object result = safeResponse(response, prepared);
            return finish(claimed, status, "CONFIRMED", response.success() ? null : "HTTP_API_STATUS_FAILED",
                    response.statusCode(), response.durationMs(), result);
        }
        if (Set.of("RUNTIME_HTTP_CREDENTIAL_REVISION_CHANGED", "RUNTIME_HTTP_CREDENTIAL_DENIED",
                "RUNTIME_HTTP_CREDENTIAL_UNAVAILABLE", "RUNTIME_HTTP_URL_REQUIRED",
                "RUNTIME_HTTP_CREDENTIAL_TYPE_UNSUPPORTED").contains(response.code())
                || (response.code() != null && response.code().startsWith("RUNTIME_HTTP_EGRESS_"))) {
            return finish(claimed, "NOT_DISPATCHED", "NOT_DISPATCHED", "HTTP_API_PRE_DISPATCH_REJECTED",
                    null, response.durationMs(), null);
        }
        return finish(claimed, "UNKNOWN", "UNCONFIRMED", "HTTP_API_DISPATCH_UNKNOWN",
                null, response.durationMs(), null);
    }

    public HttpApiConsoleContracts.InvocationOutcome get(String invocationId, String actorId) {
        String id = uuid(invocationId);
        ConsoleCapabilityInvocationEntity row = attempts.selectByInvocationId(id);
        if (row == null || !TARGET.equals(row.getTargetType()) || !Objects.equals(row.getPlatformActorId(), actorId)) {
            return null;
        }
        converge(id);
        return outcome(require(id));
    }

    /** Startup/scheduled recovery only terminalizes; it never retries an outbound HTTP request. */
    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverAtStartup() { recoverExpired(); }

    @Scheduled(fixedDelayString = "${reachai.runtime.console-capability.expiry-recovery-fixed-delay-ms:60000}",
            initialDelayString = "${reachai.runtime.console-capability.expiry-recovery-initial-delay-ms:60000}")
    public void recoverExpired() {
        try {
            List<ConsoleCapabilityInvocationEntity> rows = attempts.selectList(
                    Wrappers.<ConsoleCapabilityInvocationEntity>lambdaQuery()
                            .eq(ConsoleCapabilityInvocationEntity::getTargetType, TARGET)
                            .in(ConsoleCapabilityInvocationEntity::getStatus, List.of("ACCEPTED", "DISPATCHING"))
                            .le(ConsoleCapabilityInvocationEntity::getDeadlineEpochMs, System.currentTimeMillis())
                            .orderByAsc(ConsoleCapabilityInvocationEntity::getDeadlineEpochMs).last("LIMIT 100"));
            for (ConsoleCapabilityInvocationEntity row : rows) converge(row.getInvocationId());
        } catch (RuntimeException unavailableBeforeMigration) {
            log.warn("HTTP API Console recovery unavailable: {}", unavailableBeforeMigration.getClass().getSimpleName());
        }
    }

    private void validate(HttpApiConsoleContracts.InvocationCommand command) {
        if (command == null || command.contractVersion() != HttpApiConsoleContracts.VERSION
                || !uuid(command.invocationId()).equals(command.invocationId())
                || !StringUtils.hasText(command.platformActorId()) || command.apiId() == null || command.apiId() <= 0
                || command.projectId() == null || command.projectId() <= 0
                || !StringUtils.hasText(command.projectCode()) || !StringUtils.hasText(command.environment())
                || !StringUtils.hasText(command.qualifiedName()) || command.connectionRevision() == null
                || command.connectionRevision() <= 0 || !hash(command.expectedContractHash())
                || !hash(command.expectedSourceSetRevision())
                || command.deadlineEpochMs() < System.currentTimeMillis()
                || command.deadlineEpochMs() > System.currentTimeMillis() + 60_000) {
            throw new IllegalArgumentException("HTTP API invocation command is invalid");
        }
        if (command.expectedCredentialRevision() != null && !hash(command.expectedCredentialRevision())) {
            throw new IllegalArgumentException("invalid HTTP API credential revision");
        }
        if (command.pathParams() != null && command.pathParams().size() > 64
                || command.queryParams() != null && command.queryParams().size() > 64
                || command.body() != null && command.body().size() > 64) {
            throw new IllegalArgumentException("too many HTTP API parameters");
        }
    }

    private Prepared prepare(HttpApiConsoleContracts.InvocationCommand command) {
        HttpApiConsoleContracts.ConnectionCommand ownerCommand = new HttpApiConsoleContracts.ConnectionCommand(
                HttpApiConsoleContracts.VERSION, command.apiId(), command.qualifiedName(),
                command.projectId(), command.projectCode(), command.environment(), null);
        HttpApiConsoleContracts.ExecutionContext owner = connections.owner(ownerCommand);
        if (!owner.sourceConfirmed() || !"ACCEPTED".equals(owner.sourceStatus()) || !owner.consoleCallSupported()
                || !Objects.equals(owner.acceptedContractHash(), owner.candidateContractHash())
                || !Objects.equals(owner.acceptedContractHash(), command.expectedContractHash())
                || !Objects.equals(owner.sourceSetRevision(), command.expectedSourceSetRevision())) {
            throw new Conflict("HTTP_API_CONTRACT_CHANGED", "API 来源或已接纳契约已变化，请刷新后重试");
        }
        if ("WRITE".equals(owner.acceptedContract().path("sideEffect").asText()) && !command.confirmedSideEffect()) {
            throw new Conflict("HTTP_API_CONFIRMATION_REQUIRED", "写 API 必须确认当前契约、连接和输入");
        }
        RuntimeHttpApiConnectionEntity connection = connections.find(owner.qualifiedName());
        if (connection == null || !Objects.equals(connection.getRevision(), command.connectionRevision())
                || !Objects.equals(connection.getProjectId(), owner.projectId())
                || !Objects.equals(connection.getProjectCode(), owner.projectCode())
                || !Objects.equals(connection.getEnvironment(), owner.environment())) {
            throw new Conflict("HTTP_API_CONNECTION_CHANGED", "连接配置已变化，请刷新后重试");
        }
        connections.origin(connection.getOrigin());
        RuntimeWorkflowCredentialRuntime credential = connections.selectedCredential(
                connection.getAuthMode(), connection.getCredentialRef(), owner);
        if (credential != null && !StringUtils.hasText(credential.revision())) {
            throw new Conflict("HTTP_API_CREDENTIAL_REVISION_UNAVAILABLE", "项目凭据修订不可用，请刷新凭据后重试");
        }
        if ("WRITE".equals(owner.acceptedContract().path("sideEffect").asText())
                && !Objects.equals(command.expectedCredentialRevision(), credential == null ? null : credential.revision())) {
            throw new Conflict("HTTP_API_CREDENTIAL_CHANGED", "项目凭据已变化，请刷新连接并重新确认");
        }
        String headerName = credential == null || credential.secret() == null ? null
                : credential.secret().get("headerName") instanceof String name ? name : null;
        String authReason = HttpApiConsolePolicy.connectionAuthReason(owner.acceptedContract(),
                connection.getAuthMode(), credential == null ? null : credential.type(), headerName);
        if (authReason != null) throw new Conflict("HTTP_API_AUTH_MISMATCH", authReason);
        HttpApiConsolePolicy.BoundRequest bound = HttpApiConsolePolicy.bind(owner.acceptedContract(),
                command.pathParams(), command.queryParams(), command.body(), json);
        // Pin sensitive values to this verified accepted schema and actual bound body before dispatch.
        // They stay in the local Prepared value, never in a ledger/snapshot or client-authored metadata.
        List<String> inputSecrets = new ArrayList<>();
        collectInputSecrets(command.pathParams(), inputSecrets, null);
        collectInputSecrets(command.queryParams(), inputSecrets, null);
        collectInputSecrets(bound.jsonBody(), inputSecrets,
                owner.acceptedContract().path("requestBody").path("schema").path("properties"));
        return new Prepared(connection, credential, credential == null ? null : credential.revision(), bound,
                List.copyOf(inputSecrets));
    }

    private Claim claim(HttpApiConsoleContracts.InvocationCommand command, Prepared prepared) {
        try {
            return transaction().execute(tx -> {
                ConsoleCapabilityInvocationEntity existing = attempts.selectByInvocationIdForUpdate(command.invocationId());
                if (existing != null) { assertSame(existing, command); return new Claim(existing, false); }
                LocalDateTime now = LocalDateTime.now();
                String traceId = "console-api-" + UUID.randomUUID().toString().replace("-", "");
                String rootSpan = "root-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                String inputFingerprint = fingerprint(command);
                RuntimeRunEntity run = new RuntimeRunEntity();
                run.setTraceId(traceId); run.setRootSpanId(rootSpan); run.setRunType("CONSOLE_HTTP_API");
                run.setEntryType("CONSOLE"); run.setRuntimeType("HTTP_API"); run.setStatus("RUNNING");
                run.setProjectId(command.projectId()); run.setProjectCode(command.projectCode());
                run.setInputSummary(json(Map.of("inputFingerprint", inputFingerprint,
                        "parameterCount", size(command.pathParams()) + size(command.queryParams()) + size(command.body()))));
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("apiId", command.apiId());
                snapshot.put("qualifiedName", command.qualifiedName());
                snapshot.put("contractHash", command.expectedContractHash());
                snapshot.put("sourceSetRevision", command.expectedSourceSetRevision());
                snapshot.put("connectionRevision", command.connectionRevision());
                snapshot.put("credentialRevision", prepared.credentialRevision());
                snapshot.put("httpMethod", prepared.bound().method());
                snapshot.put("sideEffect", prepared.bound().sideEffect());
                run.setSnapshotJson(json(snapshot));
                run.setMetadataJson(json(Map.of("invocationId", command.invocationId(),
                        "targetType", TARGET, "platformActorId", command.platformActorId(),
                        "sideEffect", prepared.bound().sideEffect(), "confirmedSideEffect", command.confirmedSideEffect())));
                run.setToolCallCount(1); run.setTokenCost(0); run.setPlanCount(0); run.setReplanCount(0);
                run.setWorkflowCallCount(0); run.setGuardDenyCount(0); run.setApprovalCount(0);
                run.setStartedAt(now); run.setCreatedAt(now); run.setUpdatedAt(now);
                if (runs.insert(run) != 1 || run.getId() == null) throw new IllegalStateException("Run persistence failed");
                traces.startConsoleCapability(RuntimeTraceRootService.Start.builder()
                        .traceId(traceId).spanId(rootSpan).spanType("CONSOLE_CAPABILITY")
                        .runtimeType("HTTP_API").toolName(command.qualifiedName())
                        .projectCode(command.projectCode())
                        .input(Map.of("entryType", "CONSOLE", "targetType", TARGET, "invocationId", command.invocationId()))
                        .metadataJson(json(Map.of("contractHash", command.expectedContractHash(),
                                "sourceSetRevision", command.expectedSourceSetRevision(),
                                "connectionRevision", command.connectionRevision(),
                                "sideEffect", prepared.bound().sideEffect(), "confirmedSideEffect", command.confirmedSideEffect())))
                        .startedAt(now).build());
                ConsoleCapabilityInvocationEntity row = new ConsoleCapabilityInvocationEntity();
                row.setInvocationId(command.invocationId()); row.setTargetType(TARGET);
                row.setPlatformActorId(command.platformActorId()); row.setProjectId(command.projectId());
                row.setProjectCode(command.projectCode()); row.setEnvironment(command.environment());
                row.setQualifiedName(command.qualifiedName()); row.setExpectedContractHash(command.expectedContractHash());
                row.setSourceSetRevision(command.expectedSourceSetRevision());
                row.setConnectionRevision(command.connectionRevision());
                row.setCredentialRevision(prepared.credentialRevision());
                row.setInputFingerprint(inputFingerprint); row.setDeadlineEpochMs(command.deadlineEpochMs());
                row.setRunId(run.getId()); row.setTraceId(traceId);
                row.setIdentityMode("PROJECT_CREDENTIAL_NO_BUSINESS_IDENTITY");
                row.setSideEffect(prepared.bound().sideEffect()); row.setConfirmedSideEffect(command.confirmedSideEffect());
                row.setStatus("ACCEPTED"); row.setDispatchStage("PERSISTED"); row.setResultTruncated(false);
                row.setStartedAt(now); row.setCreatedAt(now); row.setUpdatedAt(now);
                if (attempts.insert(row) != 1 || row.getId() == null) throw new IllegalStateException("Attempt persistence failed");
                return new Claim(row, true);
            });
        } catch (DuplicateKeyException duplicate) {
            ConsoleCapabilityInvocationEntity existing = require(command.invocationId());
            assertSame(existing, command);
            return new Claim(existing, false);
        }
    }

    private boolean markDispatching(String id) {
        Boolean result = transaction().execute(tx -> {
            ConsoleCapabilityInvocationEntity row = attempts.selectByInvocationIdForUpdate(id);
            if (row == null || !"ACCEPTED".equals(row.getStatus())) return false;
            if (System.currentTimeMillis() >= row.getDeadlineEpochMs()) {
                terminal(row, "NOT_DISPATCHED", "NOT_DISPATCHED", "HTTP_API_DEADLINE_EXPIRED",
                        null, null, null);
                return false;
            }
            LocalDateTime now = LocalDateTime.now();
            return attempts.update(null, Wrappers.<ConsoleCapabilityInvocationEntity>lambdaUpdate()
                    .eq(ConsoleCapabilityInvocationEntity::getId, row.getId())
                    .eq(ConsoleCapabilityInvocationEntity::getStatus, "ACCEPTED")
                    .set(ConsoleCapabilityInvocationEntity::getStatus, "DISPATCHING")
                    .set(ConsoleCapabilityInvocationEntity::getDispatchStage, "DISPATCHING")
                    .set(ConsoleCapabilityInvocationEntity::getDispatchedAt, now)
                    .set(ConsoleCapabilityInvocationEntity::getUpdatedAt, now)) == 1;
        });
        return Boolean.TRUE.equals(result);
    }

    private HttpApiConsoleContracts.InvocationOutcome finish(ConsoleCapabilityInvocationEntity row, String status,
            String stage, String code, Integer httpStatus, Long latencyMs, Object result) {
        transaction().executeWithoutResult(tx -> {
            ConsoleCapabilityInvocationEntity locked = attempts.selectByInvocationIdForUpdate(row.getInvocationId());
            if (locked != null && Set.of("ACCEPTED", "DISPATCHING").contains(locked.getStatus())) {
                terminal(locked, status, stage, code, httpStatus, latencyMs, result);
            }
        });
        return outcome(require(row.getInvocationId()));
    }

    private void terminal(ConsoleCapabilityInvocationEntity row, String status, String stage, String code,
                          Integer httpStatus, Long latencyHint, Object result) {
        LocalDateTime now = LocalDateTime.now();
        long latency = latencyHint == null ? Math.max(0, ChronoUnit.MILLIS.between(row.getStartedAt(), now))
                : Math.max(0, latencyHint);
        String resultJson = result == null ? null : json(result);
        boolean truncated = false;
        if (resultJson != null && resultJson.getBytes(StandardCharsets.UTF_8).length > RESULT_LIMIT) {
            resultJson = json(Map.of("truncated", true, "reason", "RESULT_MAX_BYTES"));
            truncated = true;
        }
        int changed = attempts.update(null, Wrappers.<ConsoleCapabilityInvocationEntity>lambdaUpdate()
                .eq(ConsoleCapabilityInvocationEntity::getId, row.getId())
                .in(ConsoleCapabilityInvocationEntity::getStatus, List.of("ACCEPTED", "DISPATCHING"))
                .set(ConsoleCapabilityInvocationEntity::getStatus, status)
                .set(ConsoleCapabilityInvocationEntity::getDispatchStage, stage)
                .set(ConsoleCapabilityInvocationEntity::getHttpStatus, httpStatus)
                .set(ConsoleCapabilityInvocationEntity::getErrorCode, code)
                .set(ConsoleCapabilityInvocationEntity::getErrorMessage,
                        code == null ? null : WorkflowTraceSanitizer.sanitizeRejectionSummary(code))
                .set(ConsoleCapabilityInvocationEntity::getResultJson, resultJson)
                .set(ConsoleCapabilityInvocationEntity::getResultTruncated, truncated)
                .set(ConsoleCapabilityInvocationEntity::getResultExpiresAt,
                        resultJson == null ? null : now.plusHours(RESULT_TTL_HOURS))
                .set(ConsoleCapabilityInvocationEntity::getLatencyMs, latency)
                .set(ConsoleCapabilityInvocationEntity::getEndedAt, now)
                .set(ConsoleCapabilityInvocationEntity::getUpdatedAt, now));
        if (changed != 1) return;
        RuntimeRunEntity run = runs.selectById(row.getRunId());
        if (run == null || !Objects.equals(run.getTraceId(), row.getTraceId())) {
            throw new IllegalStateException("HTTP API Run/Trace linkage missing");
        }
        boolean success = "SUCCEEDED".equals(status);
        if (!traces.finishConsoleCapability(
                new RuntimeTraceRootService.Handle(null, row.getTraceId(), run.getRootSpanId(), row.getStartedAt()),
                new RuntimeTraceRootService.Completion(success ? "SUCCESS" : "UNKNOWN".equals(status) ? "ERROR" : "FAILED",
                        code, resultJson == null ? "" : "[omitted]", now,
                        json(Map.of("targetType", TARGET, "status", status, "dispatchStage", stage))))) {
            throw new IllegalStateException("HTTP API Trace terminal update failed");
        }
        if (runs.update(null, Wrappers.<RuntimeRunEntity>lambdaUpdate()
                .eq(RuntimeRunEntity::getId, row.getRunId()).eq(RuntimeRunEntity::getStatus, "RUNNING")
                .set(RuntimeRunEntity::getStatus, success ? "COMPLETED" : "FAILED")
                .set(RuntimeRunEntity::getOutputSummary, resultJson == null ? "" : "[omitted]")
                .set(RuntimeRunEntity::getErrorCode, success ? null : code)
                .set(RuntimeRunEntity::getErrorMessage, success ? null : WorkflowTraceSanitizer.sanitizeRejectionSummary(code))
                .set(RuntimeRunEntity::getLatencyMs, Math.toIntExact(Math.min(Integer.MAX_VALUE, latency)))
                .set(RuntimeRunEntity::getEndedAt, now).set(RuntimeRunEntity::getUpdatedAt, now)) != 1) {
            throw new IllegalStateException("HTTP API Run terminal update failed");
        }
    }

    private void converge(String id) {
        transaction().executeWithoutResult(tx -> {
            ConsoleCapabilityInvocationEntity row = attempts.selectByInvocationIdForUpdate(id);
            if (row == null || !TARGET.equals(row.getTargetType())) return;
            if (Set.of("ACCEPTED", "DISPATCHING").contains(row.getStatus())
                    && System.currentTimeMillis() > row.getDeadlineEpochMs()) {
                boolean dispatched = "DISPATCHING".equals(row.getStatus());
                terminal(row, dispatched ? "UNKNOWN" : "NOT_DISPATCHED",
                        dispatched ? "UNCONFIRMED" : "NOT_DISPATCHED",
                        dispatched ? "HTTP_API_DISPATCH_UNKNOWN" : "HTTP_API_DEADLINE_EXPIRED",
                        null, null, null);
            }
        });
    }

    private HttpApiConsoleContracts.InvocationOutcome outcome(ConsoleCapabilityInvocationEntity row) {
        boolean expired = row.getResultExpiresAt() != null && !row.getResultExpiresAt().isAfter(LocalDateTime.now());
        Object result = null;
        if (!expired && StringUtils.hasText(row.getResultJson())) {
            try { result = json.readValue(row.getResultJson(), Object.class); }
            catch (Exception invalid) { result = Map.of("truncated", true, "reason", "RESULT_UNAVAILABLE"); }
        }
        return new HttpApiConsoleContracts.InvocationOutcome(HttpApiConsoleContracts.VERSION,
                row.getInvocationId(), TARGET, row.getQualifiedName(), row.getProjectId(), row.getProjectCode(),
                row.getEnvironment(), row.getRunId(), row.getTraceId(), row.getStatus(), row.getDispatchStage(),
                !Set.of("ACCEPTED", "DISPATCHING").contains(row.getStatus()), row.getErrorCode(),
                row.getHttpStatus(), row.getLatencyMs(), result, Boolean.TRUE.equals(row.getResultTruncated()),
                row.getResultExpiresAt() == null ? null : row.getResultExpiresAt().atZone(ZoneId.systemDefault())
                        .toInstant().toEpochMilli(), row.getExpectedContractHash(), row.getSourceSetRevision(),
                row.getConnectionRevision(), row.getCredentialRevision());
    }

    private Object safeResponse(WorkflowHttpClient.HttpExecutionResult response, Prepared prepared) {
        List<String> secrets = new ArrayList<>(prepared.inputSecrets());
        RuntimeWorkflowCredentialRuntime credential = prepared.credential();
        if (credential != null && credential.secret() != null) {
            for (String key : List.of("token", "apiKey", "password")) {
                Object value = credential.secret().get(key);
                if (value instanceof String text && !text.isBlank()) secrets.add(text);
            }
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("contentType", redact(response.contentType(), secrets, null));
        safe.put("body", redact(response.parsedBody() == null ? response.body() : response.parsedBody(), secrets, null));
        return safe;
    }

    private void collectInputSecrets(Map<String, Object> input, List<String> secrets, JsonNode properties) {
        com.enterprise.ai.common.capability.HttpApiResponseProtection.collectInputSecrets(input, secrets, properties);
    }

    private Object redact(Object value, List<String> secrets, String name) {
        return com.enterprise.ai.common.capability.HttpApiResponseProtection.redact(value, secrets, name);
    }

    private void assertSame(ConsoleCapabilityInvocationEntity row, HttpApiConsoleContracts.InvocationCommand command) {
        if (!TARGET.equals(row.getTargetType()) || !Objects.equals(row.getPlatformActorId(), command.platformActorId())
                || !Objects.equals(row.getProjectId(), command.projectId())
                || !Objects.equals(row.getProjectCode(), command.projectCode())
                || !Objects.equals(row.getEnvironment(), command.environment())
                || !Objects.equals(row.getQualifiedName(), command.qualifiedName())
                || !Objects.equals(row.getExpectedContractHash(), command.expectedContractHash())
                || !Objects.equals(row.getSourceSetRevision(), command.expectedSourceSetRevision())
                || !Objects.equals(row.getConnectionRevision(), command.connectionRevision())
                || !Objects.equals(row.getInputFingerprint(), fingerprint(command))) {
            throw new Conflict("HTTP_API_INVOCATION_CONFLICT", "调用 ID 已用于不同操作者、API、参数或连接");
        }
    }

    private String fingerprint(HttpApiConsoleContracts.InvocationCommand command) {
        try {
            Map<String, Object> value = new TreeMap<>();
            value.put("path", new TreeMap<>(command.pathParams() == null ? Map.of() : command.pathParams()));
            value.put("query", new TreeMap<>(command.queryParams() == null ? Map.of() : command.queryParams()));
            value.put("body", command.body() == null ? null : new TreeMap<>(command.body()));
            value.put("confirmation", command.confirmedSideEffect());
            value.put("credentialRevision", command.expectedCredentialRevision());
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(value)));
        } catch (Exception invalid) { throw new IllegalArgumentException("HTTP API parameters are invalid", invalid); }
    }

    private ConsoleCapabilityInvocationEntity require(String id) {
        ConsoleCapabilityInvocationEntity row = attempts.selectByInvocationId(id);
        if (row == null) throw new IllegalStateException("HTTP API attempt disappeared");
        return row;
    }

    private String json(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalStateException("HTTP API safe metadata serialization failed", invalid); }
    }

    private String uuid(String value) {
        try { return UUID.fromString(value).toString(); }
        catch (Exception invalid) { throw new IllegalArgumentException("invocationId must be UUID", invalid); }
    }

    private boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private int size(Map<String, Object> value) { return value == null ? 0 : value.size(); }
    private TransactionTemplate transaction() { return new TransactionTemplate(transactions); }
    private record Prepared(RuntimeHttpApiConnectionEntity connection, RuntimeWorkflowCredentialRuntime credential,
                            String credentialRevision, HttpApiConsolePolicy.BoundRequest bound,
                            List<String> inputSecrets) { }
    private record Claim(ConsoleCapabilityInvocationEntity row, boolean owner) { }

    public static class Conflict extends RuntimeException {
        private final String code;
        public Conflict(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}

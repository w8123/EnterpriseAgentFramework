package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.execution.trace.WorkflowTraceSanitizer;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.workflow.WorkflowSemanticValues;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeRunLifecycleService {

    private final RuntimeRunMapper runMapper;
    private final ObjectMapper objectMapper;

    /**
     * Closes the durable root run when its pending interaction expires.
     * The conditional update makes this idempotent and prevents a late scheduler from
     * overwriting a run that has already resumed or completed.
     */
    public int expireWaitingInteraction(String traceId,
                                        String interactionId,
                                        LocalDateTime expiredAt) {
        if (!StringUtils.hasText(traceId)) {
            return 0;
        }
        LocalDateTime endedAt = expiredAt == null ? LocalDateTime.now() : expiredAt;
        return runMapper.update(null, Wrappers.<RuntimeRunEntity>lambdaUpdate()
                .eq(RuntimeRunEntity::getTraceId, traceId.trim())
                .eq(RuntimeRunEntity::getStatus, RuntimeRunStatus.SUSPENDED.name())
                .isNull(RuntimeRunEntity::getEndedAt)
                .set(RuntimeRunEntity::getStatus, RuntimeRunStatus.TIMED_OUT.name())
                .set(RuntimeRunEntity::getSuspensionReason, null)
                .set(RuntimeRunEntity::getOutputSummary, "Interaction expired before user response")
                .set(RuntimeRunEntity::getErrorCode, "RUNTIME_INTERACTION_EXPIRED")
                .set(RuntimeRunEntity::getErrorMessage, "Interaction expired: " + interactionId)
                .set(RuntimeRunEntity::getEndedAt, endedAt)
                .set(RuntimeRunEntity::getUpdatedAt, endedAt));
    }

    public void beginAgent(String traceId,
                           String rootSpanId,
                           LocalDateTime startedAt,
                           RuntimeAgentView agent,
                           RuntimeAgentConfigVersionEntity config,
                           List<RuntimeAgentWorkflowToolEntity> workflowTools,
                           Map<String, Object> input) {
        beginAgent(traceId, rootSpanId, startedAt, agent, config, workflowTools, input, null);
    }

    public void beginAgent(String traceId,
                           String rootSpanId,
                           LocalDateTime startedAt,
                           RuntimeAgentView agent,
                           RuntimeAgentConfigVersionEntity config,
                           List<RuntimeAgentWorkflowToolEntity> workflowTools,
                           Map<String, Object> input,
                           WorkflowExecutionIdentity identity) {
        RuntimeRunEntity run = baseRun(traceId, input, startedAt, identity);
        run.setRunType("AGENT");
        run.setStatus(RuntimeRunStatus.RUNNING.name());
        run.setProjectId(agent.projectId());
        run.setProjectCode(firstText(input.get("projectCode"), agent.projectCode()));
        run.setAgentId(agent.id());
        run.setAgentKeySlug(agent.keySlug());
        run.setAgentName(agent.name());
        run.setAgentConfigVersionId(config.getId());
        run.setAgentConfigVersion(config.getVersionNo());
        run.setRuntimeType(config.getRuntimeType());
        run.setRootSpanId(rootSpanId);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("agentId", agent.id());
        snapshot.put("agentKeySlug", agent.keySlug());
        snapshot.put("agentConfigVersionId", config.getId());
        snapshot.put("agentConfigVersion", config.getVersionNo());
        snapshot.put("runtimeType", config.getRuntimeType());
        snapshot.put("workflowToolCount", workflowTools == null ? 0 : workflowTools.size());
        snapshot.put("workflowToolNames", workflowTools == null ? List.of() : workflowTools.stream()
                .map(RuntimeAgentWorkflowToolEntity::getToolName)
                .filter(StringUtils::hasText)
                .toList());
        run.setSnapshotJson(json(snapshot));
        insert(run, "begin Agent run");
    }

    public void rejectAgent(String traceId,
                            RuntimeAgentView agent,
                            Map<String, Object> input,
                            String code,
                            String message) {
        LocalDateTime now = LocalDateTime.now();
        RuntimeRunEntity run = baseRun(traceId, input, now, null);
        run.setRunType("AGENT");
        run.setStatus(status(false, code));
        run.setSuspensionReason(suspensionReason(code));
        if (agent != null) {
            run.setProjectId(agent.projectId());
            run.setProjectCode(firstText(input.get("projectCode"), agent.projectCode()));
            run.setAgentId(agent.id());
            run.setAgentKeySlug(agent.keySlug());
            run.setAgentName(agent.name());
        }
        String rejectionSummary = WorkflowTraceSanitizer.sanitizeRejectionSummary(code);
        run.setInputSummary(json(WorkflowTraceSanitizer.sanitizeInputSummary(input)));
        run.setOutputSummary(rejectionSummary);
        run.setErrorCode(code);
        run.setErrorMessage(rejectionSummary);
        run.setLatencyMs(0);
        run.setEndedAt(now);
        run.setUpdatedAt(now);
        insert(run, "record rejected Agent run");
    }

    /**
     * Re-opens a suspended Agent root run so the same traceId can finish later.
     */
    public void resumeAgent(String traceId) {
        safe(() -> {
            RuntimeRunEntity run = find(traceId);
            if (run == null || !isSuspendedStatus(run.getStatus())) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            run.setStatus(RuntimeRunStatus.RUNNING.name());
            run.setSuspensionReason(null);
            run.setEndedAt(null);
            run.setErrorCode(null);
            run.setErrorMessage(null);
            run.setUpdatedAt(now);
            runMapper.updateById(run);
        }, "resume Agent run");
    }

    public void finishAgent(String traceId,
                            boolean success,
                            String code,
                            String answer,
                            Map<String, Object> metadata,
                            LocalDateTime endedAt) {
        finishAgent(traceId, success, code, answer, metadata, endedAt, null, null, null);
    }

    public void finishAgent(String traceId,
                            boolean success,
                            String code,
                            String answer,
                            Map<String, Object> metadata,
                            LocalDateTime endedAt,
                            WorkflowExecutionIdentity identity) {
        finishAgent(traceId, success, code, answer, metadata, endedAt, identity, null, null);
    }

    public void finishAgent(String traceId,
                            boolean success,
                            String code,
                            String answer,
                            Map<String, Object> metadata,
                            LocalDateTime endedAt,
                            WorkflowExecutionIdentity identity,
                            Integer latencyMs,
                            LocalDateTime startedAtHint) {
        if (!StringUtils.hasText(traceId)) {
            return;
        }
        try {
            LocalDateTime ended = endedAt == null ? LocalDateTime.now() : endedAt;
            String resolvedStatus = status(success, code);
            boolean suspended = isSuspendedStatus(resolvedStatus);
            String resolvedSuspensionReason = suspended ? suspensionReason(code) : null;
            String safeAnswer = WorkflowTraceSanitizer.sanitizeAnswer(answer);
            String sessionId = text(metadata == null ? null : metadata.get("sessionId"));
            RuntimeRunFinishCounts counts = resolveFinishCounts(traceId, metadata);
            Integer resolvedLatency = suspended
                    ? null
                    : (latencyMs != null
                    ? latencyMs
                    : (startedAtHint == null ? null : toInt(ChronoUnit.MILLIS.between(startedAtHint, ended))));
            String trustedUserId = identity != null && identity.userTrusted() && StringUtils.hasText(identity.userId())
                    ? identity.userId().trim()
                    : null;
            // token_cost is NOT NULL — never write null through LambdaUpdateWrapper.set.
            int tokenCost = resolveTokenCost(metadata);
            // Conditional update by traceId — no full-entity reload before finish.
            int updated = runMapper.update(null, Wrappers.<RuntimeRunEntity>lambdaUpdate()
                    .eq(RuntimeRunEntity::getTraceId, traceId.trim())
                    .set(RuntimeRunEntity::getStatus, resolvedStatus)
                    .set(RuntimeRunEntity::getSuspensionReason, resolvedSuspensionReason)
                    .set(StringUtils.hasText(sessionId), RuntimeRunEntity::getSessionId, sessionId)
                    .set(RuntimeRunEntity::getUserId, trustedUserId)
                    .set(RuntimeRunEntity::getExternalUserId, trustedUserId)
                    .set(RuntimeRunEntity::getGlobalUserId, trustedUserId)
                    .set(RuntimeRunEntity::getOutputSummary, safeAnswer)
                    .set(RuntimeRunEntity::getErrorCode, success || suspended ? null : code)
                    .set(RuntimeRunEntity::getErrorMessage, success || suspended ? null : safeAnswer)
                    .set(RuntimeRunEntity::getLatencyMs, resolvedLatency)
                    .set(RuntimeRunEntity::getTokenCost, tokenCost)
                    .set(RuntimeRunEntity::getPlanCount, intValue(metadata, "planCount"))
                    .set(RuntimeRunEntity::getReplanCount, intValue(metadata, "replanCount"))
                    .set(RuntimeRunEntity::getWorkflowCallCount, intValue(metadata, "workflowCallCount"))
                    .set(RuntimeRunEntity::getToolCallCount, counts.toolCallCountInt())
                    .set(RuntimeRunEntity::getGuardDenyCount, counts.guardDenyCountInt())
                    .set(RuntimeRunEntity::getApprovalCount, counts.approvalCountInt())
                    .set(RuntimeRunEntity::getMetadataJson, json(WorkflowTraceSanitizer.sanitizeRunMetadata(metadata)))
                    .set(RuntimeRunEntity::getEndedAt, suspended ? null : ended)
                    .set(RuntimeRunEntity::getUpdatedAt, ended));
            if (updated <= 0) {
                log.warn("Cannot finish Agent run: no runtime_run row matched traceId={}", traceId);
            }
        } catch (Exception ex) {
            log.warn("Failed to finish Agent run for traceId={}: {}", traceId, ex.getMessage());
        }
    }

    /**
     * Safe non-negative token total for {@code runtime_run.token_cost} (NOT NULL).
     * Prefers an explicit {@code tokenCost} integer; otherwise walks usage trees.
     * Never returns null — missing usage becomes {@code 0}.
     */
    public static int resolveTokenCost(Map<String, Object> metadata) {
        if (metadata != null) {
            Object explicit = metadata.get("tokenCost");
            if (explicit instanceof Number number) {
                return Math.max(0, (int) Math.min(Integer.MAX_VALUE, number.longValue()));
            }
            if (explicit != null) {
                try {
                    return Math.max(0, Integer.parseInt(String.valueOf(explicit).trim()));
                } catch (NumberFormatException ignored) {
                    // fall through to tree walk
                }
            }
            Integer preferred = preferCanonicalUsageTotal(metadata);
            if (preferred != null) {
                return preferred;
            }
        }
        long total = tokenValueStatic(metadata);
        return total <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * Prefer a single canonical usage total to avoid double-counting nested model round copies.
     */
    private static Integer preferCanonicalUsageTotal(Map<String, Object> metadata) {
        Object model = metadata.get("model");
        if (model instanceof Map<?, ?> modelMap) {
            Integer fromModel = usageTotal(modelMap.get("_chat_usage"));
            if (fromModel == null) {
                fromModel = usageTotal(modelMap.get("usage"));
            }
            if (fromModel == null) {
                fromModel = usageTotalFromBean(modelMap.get("_chat_usage"));
            }
            if (fromModel != null) {
                return fromModel;
            }
        }
        Integer top = usageTotal(metadata.get("usage"));
        if (top != null) {
            return top;
        }
        return null;
    }

    private static Integer usageTotal(Object usage) {
        if (!(usage instanceof Map<?, ?> map)) {
            return null;
        }
        Object total = firstPresent(map, "totalTokens", "total_tokens");
        if (total instanceof Number number) {
            return Math.max(0, (int) Math.min(Integer.MAX_VALUE, number.longValue()));
        }
        Object input = firstPresent(map, "inputTokens", "promptTokens", "prompt_tokens");
        Object output = firstPresent(map, "outputTokens", "completionTokens", "completion_tokens");
        if (input instanceof Number || output instanceof Number) {
            long sum = 0L;
            if (input instanceof Number number) {
                sum += number.longValue();
            }
            if (output instanceof Number number) {
                sum += number.longValue();
            }
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, sum));
        }
        return null;
    }

    private static Integer usageTotalFromBean(Object usage) {
        if (usage == null || usage instanceof Map<?, ?> || usage instanceof Iterable<?>) {
            return null;
        }
        try {
            Object total = readBeanNumber(usage, "getTotalTokens", "totalTokens");
            if (total instanceof Number number) {
                return Math.max(0, (int) Math.min(Integer.MAX_VALUE, number.longValue()));
            }
            Object input = readBeanNumber(usage, "getInputTokens", "getPromptTokens", "inputTokens", "promptTokens");
            Object output = readBeanNumber(usage, "getOutputTokens", "getCompletionTokens", "outputTokens", "completionTokens");
            if (input instanceof Number || output instanceof Number) {
                long sum = 0L;
                if (input instanceof Number number) {
                    sum += number.longValue();
                }
                if (output instanceof Number number) {
                    sum += number.longValue();
                }
                return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, sum));
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static Object readBeanNumber(Object bean, String... accessors) {
        for (String accessor : accessors) {
            try {
                if (accessor.startsWith("get")) {
                    var method = bean.getClass().getMethod(accessor);
                    Object value = method.invoke(bean);
                    if (value instanceof Number) {
                        return value;
                    }
                }
            } catch (ReflectiveOperationException ignored) {
                // try next
            }
        }
        return null;
    }

    private static Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) {
                return map.get(key);
            }
        }
        return null;
    }

    public void beginWorkflow(String traceId,
                              String rootSpanId,
                              String entryType,
                              String workflowId,
                              String workflowKeySlug,
                              String workflowName,
                              String projectCode,
                              String executionEngine,
                              String graphSpecJson,
                              Map<String, Object> input) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> normalized = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
        normalized.put("entryType", firstText(entryType, "WORKFLOW_STUDIO"));
        normalized.putIfAbsent("projectCode", projectCode);
        RuntimeRunEntity run = baseRun(traceId, normalized, now, null);
        run.setRunType("WORKFLOW");
        run.setStatus(RuntimeRunStatus.RUNNING.name());
        run.setWorkflowId(trim(workflowId));
        run.setWorkflowKeySlug(trim(workflowKeySlug));
        run.setWorkflowName(trim(workflowName));
        run.setRuntimeType(WorkflowSemanticValues.normalizeExecutionEngine(
                firstText(executionEngine, WorkflowSemanticValues.ENGINE_GRAPH_SPEC)));
        run.setRootSpanId(rootSpanId);
        Map<String, Object> snapshot = new LinkedHashMap<>(WorkflowTraceSanitizer.sanitizeWorkflowSnapshot(graphSpecJson));
        snapshot.put("workflowId", trim(workflowId));
        run.setSnapshotJson(json(snapshot));
        insert(run, "begin Workflow run");
    }

    public void finishWorkflow(String traceId,
                               boolean success,
                               String code,
                               String answer,
                               int nodeCount,
                               Map<String, Object> metadata) {
        safe(() -> {
            RuntimeRunEntity run = find(traceId);
            if (run == null) return;
            LocalDateTime ended = LocalDateTime.now();
            String resolvedStatus = status(success, code);
            boolean suspended = isSuspendedStatus(resolvedStatus);
            run.setStatus(resolvedStatus);
            run.setSuspensionReason(suspended ? suspensionReason(code) : null);
            String safeAnswer = WorkflowTraceSanitizer.sanitizeAnswer(answer);
            run.setOutputSummary(safeAnswer);
            run.setErrorCode(success || suspended ? null : code);
            run.setErrorMessage(success || suspended ? null : safeAnswer);
            run.setLatencyMs(suspended ? null : toInt(ChronoUnit.MILLIS.between(run.getStartedAt(), ended)));
            run.setTokenCost(resolveTokenCost(metadata));
            Map<String, Object> meta = new LinkedHashMap<>(
                    WorkflowTraceSanitizer.sanitizeRunMetadata(metadata));
            meta.putIfAbsent("nodeCount", nodeCount);
            run.setMetadataJson(json(meta));
            run.setEndedAt(suspended ? null : ended);
            run.setUpdatedAt(ended);
            runMapper.updateById(run);
        }, "finish Workflow run");
    }

    private RuntimeRunEntity baseRun(String traceId, Map<String, Object> input, LocalDateTime startedAt,
                                     WorkflowExecutionIdentity identity) {
        Map<String, Object> body = input == null ? Map.of() : input;
        LocalDateTime now = startedAt == null ? LocalDateTime.now() : startedAt;
        RuntimeRunEntity run = new RuntimeRunEntity();
        run.setTraceId(traceId);
        run.setEntryType(entryType(body));
        run.setProjectCode(text(body.get("projectCode")));
        run.setTenantId(text(body.get("tenantId")));
        run.setAppId(firstText(body.get("appId"), body.get("projectCode")));
        run.setSessionId(text(body.get("sessionId")));
        applyTrustedUser(run, identity);
        run.setPageInstanceId(text(body.get("pageInstanceId")));
        run.setOrigin(text(body.get("origin")));
        run.setInputSummary(json(WorkflowTraceSanitizer.sanitizeInputSummary(body)));
        run.setReplayOfTraceId(text(body.get("replayOfTraceId")));
        run.setPlanCount(0);
        run.setReplanCount(0);
        run.setWorkflowCallCount(0);
        run.setToolCallCount(0);
        run.setGuardDenyCount(0);
        run.setApprovalCount(0);
        run.setTokenCost(0);
        run.setStartedAt(now);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        return run;
    }

    private void applyTrustedUser(RuntimeRunEntity run, WorkflowExecutionIdentity identity) {
        if (identity != null && identity.userTrusted() && StringUtils.hasText(identity.userId())) {
            run.setUserId(identity.userId());
            run.setExternalUserId(identity.userId());
            run.setGlobalUserId(identity.userId());
        } else {
            run.setUserId(null);
            run.setExternalUserId(null);
            run.setGlobalUserId(null);
        }
    }

    private RuntimeRunEntity find(String traceId) {
        if (!StringUtils.hasText(traceId)) return null;
        return runMapper.selectOne(Wrappers.<RuntimeRunEntity>lambdaQuery()
                .eq(RuntimeRunEntity::getTraceId, traceId.trim())
                .last("LIMIT 1"));
    }

    private void insert(RuntimeRunEntity run, String action) {
        safe(() -> {
            try {
                // Fresh request traceIds are unique; avoid unconditional find-before-insert.
                runMapper.insert(run);
            } catch (DuplicateKeyException duplicate) {
                log.warn("Duplicate runtime_run for traceId={}, refusing silent overwrite", run.getTraceId());
            }
        }, action);
    }

    /**
     * Prefer trusted in-memory counters from Supervisor metadata when present; otherwise one aggregate query.
     * Never runs three independent COUNT queries.
     */
    private RuntimeRunFinishCounts resolveFinishCounts(String traceId, Map<String, Object> metadata) {
        boolean hasTool = metadata != null && metadata.containsKey("toolCallCount");
        boolean hasDeny = metadata != null && metadata.containsKey("guardDenyCount");
        boolean hasApproval = metadata != null && metadata.containsKey("approvalCount");
        if (hasTool && hasDeny && hasApproval) {
            RuntimeRunFinishCounts counts = new RuntimeRunFinishCounts();
            counts.setToolCallCount((long) intValue(metadata, "toolCallCount"));
            counts.setGuardDenyCount((long) intValue(metadata, "guardDenyCount"));
            counts.setApprovalCount((long) intValue(metadata, "approvalCount"));
            return counts;
        }
        RuntimeRunFinishCounts counts = runMapper.selectFinishCounts(traceId.trim());
        return counts == null ? RuntimeRunFinishCounts.zeros() : counts;
    }

    @SuppressWarnings("unchecked")
    private String entryType(Map<String, Object> input) {
        Map<String, Object> metadata = input.get("metadata") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : Map.of();
        String explicit = firstText(input.get("entryType"), metadata.get("entryType"));
        if (StringUtils.hasText(explicit)) return explicit.toUpperCase();
        if (StringUtils.hasText(text(input.get("replayOfTraceId")))) return "REPLAY";
        if (input.containsKey("evalRunId") || input.containsKey("evalCaseId")) return "EVAL";
        String source = firstText(input.get("source"), metadata.get("source"), input.get("origin"));
        if (source != null) {
            String normalized = source.toUpperCase();
            if (normalized.contains("EMBED")) return "EMBED";
            if (normalized.contains("GATEWAY")) return "GATEWAY";
            if (normalized.contains("A2A")) return "A2A";
            if (normalized.contains("DEBUG")) return "DEBUG";
        }
        if (StringUtils.hasText(text(input.get("pageInstanceId")))) return "EMBED";
        return "API";
    }

    private String status(boolean success, String code) {
        return RuntimeRunStatus.fromResult(success, code).name();
    }

    private static String suspensionReason(String code) {
        RuntimeRunSuspensionReason reason = RuntimeRunSuspensionReason.fromCode(code);
        return reason == null ? null : reason.name();
    }

    private static boolean isSuspendedStatus(String status) {
        return RuntimeRunStatus.parse(status) == RuntimeRunStatus.SUSPENDED;
    }

    private Integer intValue(Map<String, Object> metadata, String key) {
        if (metadata == null) return 0;
        Object value = metadata.get(key);
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? 0 : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static long tokenValueStatic(Object value) {
        if (value instanceof Map<?, ?> map) {
            long directTokens = 0;
            long directTotal = 0;
            long nestedTokens = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object child = entry.getValue();
                if (child instanceof Number number && isTokenKey(key)) {
                    String normalized = key.replace("_", "").toLowerCase();
                    if (normalized.equals("totaltokens")) {
                        directTotal = Math.max(directTotal, number.longValue());
                    } else {
                        directTokens += number.longValue();
                    }
                } else if (!"tokenCost".equals(key)
                        && !"supervisor.modelRounds".equals(key)
                        && !"modelRounds".equals(key)) {
                    // Avoid double-counting an already resolved tokenCost scalar when walking trees.
                    // Skip per-round token copies mirrored for observability.
                    nestedTokens += tokenValueStatic(child);
                }
            }
            return nestedTokens + (directTokens > 0 ? directTokens : directTotal);
        }
        if (value instanceof Iterable<?> iterable) {
            long sum = 0;
            for (Object child : iterable) {
                sum += tokenValueStatic(child);
            }
            return sum;
        }
        return 0;
    }

    private static boolean isTokenKey(String key) {
        String normalized = key.replace("_", "").toLowerCase();
        return normalized.equals("inputtokens") || normalized.equals("outputtokens")
                || normalized.equals("prompttokens") || normalized.equals("completiontokens")
                || normalized.equals("totaltokens");
    }

    private void safe(Runnable action, String description) {
        try { action.run(); }
        catch (Exception ex) { log.warn("Failed to {}: {}", description, ex.getMessage()); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ex) { return "{}"; }
    }

    private int toInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
    }

    private String text(Object value) {
        return value == null ? null : trim(String.valueOf(value));
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            String candidate = text(value);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

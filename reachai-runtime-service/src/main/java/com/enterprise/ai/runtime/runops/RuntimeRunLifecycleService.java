package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.enterprise.ai.runtime.execution.RuntimeAgentRunLifecyclePort;
import com.enterprise.ai.runtime.trace.WorkflowTraceSanitizer;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
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
public class RuntimeRunLifecycleService implements RuntimeAgentRunLifecyclePort {

    private static final List<String> OPEN_STATUSES = List.of("RUNNING", "SUSPENDED");
    private final RuntimeRunMapper runMapper;
    private final ObjectMapper objectMapper;

    /** Lease recovery closes only the abandoned Automation run, never a completed business result. */
    public int failAbandonedAutomation(String traceId, String code, String message, LocalDateTime endedAt) {
        if (!StringUtils.hasText(traceId)) return 0;
        if (!StringUtils.hasText(code) || endedAt == null) throw new IllegalArgumentException("Recovery requires code and time");
        return runMapper.update(null, Wrappers.<RuntimeRunEntity>lambdaUpdate()
                .eq(RuntimeRunEntity::getTraceId, traceId.trim())
                .eq(RuntimeRunEntity::getEntryType, "AUTOMATION")
                .eq(RuntimeRunEntity::getStatus, "RUNNING")
                .isNull(RuntimeRunEntity::getEndedAt)
                .set(RuntimeRunEntity::getStatus, "FAILED")
                .set(RuntimeRunEntity::getErrorCode, code)
                .set(RuntimeRunEntity::getErrorMessage, message)
                .set(RuntimeRunEntity::getEndedAt, endedAt)
                .set(RuntimeRunEntity::getUpdatedAt, endedAt));
    }

    /**
     * Closes the durable root run when its pending interaction expires.
     * The conditional update makes this idempotent and prevents a late scheduler from
     * overwriting a run that has already resumed or completed.
     */
    public int expireWaitingInteraction(String traceId,
                                        String interactionId,
                                        LocalDateTime expiredAt) {
        return timeoutWaitingInteraction(traceId, "RUNTIME_INTERACTION_EXPIRED",
                "Interaction expired: " + interactionId, expiredAt, "Interaction expired before user response");
    }

    public int timeoutWaitingInteraction(String traceId, String code, String message, LocalDateTime expiredAt) {
        return timeoutWaitingInteraction(traceId, code, message, expiredAt, message);
    }

    private int timeoutWaitingInteraction(String traceId, String code, String message,
                                          LocalDateTime expiredAt, String output) {
        return timeoutInteraction(traceId, code, message, expiredAt, output, List.of(RuntimeRunStatus.SUSPENDED.name()));
    }

    /** Called only after the interaction owner fences a resume whose result is now unknown. */
    public int timeoutResumingInteraction(String traceId, String code, String message, LocalDateTime expiredAt) {
        return timeoutInteraction(traceId, code, message, expiredAt, message, OPEN_STATUSES);
    }

    /** The Studio session owner has fenced this attempt; other entry types and terminal runs are untouched. */
    public int timeoutDebugExecution(String traceId, String code, String message, LocalDateTime expiredAt) {
        return timeoutRun(traceId, code, message, expiredAt, message, OPEN_STATUSES, true);
    }

    private int timeoutInteraction(String traceId, String code, String message, LocalDateTime expiredAt,
                                   String output, List<String> openStatuses) {
        return timeoutRun(traceId, code, message, expiredAt, output, openStatuses, false);
    }

    private int timeoutRun(String traceId, String code, String message, LocalDateTime expiredAt,
                           String output, List<String> openStatuses, boolean studioOnly) {
        if (!StringUtils.hasText(traceId)) {
            return 0;
        }
        if (!StringUtils.hasText(code)) throw new IllegalArgumentException("Interaction timeout requires a code");
        LocalDateTime endedAt = expiredAt == null ? LocalDateTime.now() : expiredAt;
        var update = Wrappers.<RuntimeRunEntity>lambdaUpdate()
                .eq(RuntimeRunEntity::getTraceId, traceId.trim())
                .eq(studioOnly, RuntimeRunEntity::getRunType, "WORKFLOW")
                .eq(studioOnly, RuntimeRunEntity::getEntryType, "WORKFLOW_STUDIO")
                .in(RuntimeRunEntity::getStatus, openStatuses)
                .isNull(RuntimeRunEntity::getEndedAt)
                .set(RuntimeRunEntity::getStatus, RuntimeRunStatus.TIMED_OUT.name())
                .set(RuntimeRunEntity::getSuspensionReason, null)
                .set(RuntimeRunEntity::getOutputSummary, output)
                .set(RuntimeRunEntity::getErrorCode, code)
                .set(RuntimeRunEntity::getErrorMessage, message)
                .set(RuntimeRunEntity::getEndedAt, endedAt)
                .set(RuntimeRunEntity::getUpdatedAt, endedAt);
        return runMapper.update(null, update);
    }

    public void beginAgent(String traceId,
                           String rootSpanId,
                           LocalDateTime startedAt,
                           AgentTarget agent,
                           RuntimeRunSnapshots.AgentConfiguration config,
                           Map<String, Object> input) {
        beginAgent(traceId, rootSpanId, startedAt, agent, config, input, null);
    }

    public void beginAgent(String traceId,
                           String rootSpanId,
                           LocalDateTime startedAt,
                           AgentTarget agent,
                           RuntimeRunSnapshots.AgentConfiguration config,
                           Map<String, Object> input,
                           WorkflowExecutionIdentity identity) {
        RuntimeRunEntity run = baseRun(traceId, input, startedAt, identity);
        run.setRunType("AGENT");
        run.setStatus(RuntimeRunStatus.RUNNING.name());
        run.setProjectId(agent.projectId());
        run.setProjectCode(agent.projectCode());
        run.setAppId(firstText(run.getAppId(), agent.projectCode()));
        run.setAgentId(agent.id());
        run.setAgentKeySlug(agent.keySlug());
        run.setAgentName(agent.name());
        run.setAgentConfigVersionId(config.versionId());
        run.setAgentConfigVersion(config.versionNo());
        run.setRuntimeType(config.runtimeType());
        run.setRootSpanId(rootSpanId);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("agentId", agent.id());
        snapshot.put("agentKeySlug", agent.keySlug());
        snapshot.put("agentConfigVersionId", config.versionId());
        snapshot.put("agentConfigVersion", config.versionNo());
        snapshot.put("runtimeType", config.runtimeType());
        snapshot.put("workflowToolCount", config.workflowToolCount());
        snapshot.put("workflowToolNames", config.workflowToolNames());
        run.setSnapshotJson(json(snapshot));
        insert(run, "begin Agent run");
    }

    public void rejectAgent(String traceId,
                            AgentTarget agent,
                            Map<String, Object> input,
                            String code,
                            String message,
                            WorkflowExecutionIdentity identity) {
        LocalDateTime now = LocalDateTime.now();
        RuntimeRunEntity run = baseRun(traceId, input, now, identity);
        run.setRunType("AGENT");
        run.setStatus(status(false, code));
        run.setSuspensionReason(suspensionReason(code));
        if (agent != null) {
            run.setProjectId(agent.projectId());
            run.setProjectCode(agent.projectCode());
            run.setAppId(firstText(run.getAppId(), agent.projectCode()));
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
            runMapper.update(null, Wrappers.<RuntimeRunEntity>lambdaUpdate()
                    .eq(RuntimeRunEntity::getId, run.getId())
                    .eq(RuntimeRunEntity::getTraceId, run.getTraceId())
                    .eq(RuntimeRunEntity::getRunType, "AGENT")
                    .eq(RuntimeRunEntity::getStatus, run.getStatus())
                    .set(RuntimeRunEntity::getStatus, RuntimeRunStatus.RUNNING.name())
                    .set(RuntimeRunEntity::getSuspensionReason, null)
                    .set(RuntimeRunEntity::getEndedAt, null)
                    .set(RuntimeRunEntity::getErrorCode, null)
                    .set(RuntimeRunEntity::getErrorMessage, null)
                    .set(RuntimeRunEntity::getUpdatedAt, now));
        }, "resume Agent run");
    }

    /** Persists the exact Skill versions alongside the Agent config snapshot. */
    public void recordSkillBindings(String traceId, List<RuntimeRunSnapshots.SkillBinding> bindings) {
        if (!StringUtils.hasText(traceId)) return;
        safe(() -> {
            RuntimeRunEntity run = find(traceId);
            if (run == null) return;
            Map<String, Object> snapshot = jsonMap(run.getSnapshotJson());
            List<Map<String, Object>> versions = bindings == null ? List.of() : bindings.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(RuntimeRunSnapshots.SkillBinding::runMetadata)
                    .toList();
            snapshot.put("skillBindingCount", versions.size());
            snapshot.put("skillBindings", versions);
            runMapper.update(null, Wrappers.<RuntimeRunEntity>lambdaUpdate()
                    .eq(RuntimeRunEntity::getId, run.getId())
                    .eq(RuntimeRunEntity::getTraceId, traceId.trim())
                    .eq(RuntimeRunEntity::getRunType, "AGENT")
                    .set(RuntimeRunEntity::getSnapshotJson, json(snapshot))
                    .set(RuntimeRunEntity::getUpdatedAt, LocalDateTime.now()));
        }, "record Agent Skill binding snapshot");
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
            String safeAnswer = WorkflowTraceSanitizer.sanitizeAnswer(answer);
            String sessionId = text(metadata == null ? null : metadata.get("sessionId"));
            RuntimeRunFinishCounts counts = resolveFinishCounts(traceId, metadata);
            Integer resolvedLatency = suspended
                    ? null
                    : (latencyMs != null
                    ? latencyMs
                    : (startedAtHint == null ? null : toInt(ChronoUnit.MILLIS.between(startedAtHint, ended))));
            // Missing measurements on a continuation are not zero totals.
            boolean tokenReported = hasReportedTokenCost(metadata);
            int tokenCost = resolveTokenCost(metadata);
            Map<String, Object> safeMetadata = new LinkedHashMap<>(WorkflowTraceSanitizer.sanitizeRunMetadata(
                    jsonMap(counts.getMetadataJson())));
            safeMetadata.putAll(WorkflowTraceSanitizer.sanitizeRunMetadata(metadata));
            if (tokenReported) safeMetadata.put("tokenCost", tokenCost);
            // The execution port also completes standalone Workflow interaction resumes.
            // Only open roots may transition; no full-entity reload before Agent finish.
            // Entry attribution is immutable: completion must not clear or replace its original user.
            int updated = runMapper.update(null, completionUpdate(traceId, resolvedStatus, code,
                    safeAnswer, safeAnswer, ended, resolvedLatency)
                    .in(RuntimeRunEntity::getRunType, List.of("AGENT", "WORKFLOW"))
                    .set(StringUtils.hasText(sessionId), RuntimeRunEntity::getSessionId, sessionId)
                    .set(tokenReported, RuntimeRunEntity::getTokenCost, tokenCost)
                    .set(reported(metadata, "planCount"), RuntimeRunEntity::getPlanCount, intValue(metadata, "planCount"))
                    .set(reported(metadata, "replanCount"), RuntimeRunEntity::getReplanCount, intValue(metadata, "replanCount"))
                    .set(reported(metadata, "workflowCallCount"), RuntimeRunEntity::getWorkflowCallCount, intValue(metadata, "workflowCallCount"))
                    .set(RuntimeRunEntity::getToolCallCount, counts.toolCallCountInt())
                    .set(RuntimeRunEntity::getGuardDenyCount, counts.guardDenyCountInt())
                    .set(RuntimeRunEntity::getApprovalCount, counts.approvalCountInt())
                    .set(!safeMetadata.isEmpty(), RuntimeRunEntity::getMetadataJson, json(safeMetadata)));
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

    /** Distinguishes an explicitly measured zero from a completion with no model usage. */
    public static boolean hasReportedTokenCost(Map<String, Object> metadata) {
        if (metadata == null) return false;
        Object explicit = metadata.get("tokenCost");
        if (explicit instanceof Number) return true;
        if (explicit != null) {
            try { Integer.parseInt(String.valueOf(explicit).trim()); return true; }
            catch (NumberFormatException ignored) { /* Fall through to supported usage trees. */ }
        }
        return preferCanonicalUsageTotal(metadata) != null || hasTokenMetric(metadata);
    }

    private static boolean hasTokenMetric(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (entry.getValue() instanceof Number && isTokenKey(key)) return true;
                if (!"tokenCost".equals(key) && !"supervisor.modelRounds".equals(key)
                        && !"modelRounds".equals(key) && hasTokenMetric(entry.getValue())) return true;
            }
        } else if (value instanceof Iterable<?> values) {
            for (Object child : values) if (hasTokenMetric(child)) return true;
        }
        return false;
    }

    private static boolean reported(Map<String, Object> metadata, String key) {
        return metadata != null && metadata.get(key) != null;
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
                              RuntimeRunSnapshots.Workflow workflow,
                              Map<String, Object> input) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> normalized = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
        normalized.put("entryType", firstText(entryType, "WORKFLOW_STUDIO"));
        RuntimeRunEntity run = baseRun(traceId, normalized, now, null);
        run.setProjectId(workflow.projectId());
        run.setProjectCode(trim(workflow.projectCode()));
        run.setAppId(firstText(run.getAppId(), workflow.projectCode()));
        run.setRunType("WORKFLOW");
        run.setStatus(RuntimeRunStatus.RUNNING.name());
        run.setWorkflowId(trim(workflow.workflowId()));
        run.setWorkflowKeySlug(trim(workflow.keySlug()));
        run.setWorkflowName(trim(workflow.name()));
        run.setRuntimeType(workflow.executionEngine());
        run.setRootSpanId(rootSpanId);
        Map<String, Object> snapshot = new LinkedHashMap<>(WorkflowTraceSanitizer.sanitizeWorkflowSnapshot(workflow.graphSpecJson()));
        snapshot.put("workflowId", trim(workflow.workflowId()));
        run.setSnapshotJson(json(snapshot));
        insert(run, "begin Workflow run");
    }

    /** Begins a non-Studio Workflow run against one immutable published version. */
    public void beginPublishedWorkflow(String traceId,
                                       String rootSpanId,
                                       String entryType,
                                       RuntimeRunSnapshots.PublishedWorkflow workflow,
                                       Map<String, Object> input,
                                       WorkflowExecutionIdentity identity) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> normalized = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
        normalized.put("entryType", firstText(entryType, "API"));
        RuntimeRunEntity run = baseRun(traceId, normalized, now, identity);
        run.setRunType("WORKFLOW");
        run.setStatus(RuntimeRunStatus.RUNNING.name());
        run.setProjectId(workflow.projectId());
        run.setProjectCode(workflow.projectCode());
        run.setAppId(firstText(run.getAppId(), workflow.projectCode()));
        run.setWorkflowId(trim(workflow.workflowId()));
        run.setWorkflowKeySlug(trim(workflow.keySlug()));
        run.setWorkflowName(trim(workflow.name()));
        run.setWorkflowVersionId(workflow.versionId());
        run.setWorkflowVersion(workflow.version());
        run.setRuntimeType(workflow.executionEngine());
        run.setRootSpanId(rootSpanId);
        Map<String, Object> snapshot = new LinkedHashMap<>(
                WorkflowTraceSanitizer.sanitizeWorkflowSnapshot(workflow.graphSpecJson()));
        snapshot.put("workflowId", trim(workflow.workflowId()));
        snapshot.put("workflowKeySlug", trim(workflow.keySlug()));
        snapshot.put("workflowVersionId", workflow.versionId());
        snapshot.put("workflowVersion", workflow.version());
        run.setSnapshotJson(json(snapshot));
        insert(run, "begin published Workflow run");
    }

    /** Begins one externally initiated MCP tools/call as its own durable root run. */
    public Long beginMcp(String traceId,
                         String rootSpanId,
                         String sourceKind,
                         String sourceRef,
                         Long workflowVersionId,
                         String toolName,
                         Map<String, Object> input,
                         Map<String, Object> metadata,
                         WorkflowExecutionIdentity identity) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> normalized = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
        normalized.put("entryType", "MCP");
        normalized.put("projectCode", identity == null ? null : identity.projectCode());
        normalized.put("tenantId", identity == null ? null : identity.tenantId());
        RuntimeRunEntity run = baseRun(traceId, normalized, now, identity);
        run.setRunType("MCP");
        run.setStatus(RuntimeRunStatus.RUNNING.name());
        run.setProjectId(identity == null ? null : identity.projectId());
        run.setProjectCode(identity == null ? null : identity.projectCode());
        run.setTenantId(identity == null ? null : identity.tenantId());
        run.setRuntimeType("WORKFLOW".equalsIgnoreCase(sourceKind) ? "GRAPH_SPEC" : "CAPABILITY");
        run.setRootSpanId(rootSpanId);
        run.setToolCallCount(1);
        if ("WORKFLOW".equalsIgnoreCase(sourceKind)) {
            run.setWorkflowId(trim(sourceRef));
            run.setWorkflowVersionId(workflowVersionId);
            run.setWorkflowCallCount(1);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sourceKind", trim(sourceKind));
        snapshot.put("sourceRef", trim(sourceRef));
        snapshot.put("toolName", trim(toolName));
        if (workflowVersionId != null) snapshot.put("workflowVersionId", workflowVersionId);
        copyScalar(snapshot, metadata, "publicationId");
        copyScalar(snapshot, metadata, "revisionNo");
        copyScalar(snapshot, metadata, "environment");
        run.setSnapshotJson(json(snapshot));
        insert(run, "begin MCP run");
        RuntimeRunEntity inserted = find(traceId);
        return inserted == null ? null : inserted.getId();
    }

    /** Completes the MCP root without persisting tool arguments or response payloads. */
    public void finishMcp(String traceId, boolean success, String code,
                          boolean hasOutput, Map<String, Object> metadata) {
        safe(() -> {
            RuntimeRunEntity run = find(traceId);
            if (run == null) return;
            LocalDateTime ended = LocalDateTime.now();
            Map<String, Object> safeMetadata = new LinkedHashMap<>();
            copyScalar(safeMetadata, metadata, "sourceKind");
            copyScalar(safeMetadata, metadata, "toolName");
            copyScalar(safeMetadata, metadata, "publicationId");
            copyScalar(safeMetadata, metadata, "revisionNo");
            copyScalar(safeMetadata, metadata, "environment");
            copyScalar(safeMetadata, metadata, "nodeCount");
            runMapper.update(null, completionUpdate(traceId, status(success, code), trim(code),
                    hasOutput ? "[omitted]" : "", WorkflowTraceSanitizer.sanitizeRejectionSummary(code),
                    ended, elapsed(run, ended))
                    .eq(RuntimeRunEntity::getId, run.getId())
                    .eq(RuntimeRunEntity::getRunType, "MCP")
                    .eq(RuntimeRunEntity::getStatus, run.getStatus())
                    .set(RuntimeRunEntity::getMetadataJson, json(safeMetadata)));
        }, "finish MCP run");
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
            String safeAnswer = WorkflowTraceSanitizer.sanitizeAnswer(answer);
            Map<String, Object> meta = new LinkedHashMap<>(
                    WorkflowTraceSanitizer.sanitizeRunMetadata(metadata));
            meta.putIfAbsent("nodeCount", nodeCount);
            runMapper.update(null, completionUpdate(traceId, resolvedStatus, code,
                    safeAnswer, safeAnswer, ended, elapsed(run, ended))
                    .eq(RuntimeRunEntity::getId, run.getId())
                    .eq(RuntimeRunEntity::getRunType, "WORKFLOW")
                    .eq(RuntimeRunEntity::getStatus, run.getStatus())
                    .set(RuntimeRunEntity::getTokenCost, resolveTokenCost(metadata))
                    .set(RuntimeRunEntity::getMetadataJson, json(meta)));
        }, "finish Workflow run");
    }

    /** Shared lifecycle writes must clear SQL NULLs and never replace an already terminal outcome. */
    private LambdaUpdateWrapper<RuntimeRunEntity> completionUpdate(
            String traceId, String resolvedStatus, String code, String outputSummary, String errorSummary,
            LocalDateTime endedAt, Integer latencyMs) {
        boolean suspended = isSuspendedStatus(resolvedStatus);
        boolean clearError = suspended || RuntimeRunStatus.COMPLETED.name().equals(resolvedStatus);
        return Wrappers.<RuntimeRunEntity>lambdaUpdate()
                .eq(RuntimeRunEntity::getTraceId, traceId.trim())
                .in(RuntimeRunEntity::getStatus, OPEN_STATUSES)
                .set(RuntimeRunEntity::getStatus, resolvedStatus)
                .set(RuntimeRunEntity::getSuspensionReason, suspended ? suspensionReason(code) : null)
                .set(RuntimeRunEntity::getOutputSummary, outputSummary)
                .set(RuntimeRunEntity::getErrorCode, clearError ? null : code)
                .set(RuntimeRunEntity::getErrorMessage, clearError ? null : errorSummary)
                .set(RuntimeRunEntity::getLatencyMs, suspended ? null : latencyMs)
                .set(RuntimeRunEntity::getEndedAt, suspended ? null : endedAt)
                .set(RuntimeRunEntity::getUpdatedAt, endedAt);
    }

    private int elapsed(RuntimeRunEntity run, LocalDateTime endedAt) {
        return run.getStartedAt() == null ? 0 : toInt(ChronoUnit.MILLIS.between(run.getStartedAt(), endedAt));
    }

    private RuntimeRunEntity baseRun(String traceId, Map<String, Object> input, LocalDateTime startedAt,
                                     WorkflowExecutionIdentity identity) {
        Map<String, Object> body = input == null ? Map.of() : input;
        LocalDateTime now = startedAt == null ? LocalDateTime.now() : startedAt;
        RuntimeRunEntity run = new RuntimeRunEntity();
        run.setTraceId(traceId);
        run.setEntryType(entryType(body));
        // Target ownership is supplied explicitly by each entry; business input never assigns scope.
        run.setTenantId(identity != null && identity.projectTrusted() ? identity.tenantId() : null);
        run.setAppId(text(body.get("appId")));
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

    private void copyScalar(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source == null || target == null || key == null) return;
        Object value = source.get(key);
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            target.put(key, value);
        }
    }

    private Map<String, Object> jsonMap(String value) {
        if (!StringUtils.hasText(value)) return new LinkedHashMap<>();
        try {
            Object parsed = objectMapper.readValue(value, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, item) -> result.put(String.valueOf(key), item));
                return result;
            }
        } catch (Exception ignored) {
            // Replace corrupt observability metadata with a safe fresh object.
        }
        return new LinkedHashMap<>();
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
        boolean hasTool = reported(metadata, "toolCallCount");
        boolean hasDeny = reported(metadata, "guardDenyCount");
        boolean hasApproval = reported(metadata, "approvalCount");
        if (hasTool && hasDeny && hasApproval && reported(metadata, "planCount")
                && reported(metadata, "replanCount") && reported(metadata, "workflowCallCount")
                && hasReportedTokenCost(metadata)) {
            RuntimeRunFinishCounts counts = new RuntimeRunFinishCounts();
            counts.setToolCallCount((long) intValue(metadata, "toolCallCount"));
            counts.setGuardDenyCount((long) intValue(metadata, "guardDenyCount"));
            counts.setApprovalCount((long) intValue(metadata, "approvalCount"));
            return counts;
        }
        RuntimeRunFinishCounts counts = runMapper.selectFinishCounts(traceId.trim());
        if (counts == null) counts = RuntimeRunFinishCounts.zeros();
        if (hasTool) counts.setToolCallCount((long) intValue(metadata, "toolCallCount"));
        if (hasDeny) counts.setGuardDenyCount((long) intValue(metadata, "guardDenyCount"));
        if (hasApproval) counts.setApprovalCount((long) intValue(metadata, "approvalCount"));
        return counts;
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

}

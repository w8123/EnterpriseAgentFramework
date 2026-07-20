package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.execution.trace.WorkflowTraceSanitizer;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogEntity;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final RuntimeToolCallLogMapper toolCallLogMapper;
    private final RuntimeGuardDecisionLogMapper guardDecisionLogMapper;
    private final ObjectMapper objectMapper;

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
        run.setStatus("RUNNING");
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
     * Re-opens a WAITING_* Agent root run so the same traceId can finish later.
     */
    public void resumeAgent(String traceId) {
        safe(() -> {
            RuntimeRunEntity run = find(traceId);
            if (run == null || !isWaitingStatus(run.getStatus())) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            run.setStatus("RUNNING");
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
        finishAgent(traceId, success, code, answer, metadata, endedAt, null);
    }

    public void finishAgent(String traceId,
                            boolean success,
                            String code,
                            String answer,
                            Map<String, Object> metadata,
                            LocalDateTime endedAt,
                            WorkflowExecutionIdentity identity) {
        safe(() -> {
            RuntimeRunEntity run = find(traceId);
            if (run == null) {
                log.warn("Cannot finish Agent run because runtime_run is missing: {}", traceId);
                return;
            }
            LocalDateTime ended = endedAt == null ? LocalDateTime.now() : endedAt;
            String resolvedStatus = status(success, code);
            run.setStatus(resolvedStatus);
            run.setSessionId(firstText(metadata == null ? null : metadata.get("sessionId"), run.getSessionId()));
            applyTrustedUser(run, identity);
            String safeAnswer = WorkflowTraceSanitizer.sanitizeAnswer(answer);
            run.setOutputSummary(safeAnswer);
            boolean waiting = isWaitingStatus(resolvedStatus);
            run.setErrorCode(success || waiting ? null : code);
            run.setErrorMessage(success || waiting ? null : safeAnswer);
            run.setLatencyMs(waiting ? null : toInt(ChronoUnit.MILLIS.between(run.getStartedAt(), ended)));
            run.setTokenCost(totalTokens(metadata));
            run.setPlanCount(intValue(metadata, "planCount"));
            run.setReplanCount(intValue(metadata, "replanCount"));
            run.setWorkflowCallCount(intValue(metadata, "workflowCallCount"));
            run.setToolCallCount(countTools(traceId));
            run.setGuardDenyCount(countGuardDecisions(traceId, "DENY"));
            run.setApprovalCount(countGuardDecisions(traceId, "REQUIRE_CONFIRMATION"));
            run.setMetadataJson(json(WorkflowTraceSanitizer.sanitizeRunMetadata(metadata)));
            // WAITING_USER / WAITING_APPROVAL：root run 保持未终结
            run.setEndedAt(waiting ? null : ended);
            run.setUpdatedAt(ended);
            runMapper.updateById(run);
        }, "finish Agent run");
    }

    public void beginWorkflow(String traceId,
                              String rootSpanId,
                              String entryType,
                              String workflowId,
                              String workflowKeySlug,
                              String workflowName,
                              String projectCode,
                              String runtimeType,
                              String graphSpecJson,
                              Map<String, Object> input) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> normalized = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
        normalized.put("entryType", firstText(entryType, "WORKFLOW_STUDIO"));
        normalized.putIfAbsent("projectCode", projectCode);
        RuntimeRunEntity run = baseRun(traceId, normalized, now, null);
        run.setRunType("WORKFLOW");
        run.setStatus("RUNNING");
        run.setWorkflowId(trim(workflowId));
        run.setWorkflowKeySlug(trim(workflowKeySlug));
        run.setWorkflowName(trim(workflowName));
        run.setRuntimeType(firstText(runtimeType, "LANGGRAPH4J"));
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
            boolean waiting = isWaitingStatus(resolvedStatus);
            run.setStatus(resolvedStatus);
            String safeAnswer = WorkflowTraceSanitizer.sanitizeAnswer(answer);
            run.setOutputSummary(safeAnswer);
            run.setErrorCode(success || waiting ? null : code);
            run.setErrorMessage(success || waiting ? null : safeAnswer);
            run.setLatencyMs(waiting ? null : toInt(ChronoUnit.MILLIS.between(run.getStartedAt(), ended)));
            run.setTokenCost(totalTokens(metadata));
            Map<String, Object> meta = new LinkedHashMap<>(
                    WorkflowTraceSanitizer.sanitizeRunMetadata(metadata));
            meta.putIfAbsent("nodeCount", nodeCount);
            run.setMetadataJson(json(meta));
            run.setEndedAt(waiting ? null : ended);
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
            if (find(run.getTraceId()) == null) runMapper.insert(run);
        }, action);
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
        if (success) return "SUCCESS";
        if ("SUPERVISOR_CONFIRMATION_REQUIRED".equalsIgnoreCase(code)) return "WAITING_APPROVAL";
        if ("RUNTIME_GRAPH_INTERACTION_WAITING".equalsIgnoreCase(code)) return "WAITING_USER";
        if (code != null && code.toUpperCase().contains("TIMEOUT")) return "TIMEOUT";
        if (code != null && code.toUpperCase().contains("EXPIRED")) return "TIMEOUT";
        if (code != null && (code.toUpperCase().contains("CANCEL")
                || code.toUpperCase().contains("ACTION_REJECTED"))) return "CANCELLED";
        return "FAILED";
    }

    private static boolean isWaitingStatus(String status) {
        return "WAITING_USER".equalsIgnoreCase(status) || "WAITING_APPROVAL".equalsIgnoreCase(status);
    }

    private Integer intValue(Map<String, Object> metadata, String key) {
        if (metadata == null) return 0;
        Object value = metadata.get(key);
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? 0 : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private Integer countTools(String traceId) {
        Long count = toolCallLogMapper.selectCount(Wrappers.<RuntimeToolCallLogEntity>lambdaQuery()
                .eq(RuntimeToolCallLogEntity::getTraceId, traceId));
        return toInt(count == null ? 0L : count);
    }

    private Integer countGuardDecisions(String traceId, String decision) {
        Long count = guardDecisionLogMapper.selectCount(Wrappers.<RuntimeGuardDecisionLogEntity>lambdaQuery()
                .eq(RuntimeGuardDecisionLogEntity::getTraceId, traceId)
                .eq(RuntimeGuardDecisionLogEntity::getDecision, decision));
        return toInt(count == null ? 0L : count);
    }

    private Integer totalTokens(Object value) {
        long total = tokenValue(value);
        return total <= 0 ? null : toInt(total);
    }

    private long tokenValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            long directTokens = 0;
            long directTotal = 0;
            long nestedTokens = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object child = entry.getValue();
                if (child instanceof Number number && isTokenKey(key)) {
                    String normalized = key.replace("_", "").toLowerCase();
                    if (normalized.equals("totaltokens")) directTotal = Math.max(directTotal, number.longValue());
                    else directTokens += number.longValue();
                } else {
                    nestedTokens += tokenValue(child);
                }
            }
            return nestedTokens + (directTokens > 0 ? directTokens : directTotal);
        }
        if (value instanceof Iterable<?> iterable) {
            long sum = 0;
            for (Object child : iterable) sum += tokenValue(child);
            return sum;
        }
        return 0;
    }

    private boolean isTokenKey(String key) {
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

package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentSkillBindingSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentSkillRepositoryFactory.SkippedSkill;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot;
import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter.RemoteAgentBinding;
import com.enterprise.ai.runtime.execution.RuntimeInteractionExpiryTracePort;
import com.enterprise.ai.runtime.client.control.RuntimeA2aControlClient.SendResponse;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.runops.RuntimeRunSnapshots;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import com.enterprise.ai.runtime.trace.WorkflowTraceSanitizer;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.execution.RuntimeAgentRunLifecyclePort.AgentTarget;
import com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter;
import com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter.ChildSpan;
import com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter.ToolCall;
import com.enterprise.ai.runtime.trace.RuntimeTraceRootService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupervisorExecutionTraceService implements RuntimeInteractionExpiryTracePort {

    private final RuntimeTraceEvidenceWriter evidence;
    private final RuntimeRunLifecycleService runLifecycleService;
    private final ObjectMapper objectMapper;
    private final RuntimeTraceRootService rootSpans;
    private final RuntimeTraceSpanTerminationService spanTermination;

    /**
     * Closes every still-waiting span plus the root RunOps row for an expired interaction.
     * Called only after the interaction-session CAS succeeds, inside the same transaction.
     */
    public void expireWaitingInteraction(String traceId,
                                         String interactionId,
                                         LocalDateTime expiredAt) {
        if (!StringUtils.hasText(traceId)) {
            return;
        }
        LocalDateTime endedAt = expiredAt == null ? LocalDateTime.now() : expiredAt;
        spanTermination.expireWaiting(traceId, interactionId, endedAt);
        runLifecycleService.expireWaitingInteraction(traceId, interactionId, endedAt);
    }

    @Override
    public void expireResumingInteraction(String traceId, String interactionId, LocalDateTime expiredAt) {
        spanTermination.timeoutWaiting(traceId, WorkflowInteractionCodes.RESUME_TIMEOUT,
                WorkflowInteractionCodes.RESUME_TIMEOUT_MESSAGE, expiredAt);
        runLifecycleService.timeoutWaitingInteraction(traceId, WorkflowInteractionCodes.RESUME_TIMEOUT,
                WorkflowInteractionCodes.RESUME_TIMEOUT_MESSAGE, expiredAt);
    }

    @Override
    public void expireSupervisorApprovalResume(String traceId, String interactionId, LocalDateTime expiredAt) {
        spanTermination.timeoutResuming(traceId,
                com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalService.RESUME_TIMEOUT,
                com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalService.RESUME_TIMEOUT_MESSAGE, expiredAt);
        runLifecycleService.timeoutResumingInteraction(traceId,
                com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalService.RESUME_TIMEOUT,
                com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalService.RESUME_TIMEOUT_MESSAGE, expiredAt);
    }

    public TraceHandle begin(RuntimeAgentView agent,
                             RuntimeAgentConfigSnapshot config,
                             List<RuntimeAgentWorkflowToolSnapshot> workflowTools,
                             Map<String, Object> input) {
        return begin(agent, config, workflowTools, input, null);
    }

    public TraceHandle begin(RuntimeAgentView agent,
                             RuntimeAgentConfigSnapshot config,
                             List<RuntimeAgentWorkflowToolSnapshot> workflowTools,
                             Map<String, Object> input,
                             WorkflowExecutionIdentity identity) {
        input = input == null ? Map.of() : input;
        String traceId = firstText(input.get("traceId"), id(32));
        String spanId = id(16);
        LocalDateTime now = LocalDateTime.now();
        var root = rootSpans.startBestEffort(RuntimeTraceRootService.Start.builder()
                .traceId(traceId).spanId(spanId).spanType("SUPERVISOR").runtimeType("AGENTSCOPE")
                .agentId(agent.id()).agentName(agent.name()).modelInstanceId(config.getModelInstanceId())
                .projectCode(agent.projectCode())
                .tenantId(identity != null && identity.projectTrusted() ? identity.tenantId() : null)
                .appId(firstText(input.get("appId"), agent.projectCode()))
                .pageInstanceId(text(input.get("pageInstanceId"))).input(input)
                .metadataJson(json(Map.of(
                "agentConfigVersionId", config.getId(),
                "agentConfigVersion", config.getVersionNo(),
                "policyProfile", config.getPolicyProfile(),
                "toolCatalogMode", config.getToolCatalogMode())))
                .startedAt(now).build());
        RuntimeRunSnapshots.AgentConfiguration runConfig = new RuntimeRunSnapshots.AgentConfiguration(
                config.getId(), config.getVersionNo(), config.getRuntimeType(),
                workflowTools == null ? 0 : workflowTools.size(),
                workflowTools == null ? List.of() : workflowTools.stream()
                        .map(RuntimeAgentWorkflowToolSnapshot::getToolName).filter(StringUtils::hasText).toList());
        runLifecycleService.beginAgent(traceId, spanId, now,
                new AgentTarget(agent.id(), agent.keySlug(), agent.name(), agent.projectId(), agent.projectCode()),
                runConfig, input, identity);
        return new TraceHandle(traceId, spanId, root.id(), now, root.scope());
    }

    /**
     * Continues an existing Supervisor root span/run for the same traceId after WAITING_USER.
     * Returns null when no reusable root exists (caller should {@link #begin}).
     */
    public TraceHandle resume(String traceId) {
        var root = rootSpans.resumeSupervisor(traceId);
        if (root == null) return null;
        runLifecycleService.resumeAgent(root.traceId());
        return new TraceHandle(root.traceId(), root.spanId(), root.id(), root.startedAt(), root.scope());
    }

    public TraceHandle beginOrResume(RuntimeAgentView agent,
                                     RuntimeAgentConfigSnapshot config,
                                     List<RuntimeAgentWorkflowToolSnapshot> workflowTools,
                                     Map<String, Object> input) {
        return beginOrResume(agent, config, workflowTools, input, null);
    }

    public TraceHandle beginOrResume(RuntimeAgentView agent,
                                     RuntimeAgentConfigSnapshot config,
                                     List<RuntimeAgentWorkflowToolSnapshot> workflowTools,
                                     Map<String, Object> input,
                                     WorkflowExecutionIdentity identity) {
        String resumeTraceId = firstText(input == null ? null : input.get("traceId"));
        Object continuation = input == null ? null : input.get("__supervisorContinuation");
        if (continuation instanceof Map<?, ?> map) {
            resumeTraceId = firstText(map.get("traceId"), resumeTraceId);
        }
        if (Boolean.TRUE.equals(input == null ? null : input.get("__resumeExistingTrace"))
                && StringUtils.hasText(resumeTraceId)) {
            TraceHandle resumed = resume(resumeTraceId);
            if (resumed != null) {
                return resumed;
            }
        }
        return begin(agent, config, workflowTools, input, identity);
    }

    /** Adds the immutable Skill binding snapshot to the root trace and RunOps row. */
    public void skillBindings(TraceHandle trace, List<RuntimeAgentSkillBindingSnapshot> bindings) {
        if (trace == null) return;
        List<RuntimeRunSnapshots.SkillBinding> snapshots = skillSnapshots(bindings);
        try {
            if (!rootSpans.recordSkillBindings(new RuntimeTraceRootService.Handle(
                    trace.rootId(), trace.traceId(), trace.rootSpanId(), trace.startedAt()),
                    json(snapshots.stream().map(RuntimeRunSnapshots.SkillBinding::traceMetadata).toList()))) return;
        } catch (Exception failure) {
            log.warn("Failed to record Supervisor Skill binding snapshot: {}", failure.getClass().getSimpleName());
        }
        runLifecycleService.recordSkillBindings(trace.traceId(), snapshots);
    }

    /** Records which bound Skills were actually exposed to AgentScope for this turn. */
    public void skillActivation(TraceHandle trace,
                                RuntimeAgentView agent,
                                RuntimeAgentConfigSnapshot config,
                                Map<String, Object> input,
                                List<RuntimeAgentSkillBindingSnapshot> activeBindings) {
        skillActivation(trace, agent, config, input, activeBindings, List.of());
    }

    public void skillActivation(TraceHandle trace,
                                RuntimeAgentView agent,
                                RuntimeAgentConfigSnapshot config,
                                Map<String, Object> input,
                                List<RuntimeAgentSkillBindingSnapshot> activeBindings,
                                List<SkippedSkill> skippedSkills) {
        if (trace == null) return;
        List<RuntimeAgentSkillBindingSnapshot> active = activeBindings == null ? List.of() : activeBindings;
        List<SkippedSkill> skipped = skippedSkills == null ? List.of() : skippedSkills;
        if (active.isEmpty() && skipped.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        ChildSpan.ChildSpanBuilder span = baseSpan(trace, id(16), trace.rootSpanId(),
                "SKILL_CONTEXT", agent, config, input);
        List<Map<String, Object>> snapshots = skillSnapshots(active).stream()
                .map(RuntimeRunSnapshots.SkillBinding::traceMetadata).toList();
        List<Map<String, Object>> skippedSnapshots = skipped.stream()
                .map(skill -> Map.<String, Object>of(
                        "skillId", skill.skillId(),
                        "skillVersionId", skill.skillVersionId(),
                        "identity", skill.identity() + "@" + skill.version(),
                        "reason", skill.reason()))
                .toList();
        span.nodeId("agent-skill-context");
        span.status("SUCCESS");
        span.outputSummary(limit(json(Map.of(
                "activeSkillCount", snapshots.size(),
                "skippedSkillCount", skippedSnapshots.size(),
                "activeSkillVersions", snapshots.stream()
                        .map(value -> value.get("identity"))
                        .toList())), 4000));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("stage", "AGENTSCOPE_REPOSITORY_BOUND");
        metadata.put("skills", snapshots);
        metadata.put("skippedSkills", skippedSnapshots);
        metadata.put("scriptExecutionEnabled", false);
        span.metadataJson(json(metadata));
        span.latencyMs(0);
        span.startedAt(now);
        span.endedAt(now);
        span.createdAt(now);
        safe(() -> evidence.appendChild(span.build()), "insert Agent Skill context span");
    }

    public void plan(TraceHandle trace,
                     RuntimeAgentView agent,
                     RuntimeAgentConfigSnapshot config,
                     Map<String, Object> input,
                     int planNo,
                     Map<String, Object> plan) {
        LocalDateTime now = LocalDateTime.now();
        ChildSpan.ChildSpanBuilder span = baseSpan(trace, id(16), trace.rootSpanId(),
                planNo == 1 ? "PLAN" : "REPLAN", agent, config, input);
        Map<String, Object> safePlan = WorkflowTraceSanitizer.sanitizePlanSummary(planNo, plan);
        span.nodeId("supervisor-plan-" + planNo);
        span.status("SUCCESS");
        span.inputSummary(json(WorkflowTraceSanitizer.sanitizeInputSummary(input)));
        span.outputSummary(limit(json(safePlan), 4000));
        span.metadataJson(json(safePlan));
        span.latencyMs(0);
        span.startedAt(now);
        span.endedAt(now);
        span.createdAt(now);
        safe(() -> evidence.appendChild(span.build()), "insert plan span");
    }

    public void workflow(TraceHandle trace,
                         RuntimeAgentView agent,
                         RuntimeAgentConfigSnapshot config,
                         Map<String, Object> input,
                         String toolName,
                         String workflowId,
                         Long workflowVersionId,
                         String workflowVersion,
                         Map<String, Object> args,
                         boolean success,
                         String code,
                         String answer,
                         long elapsedMs,
                         Map<String, Object> resultMetadata) {
        workflow(trace, agent, config, input, toolName, workflowId, workflowVersionId, workflowVersion,
                args, success, code, answer, elapsedMs, resultMetadata, null);
    }

    /** Persists only safe A2A delegation metadata; message and Artifact bodies stay out of Trace. */
    public void a2aDelegation(
            TraceHandle trace,
            RuntimeAgentView agent,
            RuntimeAgentConfigSnapshot config,
            Map<String, Object> input,
            RemoteAgentBinding binding,
            Map<String, Object> args,
            SendResponse response,
            boolean success,
            String code,
            long elapsedMs) {
        LocalDateTime endedAt = LocalDateTime.now();
        ChildSpan.ChildSpanBuilder span = baseSpan(trace, id(16), trace.rootSpanId(),
                "A2A_DELEGATION", agent, config, input);
        Map<String, Object> safeInput = new LinkedHashMap<>();
        safeInput.put("protocolSkillId", args == null ? null : text(args.get("protocolSkillId")));
        Object outboundText = args == null ? null : args.get("text");
        safeInput.put("textLength", outboundText == null ? 0 : String.valueOf(outboundText).length());
        safeInput.put("hasContextId", StringUtils.hasText(text(args == null ? null : args.get("contextId"))));
        safeInput.put("hasTaskId", StringUtils.hasText(text(args == null ? null : args.get("taskId"))));
        Map<String, Object> safeOutput = new LinkedHashMap<>();
        if (response != null) {
            safeOutput.put("taskId", response.taskId());
            safeOutput.put("remoteTaskId", response.remoteTaskId());
            safeOutput.put("state", response.state());
            safeOutput.put("errorCode", response.errorCode());
            safeOutput.put("idempotentReplay", response.idempotentReplay());
            safeOutput.put("messageCount", response.agentMessages().size());
            safeOutput.put("artifactCount", response.artifacts().size());
        }
        span.toolName(binding.getToolName());
        span.nodeId(binding.getRemoteAgentKeySnapshot() + "#" + binding.getRemoteAgentRevisionId());
        String remoteState = response == null ? null : response.state();
        boolean waiting = "TASK_STATE_WORKING".equals(remoteState)
                || "TASK_STATE_INPUT_REQUIRED".equals(remoteState)
                || "TASK_STATE_AUTH_REQUIRED".equals(remoteState);
        span.status(waiting ? "WAITING_REMOTE" : (success ? "SUCCESS" : "FAILED"));
        span.inputSummary(limit(json(safeInput), 4000));
        span.outputSummary(limit(json(safeOutput), 4000));
        Map<String, Object> metadata = new LinkedHashMap<>(safeOutput);
        metadata.put("remoteAgentId", binding.getRemoteAgentId());
        metadata.put("remoteAgentRevisionId", binding.getRemoteAgentRevisionId());
        metadata.put("remoteAgentKey", binding.getRemoteAgentKeySnapshot());
        metadata.put("principalId", binding.getPrincipalId());
        metadata.put("riskLevel", binding.getRiskLevel());
        metadata.put("permissionKey", binding.getPermissionKey());
        span.metadataJson(json(metadata));
        span.errorCode(success || waiting ? null : code);
        span.errorMessage(success || waiting ? null : limit(code, 2000));
        span.latencyMs(toInt(elapsedMs));
        span.startedAt(endedAt.minus(elapsedMs, ChronoUnit.MILLIS));
        span.endedAt(endedAt);
        span.createdAt(endedAt);
        safe(() -> evidence.appendChild(span.build()), "insert A2A delegation span");
    }

    /** Persists only sanitized Managed Executor references; objective and Artifact bodies stay out of Trace. */
    public void managedExecutor(
            TraceHandle trace,
            RuntimeAgentView agent,
            RuntimeAgentConfigSnapshot config,
            Map<String, Object> input,
            String toolName,
            Map<String, Object> args,
            Map<String, Object> result,
            boolean success,
            String code,
            long elapsedMs) {
        LocalDateTime endedAt = LocalDateTime.now();
        ChildSpan.ChildSpanBuilder span = baseSpan(trace, id(16), trace.rootSpanId(),
                "MANAGED_EXECUTOR", agent, config, input);
        Map<String, Object> safeInput = new LinkedHashMap<>();
        Object objective = args == null ? null : args.get("objective");
        safeInput.put("objectiveLength", objective instanceof String text ? text.length() : 0);
        putIfPresent(safeInput, "executionId", text(args == null ? null : args.get("executionId")));
        Map<String, Object> safeOutput = new LinkedHashMap<>();
        for (String key : List.of(
                "schema", "kind", "executionId", "status", "projectCode", "sandboxProfile",
                "acceptanceProfile", "objectiveSha256", "terminal", "resultAvailable",
                "lastEventSequence", "simulated", "productionMutationApplied")) {
            if (result != null) putIfPresent(safeOutput, key, result.get(key));
        }
        if (result != null && result.get("artifacts") instanceof List<?> artifacts) {
            safeOutput.put("artifactCount", artifacts.size());
        }
        span.toolName(toolName);
        span.nodeId(text(safeOutput.get("executionId")));
        span.status(success ? "SUCCESS" : "FAILED");
        span.inputSummary(limit(json(safeInput), 4000));
        span.outputSummary(limit(json(safeOutput), 4000));
        Map<String, Object> metadata = new LinkedHashMap<>(safeOutput);
        metadata.put("sourceType", "AGENT_DELEGATION");
        metadata.put("async", true);
        metadata.put("objectivePersistedInTrace", false);
        span.metadataJson(json(metadata));
        span.errorCode(success ? null : code);
        span.errorMessage(success ? null : limit(code, 2000));
        span.latencyMs(toInt(elapsedMs));
        span.startedAt(endedAt.minus(elapsedMs, ChronoUnit.MILLIS));
        span.endedAt(endedAt);
        span.createdAt(endedAt);
        safe(() -> evidence.appendChild(span.build()), "insert Managed Executor span");
    }

    public void workflow(TraceHandle trace,
                         RuntimeAgentView agent,
                         RuntimeAgentConfigSnapshot config,
                         Map<String, Object> input,
                         String toolName,
                         String workflowId,
                         Long workflowVersionId,
                         String workflowVersion,
                         Map<String, Object> args,
                         boolean success,
                         String code,
                         String answer,
                         long elapsedMs,
                         Map<String, Object> resultMetadata,
                         WorkflowExecutionIdentity identity) {
        LocalDateTime endedAt = LocalDateTime.now();
        ChildSpan.ChildSpanBuilder span = baseSpan(trace, id(16), trace.rootSpanId(),
                "WORKFLOW_TOOL", agent, config, input);
        var scope = scope(trace, agent, input, identity);
        span.projectCode(scope.projectCode()).tenantId(scope.tenantId()).appId(scope.appId());
        boolean waiting = WorkflowInteractionCodes.WAITING.equals(code)
                || "WAITING_USER".equalsIgnoreCase(code)
                || "RUNTIME_INTERACTION_WAITING".equals(code);
        Map<String, Object> safeResult = WorkflowTraceSanitizer.sanitizeWorkflowResult(resultMetadata);
        boolean businessTerminal = "BUSINESS_TERMINAL".equalsIgnoreCase(
                firstText(safeResult.get("outcomeClass")));
        Map<String, Object> safeArgs = WorkflowTraceSanitizer.sanitizeArgs(args);
        String safeAnswer = WorkflowTraceSanitizer.sanitizeAnswer(answer);
        span.toolName(toolName);
        span.nodeId(workflowId);
        span.status(waiting
                ? "WAITING_USER"
                : (businessTerminal ? "BUSINESS_TERMINAL" : (success ? "SUCCESS" : "FAILED")));
        span.inputSummary(limit(json(safeArgs), 4000));
        span.outputSummary(limit(safeAnswer, 4000));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workflowId", workflowId);
        metadata.put("workflowVersionId", workflowVersionId);
        metadata.put("workflowVersion", workflowVersion);
        metadata.put("workflowResult", safeResult);
        span.metadataJson(json(metadata));
        span.errorCode(success || waiting || businessTerminal ? null : code);
        span.errorMessage(success || waiting || businessTerminal ? null : limit(safeAnswer, 2000));
        span.latencyMs(toInt(elapsedMs));
        span.startedAt(endedAt.minus(elapsedMs, ChronoUnit.MILLIS));
        span.endedAt(waiting ? null : endedAt);
        span.createdAt(endedAt);
        safe(() -> evidence.appendChild(span.build()), "insert Workflow tool span");
        recordWorkflowNodeSpans(trace, span.build(), agent, config, input, workflowId,
                workflowVersionId, workflowVersion, success, code, safeResult, endedAt);

        ToolCall.ToolCallBuilder logEntity = ToolCall.builder();
        logEntity.traceId(trace.traceId());
        logEntity.sessionId(text(input.get("sessionId")));
        logEntity.userId(identity != null && identity.userTrusted() ? identity.userId() : null);
        logEntity.agentName(agent.name());
        logEntity.intentType(text(input.get("intentHint")));
        logEntity.projectId(agent.projectId());
        logEntity.projectCode(scope.projectCode());
        logEntity.environment(firstText(input.get("environment"), "DEV"));
        logEntity.tenantId(scope.tenantId());
        logEntity.appId(scope.appId());
        logEntity.externalUserId(identity != null && identity.userTrusted() ? identity.userId() : null);
        logEntity.globalUserId(identity != null && identity.userTrusted() ? identity.userId() : null);
        logEntity.pageInstanceId(text(input.get("pageInstanceId")));
        logEntity.origin(text(input.get("origin")));
        logEntity.toolName(toolName);
        logEntity.argsJson(json(safeArgs));
        logEntity.resultSummary(limit(safeAnswer, 4000));
        logEntity.success(success);
        logEntity.errorCode(success || waiting ? null : code);
        logEntity.elapsedMs(toInt(elapsedMs));
        // Never persist full workflow payload / retrieval content.
        logEntity.retrievalTraceJson(json(Map.of(
                "workflowId", workflowId,
                "workflowVersionId", workflowVersionId,
                "code", firstText(code, ""),
                "summary", safeResult)));
        logEntity.createTime(endedAt);
        safe(() -> evidence.appendToolCall(logEntity.build()), "insert Workflow tool log");
    }

    public void finish(TraceHandle trace, boolean success, String code, String answer, Map<String, Object> metadata) {
        finish(trace, success, code, answer, metadata, null);
    }

    public void finish(TraceHandle trace, boolean success, String code, String answer,
                       Map<String, Object> metadata, WorkflowExecutionIdentity identity) {
        LocalDateTime ended = LocalDateTime.now();
        String resolved = status(success, code);
        boolean waiting = "WAITING_USER".equals(resolved) || "WAITING_APPROVAL".equals(resolved);
        String safeAnswer = WorkflowTraceSanitizer.sanitizeAnswer(answer);
        // Compute tokenCost from the original metadata before allowlist sanitization strips usage trees.
        int tokenCost = RuntimeRunLifecycleService.resolveTokenCost(metadata);
        Map<String, Object> safeMetadata = new LinkedHashMap<>(
                WorkflowTraceSanitizer.sanitizeFinishMetadata(metadata));
        if (RuntimeRunLifecycleService.hasReportedTokenCost(metadata)) safeMetadata.put("tokenCost", tokenCost);
        Integer latencyMs = waiting ? null : toInt(ChronoUnit.MILLIS.between(trace.startedAt(), ended));
        if (!rootSpans.finishBestEffort(new RuntimeTraceRootService.Handle(
                trace.rootId(), trace.traceId(), trace.rootSpanId(), trace.startedAt()),
                new RuntimeTraceRootService.Completion(resolved, code, safeAnswer, ended, json(safeMetadata)))) return;
        runLifecycleService.finishAgent(trace.traceId(), success, code, safeAnswer, safeMetadata, ended,
                identity, latencyMs, trace.startedAt());
    }

    @SuppressWarnings("unchecked")
    private void recordWorkflowNodeSpans(TraceHandle trace,
                                         ChildSpan workflowSpan,
                                         RuntimeAgentView agent,
                                         RuntimeAgentConfigSnapshot config,
                                         Map<String, Object> input,
                                         String workflowId,
                                         Long workflowVersionId,
                                         String workflowVersion,
                                         boolean workflowSuccess,
                                         String workflowCode,
                                         Map<String, Object> resultMetadata,
                                         LocalDateTime endedAt) {
        Object rawTraces = resultMetadata == null ? null : resultMetadata.get("workflowNodeTraces");
        if (!(rawTraces instanceof List<?> traces) || traces.isEmpty()) {
            return;
        }
        for (Object rawTrace : traces) {
            if (!(rawTrace instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> nodeTrace = WorkflowTraceSanitizer.sanitizeNodeTrace(raw);
            if (nodeTrace.isEmpty()) {
                continue;
            }
            String nodeId = firstText(nodeTrace.get("nodeId"));
            ChildSpan.ChildSpanBuilder child = baseSpan(trace, id(16), workflowSpan.spanId(),
                    "WORKFLOW_NODE", agent, config, input);
            child.projectCode(workflowSpan.projectCode()).tenantId(workflowSpan.tenantId()).appId(workflowSpan.appId());
            child.runtimeType("LANGGRAPH4J");
            child.nodeId(nodeId);
            child.toolName(firstText(nodeTrace.get("qualifiedName")));
            String status = firstText(nodeTrace.get("status"), workflowSuccess ? "SUCCESS" : "FAILED");
            child.status(status);
            Map<String, Object> childMetadata = new LinkedHashMap<>();
            childMetadata.put("workflowId", workflowId);
            childMetadata.put("workflowVersionId", workflowVersionId);
            childMetadata.put("workflowVersion", workflowVersion);
            putIfPresent(childMetadata, "nodeType", nodeTrace.get("nodeType"));
            putIfPresent(childMetadata, "nodeName", nodeTrace.get("nodeName"));
            putIfPresent(childMetadata, "attempt", nodeTrace.get("attempt"));
            putIfPresent(childMetadata, "maxAttempts", nodeTrace.get("maxAttempts"));
            putIfPresent(childMetadata, "errorPolicy", nodeTrace.get("errorPolicy"));
            putIfPresent(childMetadata, "failureCode", nodeTrace.get("failureCode"));
            putIfPresent(childMetadata, "fallbackNodeId", nodeTrace.get("fallbackNodeId"));
            putIfPresent(childMetadata, "nextNodeId", nodeTrace.get("nextNodeId"));
            putIfPresent(childMetadata, "outcomeClass", nodeTrace.get("outcomeClass"));
            putIfPresent(childMetadata, "businessOutcome", nodeTrace.get("businessOutcome"));
            putIfPresent(childMetadata, "qualifiedName", nodeTrace.get("qualifiedName"));
            putIfPresent(childMetadata, "failureCategory", nodeTrace.get("failureCategory"));
            putIfPresent(childMetadata, "retryableFailure", nodeTrace.get("retryableFailure"));
            putIfPresent(childMetadata, "traceSummary", nodeTrace.get("traceSummary"));
            putIfPresent(childMetadata, "interactionId", nodeTrace.get("interactionId"));
            putIfPresent(childMetadata, "interactionType", nodeTrace.get("interactionType"));
            child.metadataJson(json(childMetadata));
            child.errorCode(firstText(nodeTrace.get("failureCode"),
                    "FAILED".equalsIgnoreCase(status) ? workflowCode : null));
            child.latencyMs(toInt(longValue(nodeTrace.get("latencyMs"), 0L)));
            LocalDateTime started = epochMillisToLocalDateTime(nodeTrace.get("startedAt"), endedAt);
            child.startedAt(started);
            if ("WAITING_USER".equalsIgnoreCase(status)) {
                child.endedAt(null);
            } else {
                child.endedAt(epochMillisToLocalDateTime(nodeTrace.get("endedAt"), endedAt));
            }
            child.createdAt(endedAt);
            safe(() -> evidence.appendChild(child.build()), "insert Workflow node span");
        }
    }

    private RuntimeTraceRootService.Scope scope(TraceHandle trace, RuntimeAgentView agent,
                                                Map<String, Object> input, WorkflowExecutionIdentity identity) {
        if (trace.scope() != null) return trace.scope();
        // A lifecycle-only handle cannot recover tenant attribution from a business map.
        return new RuntimeTraceRootService.Scope(agent.projectCode(),
                identity != null && identity.projectTrusted() ? identity.tenantId() : null,
                firstText(input.get("appId"), agent.projectCode()));
    }

    private ChildSpan.ChildSpanBuilder baseSpan(TraceHandle trace,
                                             String spanId,
                                             String parentSpanId,
                                             String spanType,
                                             RuntimeAgentView agent,
                                             RuntimeAgentConfigSnapshot config,
                                             Map<String, Object> input) {
        ChildSpan.ChildSpanBuilder entity = ChildSpan.builder();
        entity.traceId(trace.traceId());
        entity.spanId(spanId);
        entity.parentSpanId(parentSpanId);
        entity.spanType(spanType);
        entity.runtimeType("AGENTSCOPE");
        entity.agentId(agent.id());
        entity.agentName(agent.name());
        entity.modelInstanceId(config.getModelInstanceId());
        var scope = scope(trace, agent, input, null);
        entity.projectCode(scope.projectCode());
        entity.tenantId(scope.tenantId());
        entity.appId(scope.appId());
        entity.pageInstanceId(text(input.get("pageInstanceId")));
        return entity;
    }

    private String status(boolean success, String code) {
        if (success) return "SUCCESS";
        if ("SUPERVISOR_CONFIRMATION_REQUIRED".equalsIgnoreCase(code)) return "WAITING_APPROVAL";
        if ("RUNTIME_GRAPH_INTERACTION_WAITING".equalsIgnoreCase(code)) return "WAITING_USER";
        if (code != null && code.toUpperCase().contains("TIMEOUT")) return "TIMEOUT";
        if (code != null && code.toUpperCase().contains("CANCEL")) return "CANCELLED";
        return "FAILED";
    }

    private void safe(Runnable runnable, String action) {
        try {
            runnable.run();
        } catch (Exception ex) {
            log.warn("Failed to {}: {}", action, ex.getMessage());
        }
    }

    private int toInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private List<RuntimeRunSnapshots.SkillBinding> skillSnapshots(List<RuntimeAgentSkillBindingSnapshot> bindings) {
        if (bindings == null || bindings.isEmpty()) return List.of();
        return bindings.stream().filter(java.util.Objects::nonNull)
                .map(skill -> new RuntimeRunSnapshots.SkillBinding(
                        skill.getSkillId(), skill.getSkillVersionId(), skill.getPublisher(), skill.getStandardName(),
                        skill.getVisibility(), skill.getProjectCode(), skill.getVersion(), skill.getSourceSha256(),
                        skill.getActivationMode(), skill.getScriptPolicy(), Boolean.TRUE.equals(skill.getRequired())))
                .toList();
    }

    private String id(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            String text = text(value);
            if (text != null && !text.isBlank()) return text.trim();
        }
        return null;
    }

    private String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private long longValue(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private LocalDateTime epochMillisToLocalDateTime(Object value, LocalDateTime fallback) {
        long millis = longValue(value, -1L);
        if (millis < 0L) {
            return fallback;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }

    public record TraceHandle(String traceId, String rootSpanId, Long rootId, LocalDateTime startedAt,
                              RuntimeTraceRootService.Scope scope) {
        public TraceHandle(String traceId, String rootSpanId, Long rootId, LocalDateTime startedAt) {
            this(traceId, rootSpanId, rootId, startedAt, null);
        }
    }
}

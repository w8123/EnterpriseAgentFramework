package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the whole saved working copy for real when every external node is read-only (D28).
 *
 * <p>Every authorization fact is attested by Control, re-read here before any node runs, and rechecked by the
 * Capability gateway / HTTP API projection immediately before each dispatch. Only TOOL nodes in the pinned set
 * may dispatch; the trial never runs or changes a published version and never writes release pins.</p>
 */
@Service
@RequiredArgsConstructor
public class RuntimeWorkflowReadOnlyTrialService {
    private static final Pattern INNER_CODE = Pattern.compile("\\b((?:BUSINESS_METHOD|HTTP_API|CAPABILITY)_[A-Z0-9_]{3,90})\\b");
    private static final int MAX_OUTPUT_CHARS = 8_000;

    private final RuntimeWorkflowDefinitionService definitions;
    private final RuntimeWorkflowHttpApiService httpApis;
    private final RuntimeCapabilityCatalogClient capabilities;
    private final RuntimeWorkflowDebugService debug;
    private final RuntimeWorkflowDocumentCanonicalizer canonicalizer;
    private final ObjectMapper json;

    public TrialResult run(WorkflowReadOnlyTrialPolicy.TrialCommand command) {
        validate(command);
        RuntimeWorkflowDefinitionEntity saved = current(command);
        List<WorkflowReadOnlyTrialPolicy.Target> targets = plannedTargets(saved.getGraphSpecJson());
        Map<String, WorkflowReadOnlyTrialPolicy.AllowedTarget> allowed = new LinkedHashMap<>();
        for (var target : command.allowedTargets()) allowed.put(target.nodeId(), target);
        if (allowed.size() != command.allowedTargets().size() || allowed.size() != targets.size()
                || targets.stream().anyMatch(target -> !allowed.containsKey(target.nodeId())
                || !allowed.get(target.nodeId()).sameTarget(target))) {
            throw conflict("WORKFLOW_TRIAL_TARGET_CHANGED");
        }
        GraphSpec graph;
        try { graph = json.readValue(saved.getGraphSpecJson(), GraphSpec.class); }
        catch (Exception invalid) { throw conflict("WORKFLOW_TRIAL_GRAPH_UNSUPPORTED"); }

        List<RuntimeWorkflowHttpApiService.DraftPin> apiPins = new ArrayList<>();
        Map<String, ConsoleCapabilityInvocationContracts.InvocationContext> methodOwners = new LinkedHashMap<>();
        List<Map<String, Object>> auditTargets = new ArrayList<>();
        for (var target : targets) {
            var approved = allowed.get(target.nodeId());
            if (WorkflowReadOnlyTrialPolicy.BUSINESS_METHOD.equals(target.assetType())) {
                var owner = businessOwner(saved, target.qualifiedName());
                if (!Objects.equals(approved.methodName(), owner.name())
                        || !Objects.equals(approved.acceptedContractHash(), owner.acceptedContractHash())
                        || !Objects.equals(approved.sourceContractHash(), owner.sourceContractHash())
                        || !Objects.equals(approved.executionRevision(), owner.executionRevision())
                        || approved.environment() != null || approved.sourceSetRevision() != null) {
                    throw conflict("BUSINESS_METHOD_TRIAL_OWNER_CHANGED");
                }
                methodOwners.put(target.nodeId(), owner);
                auditTargets.add(Map.of("nodeId", target.nodeId(), "assetType", target.assetType(),
                        "qualifiedName", target.qualifiedName(), "methodName", owner.name(),
                        "acceptedContractHash", owner.acceptedContractHash(),
                        "sourceContractHash", owner.sourceContractHash()));
            } else {
                GraphSpec.Node node = node(graph, target.nodeId());
                RuntimeWorkflowHttpApiService.DraftPin pin = prepareApi(node, saved, command.expectedRevision(), target);
                if (!Objects.equals(approved.environment(), pin.environment())
                        || !Objects.equals(approved.acceptedContractHash(), pin.acceptedContractHash())
                        || !Objects.equals(approved.sourceSetRevision(), pin.sourceSetRevision())) {
                    throw conflict("HTTP_API_TRIAL_OWNER_CHANGED");
                }
                apiPins.add(pin);
                auditTargets.add(Map.of("nodeId", target.nodeId(), "assetType", target.assetType(),
                        "qualifiedName", target.qualifiedName(), "apiId", target.apiId(),
                        "environment", pin.environment(), "acceptedContractHash", pin.acceptedContractHash(),
                        "sourceSetRevision", pin.sourceSetRevision()));
            }
        }

        WorkflowExecutionIdentity identity = WorkflowExecutionIdentity.fromAttestedStudioProjectTest(
                saved.getProjectId(), saved.getProjectCode(), command.platformActorId());
        var definition = new RuntimeWorkflowDebugService.DebugDefinition(saved.getId(), saved.getKeySlug(),
                saved.getName(), saved.getWorkflowKind(), saved.getProjectId(), saved.getProjectCode(),
                saved.getExecutionEngine(), saved.getDefaultModelInstanceId(), saved.getGraphSpecJson(),
                saved.getCanvasJson());
        var audit = new RuntimeWorkflowDebugService.TrialAudit(command.platformActorId(), command.expectedRevision(),
                command.graphSha256(), List.copyOf(command.inputParams().keySet()), auditTargets);
        var methodScope = new WorkflowReadOnlyTrialPolicy.BusinessMethodTrialScope(saved.getProjectId(),
                saved.getProjectCode(), methodOwners, command.deadlineEpochMs());
        long started = System.currentTimeMillis();
        RuntimeWorkflowDebugService.DebugRunResult run = httpApis.withDraftScope(apiPins, () -> isCurrent(command),
                () -> capabilities.withBusinessMethodDraftScope(methodScope, () -> isCurrent(command),
                        () -> debug.runReadOnlyTrial(definition, command.message(), command.inputParams(), identity, audit)));
        long elapsed = Math.max(0L, System.currentTimeMillis() - started);
        boolean fresh = isCurrent(command) && sameFacts(graph, saved, command.expectedRevision(), targets, apiPins, methodOwners);
        Map<String, Object> state = run.stateSnapshot() == null ? Map.of() : run.stateSnapshot();
        List<TrialStep> steps = steps(graph, run, map(state.get("nodeOutput")), fresh);
        List<TrialTarget> trialTargets = targets.stream().map(target -> new TrialTarget(target.nodeId(),
                target.nodeName(), target.assetType(), target.qualifiedName(), target.apiId())).toList();
        if (!fresh) {
            return new TrialResult(false, "RESULT_STALE_AFTER_DISPATCH", run.runId(), run.traceId(), saved.getId(),
                    command.expectedRevision(), null, Map.of(), steps, trialTargets, elapsed,
                    "WORKFLOW_TRIAL_FACTS_STALE_AFTER_DISPATCH", null, null,
                    "试运行期间草稿、业务方法或 API 状态已变化；结果不可作为验证依据，请查看 Run/Trace，勿直接重试");
        }
        TrialStep failed = steps.stream().filter(step -> "ERROR".equals(step.status())).reduce((a, b) -> b).orElse(null);
        String errorCode = run.success() ? null : failed != null && failed.errorCode() != null ? failed.errorCode() : run.errorCode();
        String errorMessage = run.success() ? null : failed != null && failed.errorMessage() != null
                ? failed.errorMessage() : humanMessage(run.errorCode(), run.errorMessage());
        return new TrialResult(run.success(), run.status(), run.runId(), run.traceId(), saved.getId(),
                command.expectedRevision(), run.answer(), map(state.get("var")), steps, trialTargets, elapsed,
                errorCode, failed == null ? run.currentNodeId() : failed.nodeId(),
                failed == null ? null : failed.nodeName(), errorMessage);
    }

    /** Plan the saved bytes and the canonical form the executor runs; both must pin the same external nodes. */
    private List<WorkflowReadOnlyTrialPolicy.Target> plannedTargets(String graphJson) {
        List<WorkflowReadOnlyTrialPolicy.Target> targets;
        try { targets = WorkflowReadOnlyTrialPolicy.plan(json, graphJson).targets(); }
        catch (WorkflowReadOnlyTrialPolicy.GraphRejected rejected) { throw conflict(rejected.code()); }
        String canonical;
        try { canonical = canonicalizer.canonicalizeGraphSpecJson(graphJson); }
        catch (RuntimeException invalid) { throw conflict("WORKFLOW_TRIAL_GRAPH_UNSUPPORTED"); }
        try {
            if (!WorkflowReadOnlyTrialPolicy.plan(json, canonical).targets().equals(targets)) {
                throw conflict("WORKFLOW_TRIAL_GRAPH_UNSUPPORTED");
            }
        } catch (WorkflowReadOnlyTrialPolicy.GraphRejected rejected) { throw conflict(rejected.code()); }
        return targets;
    }

    private RuntimeWorkflowHttpApiService.DraftPin prepareApi(GraphSpec.Node node, RuntimeWorkflowDefinitionEntity saved,
                                                              String revision, WorkflowReadOnlyTrialPolicy.Target target) {
        try { return httpApis.prepareDraft(node, saved, revision, target); }
        catch (IllegalArgumentException changed) { throw conflict(safeCode(changed.getMessage())); }
        catch (RuntimeException unavailable) { throw conflict("HTTP_API_TRIAL_OWNER_UNAVAILABLE"); }
    }

    private boolean sameFacts(GraphSpec graph, RuntimeWorkflowDefinitionEntity saved, String revision,
                              List<WorkflowReadOnlyTrialPolicy.Target> targets,
                              List<RuntimeWorkflowHttpApiService.DraftPin> apiPins,
                              Map<String, ConsoleCapabilityInvocationContracts.InvocationContext> methodOwners) {
        try {
            int api = 0;
            for (var target : targets) {
                if (WorkflowReadOnlyTrialPolicy.BUSINESS_METHOD.equals(target.assetType())) {
                    if (!Objects.equals(methodOwners.get(target.nodeId()), businessOwner(saved, target.qualifiedName()))) return false;
                } else if (!Objects.equals(apiPins.get(api++),
                        httpApis.prepareDraft(node(graph, target.nodeId()), saved, revision, target))) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException changed) { return false; }
    }

    private ConsoleCapabilityInvocationContracts.InvocationContext businessOwner(
            RuntimeWorkflowDefinitionEntity saved, String qualifiedName) {
        final ConsoleCapabilityInvocationContracts.InvocationContext owner;
        try { owner = capabilities.getBusinessMethodExecutionContext(qualifiedName, saved.getProjectCode()); }
        catch (RuntimeException unavailable) { throw conflict("BUSINESS_METHOD_TRIAL_OWNER_UNAVAILABLE"); }
        String rejection = WorkflowReadOnlyTrialPolicy.businessOwnerRejection(owner,
                saved.getProjectId(), saved.getProjectCode(), qualifiedName);
        if (rejection != null) throw conflict(rejection);
        return owner;
    }

    /** Per-node view for the author: status, latency, a bounded safe output and a readable failure reason. */
    private List<TrialStep> steps(GraphSpec graph, RuntimeWorkflowDebugService.DebugRunResult run,
                                  Map<String, Object> nodeOutputs, boolean includeOutput) {
        List<TrialStep> steps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var step : run.steps() == null ? List.<RuntimeWorkflowDebugService.DebugStepResult>of() : run.steps()) {
            if (step.nodeId() == null) continue;
            boolean first = seen.add(step.nodeId());
            GraphSpec.Node node = graph.getNodes() == null ? null : graph.getNodes().stream()
                    .filter(item -> step.nodeId().equals(item.getId())).findFirst().orElse(null);
            String nodeName = StringUtils.hasText(step.nodeName()) ? step.nodeName()
                    : node != null && StringUtils.hasText(node.getName()) ? node.getName() : step.nodeId();
            String errorCode = step.errorCode() == null ? null : innerCode(step.errorCode(), step.errorMessage());
            String errorMessage = step.errorCode() == null ? null : humanMessage(errorCode, step.errorMessage());
            Object output = includeOutput && first && !"ERROR".equals(step.status())
                    ? bounded(nodeOutputs.get(step.nodeId())) : null;
            steps.add(new TrialStep(steps.size(), step.nodeId(), step.nodeType(), nodeName, step.status(),
                    step.elapsedMs() == null ? 0L : step.elapsedMs(), output, errorCode, errorMessage));
        }
        return steps;
    }

    /** Surfaces the gateway/projection code wrapped by a generic TOOL failure (e.g. a read-only refusal). */
    private static String innerCode(String code, String message) {
        if (("RUNTIME_GRAPH_TOOL_FAILED".equals(code)) && message != null) {
            Matcher matcher = INNER_CODE.matcher(message);
            if (matcher.find()) return matcher.group(1);
        }
        return code;
    }

    static String humanMessage(String code, String message) {
        String known = switch (code == null ? "" : code) {
            case "BUSINESS_METHOD_TRIAL_READ_ONLY_REQUIRED", "CAPABILITY_STUDIO_READ_ONLY_REQUIRED",
                    "HTTP_API_TRIAL_READ_ONLY_REQUIRED" -> "该节点不是只读操作，试运行已在调用前拒绝";
            case "BUSINESS_METHOD_TRIAL_TARGET_CHANGED", "HTTP_API_TRIAL_SCOPE_DENIED" -> "该节点不在本次试运行允许的范围内";
            case "BUSINESS_METHOD_TRIAL_DRAFT_STALE", "HTTP_API_TRIAL_DRAFT_STALE" -> "草稿已变化或试运行已超时，未继续调用";
            case "BUSINESS_METHOD_TRIAL_OWNER_CHANGED", "HTTP_API_TRIAL_FACTS_STALE" -> "业务方法或 API 的来源、契约或连接在试运行期间发生变化";
            case "BUSINESS_METHOD_TRIAL_DISPATCH_LIMIT_EXCEEDED", "HTTP_API_TRIAL_DISPATCH_LIMIT_EXCEEDED" ->
                    "该节点调用次数超过试运行上限（每节点 " + WorkflowReadOnlyTrialPolicy.MAX_DISPATCHES_PER_NODE
                            + " 次、每次试运行 " + WorkflowReadOnlyTrialPolicy.MAX_DISPATCHES + " 次）";
            case "BUSINESS_METHOD_TRIAL_RESULT_UNCONFIRMED", "WORKFLOW_TRIAL_RESULT_UNCONFIRMED" ->
                    "调用结果未确认，请先查看 Run/Trace，勿直接重试";
            case "HTTP_API_WORKFLOW_INPUT_INVALID" -> "API 输入不符合已接纳契约，请检查该节点的输入映射";
            case "HTTP_API_CONNECTION_NOT_READY" -> "API 连接尚未配置完成";
            default -> null;
        };
        if (known != null) return known;
        if (message == null || message.isBlank() || Objects.equals(code, message)) return code;
        return message.length() > 500 ? message.substring(0, 500) + "…" : message;
    }

    private Object bounded(Object output) {
        if (output == null) return null;
        try {
            String text = json.writeValueAsString(output);
            return text.length() <= MAX_OUTPUT_CHARS ? output : text.substring(0, MAX_OUTPUT_CHARS) + "…（已截断）";
        } catch (Exception unprintable) { return String.valueOf(output); }
    }

    private static GraphSpec.Node node(GraphSpec graph, String nodeId) {
        return (graph.getNodes() == null ? List.<GraphSpec.Node>of() : graph.getNodes()).stream()
                .filter(node -> nodeId.equals(node.getId())).findFirst()
                .orElseThrow(() -> conflict("WORKFLOW_TRIAL_TARGET_CHANGED"));
    }

    private RuntimeWorkflowDefinitionEntity current(WorkflowReadOnlyTrialPolicy.TrialCommand command) {
        RuntimeWorkflowDefinitionEntity saved = definitions.findById(command.workflowId())
                .orElseThrow(() -> conflict("WORKFLOW_TRIAL_DRAFT_NOT_FOUND"));
        if (!Objects.equals(saved.getProjectId(), command.projectId())
                || !Objects.equals(saved.getProjectCode(), command.projectCode())) {
            throw conflict("WORKFLOW_TRIAL_PROJECT_CHANGED");
        }
        // The trial always runs the saved working copy (graph_spec_json), never a published version, so a
        // published Workflow's edited working copy can be trialled as well (D28).
        if (!Objects.equals(revision(saved.getUpdatedAt()), command.expectedRevision())
                || !StringUtils.hasText(saved.getGraphSpecJson())
                || !Objects.equals(WorkflowReadOnlyTrialPolicy.graphSha256(saved.getGraphSpecJson()),
                        command.graphSha256())) {
            throw conflict("WORKFLOW_TRIAL_DRAFT_STALE");
        }
        return saved;
    }

    private boolean isCurrent(WorkflowReadOnlyTrialPolicy.TrialCommand command) {
        try {
            return System.currentTimeMillis() <= command.deadlineEpochMs()
                    && definitions.findById(command.workflowId())
                    .map(saved -> Objects.equals(saved.getProjectId(), command.projectId())
                            && Objects.equals(saved.getProjectCode(), command.projectCode())
                            && Objects.equals(revision(saved.getUpdatedAt()), command.expectedRevision())
                            && Objects.equals(WorkflowReadOnlyTrialPolicy.graphSha256(saved.getGraphSpecJson()),
                                    command.graphSha256()))
                    .orElse(false);
        } catch (RuntimeException changed) { return false; }
    }

    private void validate(WorkflowReadOnlyTrialPolicy.TrialCommand command) {
        long now = System.currentTimeMillis();
        if (command == null || command.contractVersion() != WorkflowReadOnlyTrialPolicy.VERSION
                || !StringUtils.hasText(command.workflowId()) || !command.workflowId().matches("[A-Za-z0-9_-]{1,32}")
                || !StringUtils.hasText(command.expectedRevision())
                || !StringUtils.hasText(command.graphSha256())
                || !command.graphSha256().matches("[0-9a-f]{64}")
                || command.projectId() == null || command.projectId() <= 0
                || !StringUtils.hasText(command.projectCode())
                || !StringUtils.hasText(command.platformActorId())
                || command.allowedTargets() == null
                || command.allowedTargets().size() > WorkflowReadOnlyTrialPolicy.MAX_TARGETS
                || command.allowedTargets().stream().anyMatch(Objects::isNull)
                || command.message() != null && command.message().length() > WorkflowReadOnlyTrialPolicy.MAX_MESSAGE_LENGTH
                || command.deadlineEpochMs() <= now
                || command.deadlineEpochMs() > now + WorkflowReadOnlyTrialPolicy.MAX_DURATION_MS
                || !validInput(command.inputParams())) throw conflict("WORKFLOW_TRIAL_REQUEST_INVALID");
    }

    private static boolean validInput(Map<String, Object> params) {
        if (params == null || params.size() > WorkflowReadOnlyTrialPolicy.MAX_INPUT_FIELDS) return false;
        for (var entry : params.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) return false;
            Object value = entry.getValue();
            if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) return false;
            if (value instanceof String text && text.length() > 2048) return false;
        }
        return true;
    }

    private static String revision(LocalDateTime updatedAt) { return updatedAt == null ? null : updatedAt.toString(); }
    private static Conflict conflict(String code) { return new Conflict(code); }
    private static String safeCode(String code) {
        return code != null && code.matches("[A-Z0-9_]{1,96}") ? code : "HTTP_API_TRIAL_FACTS_UNAVAILABLE";
    }
    private static Map<String, Object> map(Object raw) {
        if (!(raw instanceof Map<?, ?> values)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    /** One external node the trial pinned and may really call. */
    public record TrialTarget(String nodeId, String nodeName, String assetType, String qualifiedName, long apiId) { }

    /** One executed node in order. {@code output} is the bounded, already redacted/protected node output. */
    public record TrialStep(int index, String nodeId, String nodeType, String nodeName, String status,
                            long elapsedMs, Object output, String errorCode, String errorMessage) { }

    public record TrialResult(boolean success, String status, String runId, String traceId,
                              String workflowId, String revision, String answer, Map<String, Object> variables,
                              List<TrialStep> steps, List<TrialTarget> targets, long elapsedMs,
                              String errorCode, String failedNodeId, String failedNodeName, String errorMessage) { }

    public static final class Conflict extends IllegalArgumentException {
        private final String code;
        private Conflict(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}

package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.common.capability.HttpApiDraftTrialPolicy;
import com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy;
import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Saved-draft-only execution. Every authorization fact is attested and rechecked before dispatch. */
@Service
@RequiredArgsConstructor
public class RuntimeWorkflowReadOnlyTrialService {
    private final RuntimeWorkflowDefinitionService definitions;
    private final RuntimeWorkflowHttpApiService httpApis;
    private final RuntimeCapabilityCatalogClient capabilities;
    private final RuntimeWorkflowDebugService debug;
    private final ObjectMapper json;

    public TrialResult run(WorkflowReadOnlyTrialPolicy.TrialCommand command) {
        validate(command);
        RuntimeWorkflowDefinitionEntity saved = current(command);
        WorkflowReadOnlyTrialPolicy.Target target = WorkflowReadOnlyTrialPolicy.target(json, saved.getGraphSpecJson());
        WorkflowReadOnlyTrialPolicy.AllowedTarget allowed = command.allowedTargets().get(0);
        if (!Objects.equals(target.nodeId(), allowed.nodeId()) || target.apiId() != allowed.apiId()
                || !Objects.equals(target.qualifiedName(), allowed.qualifiedName())
                || !Objects.equals(target.assetType(), allowed.assetType())) {
            throw conflict("HTTP_API_TRIAL_TARGET_CHANGED");
        }
        if (WorkflowReadOnlyTrialPolicy.BUSINESS_METHOD.equals(target.assetType())) {
            return runBusinessMethod(command, saved, target, allowed);
        }
        GraphSpec graph;
        try { graph = json.readValue(saved.getGraphSpecJson(), GraphSpec.class); }
        catch (Exception invalid) { throw conflict("HTTP_API_TRIAL_GRAPH_UNSUPPORTED"); }
        GraphSpec.Node apiNode = graph.getNodes().stream()
                .filter(node -> target.nodeId().equals(node.getId())).findFirst()
                .orElseThrow(() -> conflict("HTTP_API_TRIAL_TARGET_CHANGED"));
        RuntimeWorkflowHttpApiService.DraftPin pin;
        try { pin = httpApis.prepareDraft(apiNode, saved, command.expectedRevision(), target.apiTarget()); }
        catch (IllegalArgumentException changed) { throw conflict(safeCode(changed.getMessage())); }
        catch (RuntimeException unavailable) { throw conflict("HTTP_API_TRIAL_OWNER_UNAVAILABLE"); }
        if (!Objects.equals(allowed.environment(), pin.environment())
                || !Objects.equals(allowed.acceptedContractHash(), pin.acceptedContractHash())
                || !Objects.equals(allowed.sourceSetRevision(), pin.sourceSetRevision())) {
            throw conflict("HTTP_API_TRIAL_OWNER_CHANGED");
        }
        WorkflowExecutionIdentity identity = WorkflowExecutionIdentity.fromAttestedStudioProjectTest(
                saved.getProjectId(), saved.getProjectCode(), command.platformActorId());
        RuntimeWorkflowDebugService.DebugDefinition definition = new RuntimeWorkflowDebugService.DebugDefinition(
                saved.getId(), saved.getKeySlug(), saved.getName(), saved.getWorkflowKind(),
                saved.getProjectId(), saved.getProjectCode(), saved.getExecutionEngine(),
                saved.getDefaultModelInstanceId(), saved.getGraphSpecJson(), saved.getCanvasJson());
        RuntimeWorkflowDebugService.TrialAudit audit = new RuntimeWorkflowDebugService.TrialAudit(
                command.platformActorId(), command.expectedRevision(), target.apiId(),
                target.qualifiedName(), pin.environment(), pin.acceptedContractHash(), pin.sourceSetRevision(),
                command.graphSha256(),
                List.copyOf(command.inputParams().keySet()));
        long started = System.currentTimeMillis();
        RuntimeWorkflowDebugService.DebugRunResult run = httpApis.withDraftScope(pin,
                () -> isCurrent(command), () -> debug.runReadOnlyTrial(
                        definition, command.inputParams(), identity, audit));
        if (!isCurrent(command) || !sameCurrentFacts(apiNode, saved, command.expectedRevision(), target.apiTarget(), pin)) {
            return new TrialResult(false, "RESULT_STALE_AFTER_DISPATCH", run.runId(), run.traceId(),
                    saved.getId(), command.expectedRevision(), target.apiId(), target.qualifiedName(),
                    pin.environment(), null, Map.of(), Math.max(0L, System.currentTimeMillis() - started),
                    "HTTP_API_TRIAL_FACTS_STALE_AFTER_DISPATCH", "试运行期间草稿或 API 状态已变化；请查看 Run/Trace，勿直接重试");
        }
        Map<String, Object> state = run.stateSnapshot() == null ? Map.of() : run.stateSnapshot();
        Object apiOutput = map(state.get("nodeOutput")).get(target.nodeId());
        Map<String, Object> variables = map(state.get("var"));
        return new TrialResult(run.success(), run.status(), run.runId(), run.traceId(),
                saved.getId(), command.expectedRevision(), target.apiId(), target.qualifiedName(),
                pin.environment(), apiOutput, variables, Math.max(0L, System.currentTimeMillis() - started),
                run.errorCode(), run.errorMessage());
    }

    private TrialResult runBusinessMethod(WorkflowReadOnlyTrialPolicy.TrialCommand command,
            RuntimeWorkflowDefinitionEntity saved, WorkflowReadOnlyTrialPolicy.Target target,
            WorkflowReadOnlyTrialPolicy.AllowedTarget allowed) {
        var owner = businessOwner(saved, target.qualifiedName());
        if (!Objects.equals(allowed.methodName(), owner.name())
                || !Objects.equals(allowed.acceptedContractHash(), owner.acceptedContractHash())
                || !Objects.equals(allowed.sourceContractHash(), owner.sourceContractHash())
                || !Objects.equals(allowed.executionRevision(), owner.executionRevision())
                || allowed.environment() != null || allowed.sourceSetRevision() != null) {
            throw conflict("BUSINESS_METHOD_TRIAL_OWNER_CHANGED");
        }
        Map<String, Object> input;
        try { input = WorkflowReadOnlyTrialPolicy.businessInput(json, saved.getGraphSpecJson(), target, owner, command.inputParams()); }
        catch (IllegalArgumentException invalid) { throw conflict(safeCode(invalid.getMessage())); }
        var pin = new WorkflowReadOnlyTrialPolicy.BusinessMethodPin(target.nodeId(), saved.getProjectId(),
                saved.getProjectCode(), owner, input, command.deadlineEpochMs());
        var identity = WorkflowExecutionIdentity.fromAttestedStudioProjectTest(
                saved.getProjectId(), saved.getProjectCode(), command.platformActorId());
        var definition = new RuntimeWorkflowDebugService.DebugDefinition(saved.getId(), saved.getKeySlug(), saved.getName(),
                saved.getWorkflowKind(), saved.getProjectId(), saved.getProjectCode(), saved.getExecutionEngine(),
                saved.getDefaultModelInstanceId(), saved.getGraphSpecJson(), saved.getCanvasJson());
        var audit = new RuntimeWorkflowDebugService.TrialAudit(command.platformActorId(), command.expectedRevision(), 0,
                target.qualifiedName(), null, owner.acceptedContractHash(), null, command.graphSha256(),
                List.copyOf(command.inputParams().keySet()), WorkflowReadOnlyTrialPolicy.BUSINESS_METHOD,
                owner.name(), owner.currentContractHash(), owner.sourceContractHash());
        long started = System.currentTimeMillis();
        var run = capabilities.withBusinessMethodDraftScope(pin, () -> isCurrent(command),
                () -> debug.runReadOnlyTrial(definition, command.inputParams(), identity, audit));
        boolean fresh;
        try { fresh = isCurrent(command) && Objects.equals(owner, businessOwner(saved, target.qualifiedName())); }
        catch (RuntimeException changed) { fresh = false; }
        if (!fresh) return new TrialResult(false, "RESULT_STALE_AFTER_DISPATCH", run.runId(), run.traceId(),
                saved.getId(), command.expectedRevision(), 0, target.qualifiedName(), null, null, Map.of(),
                Math.max(0, System.currentTimeMillis() - started), "BUSINESS_METHOD_TRIAL_FACTS_STALE_AFTER_DISPATCH",
                "试运行期间草稿或业务方法状态已变化；请查看 Run/Trace，勿直接重试",
                WorkflowReadOnlyTrialPolicy.BUSINESS_METHOD, owner.name(), null);
        Map<String, Object> state = run.stateSnapshot() == null ? Map.of() : run.stateSnapshot();
        return new TrialResult(run.success(), run.status(), run.runId(), run.traceId(), saved.getId(),
                command.expectedRevision(), 0, target.qualifiedName(), null, null, map(state.get("var")),
                Math.max(0, System.currentTimeMillis() - started), run.errorCode(), run.errorMessage(),
                WorkflowReadOnlyTrialPolicy.BUSINESS_METHOD, owner.name(), map(state.get("nodeOutput")).get(target.nodeId()));
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

    private boolean sameCurrentFacts(GraphSpec.Node node, RuntimeWorkflowDefinitionEntity saved,
                                     String revision, HttpApiDraftTrialPolicy.Target target,
                                     RuntimeWorkflowHttpApiService.DraftPin pin) {
        try {
            return Objects.equals(pin, httpApis.prepareDraft(node, saved, revision, target));
        } catch (RuntimeException changed) { return false; }
    }

    private RuntimeWorkflowDefinitionEntity current(WorkflowReadOnlyTrialPolicy.TrialCommand command) {
        RuntimeWorkflowDefinitionEntity saved = definitions.findById(command.workflowId())
                .orElseThrow(() -> conflict("HTTP_API_TRIAL_DRAFT_NOT_FOUND"));
        if (!Objects.equals(saved.getProjectId(), command.projectId())
                || !Objects.equals(saved.getProjectCode(), command.projectCode())) {
            throw conflict("HTTP_API_TRIAL_PROJECT_CHANGED");
        }
        if (!"DRAFT".equalsIgnoreCase(saved.getStatus())) {
            throw conflict("HTTP_API_TRIAL_DRAFT_REQUIRED");
        }
        if (!Objects.equals(revision(saved.getUpdatedAt()), command.expectedRevision())
                || !StringUtils.hasText(saved.getGraphSpecJson())
                || !Objects.equals(HttpApiDraftTrialPolicy.graphSha256(saved.getGraphSpecJson()),
                        command.graphSha256())) {
            throw conflict("HTTP_API_TRIAL_DRAFT_STALE");
        }
        return saved;
    }

    private boolean isCurrent(WorkflowReadOnlyTrialPolicy.TrialCommand command) {
        try {
            return System.currentTimeMillis() <= command.deadlineEpochMs()
                    && definitions.findById(command.workflowId())
                    .map(saved -> Objects.equals(saved.getProjectId(), command.projectId())
                            && Objects.equals(saved.getProjectCode(), command.projectCode())
                            && "DRAFT".equalsIgnoreCase(saved.getStatus())
                            && Objects.equals(revision(saved.getUpdatedAt()), command.expectedRevision())
                            && Objects.equals(HttpApiDraftTrialPolicy.graphSha256(saved.getGraphSpecJson()),
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
                || command.allowedTargets() == null || command.allowedTargets().size() != 1
                || command.allowedTargets().get(0) == null
                || command.deadlineEpochMs() <= now || command.deadlineEpochMs() > now + 45_000
                || !validInput(command.inputParams())) throw conflict("HTTP_API_TRIAL_REQUEST_INVALID");
    }

    private static boolean validInput(Map<String, Object> params) {
        if (params == null || params.size() > 16) return false;
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

    public record TrialResult(boolean success, String status, String runId, String traceId,
                              String workflowId, String revision, long apiId, String qualifiedName,
                              String environment, Object apiOutput, Map<String, Object> variables,
                              long elapsedMs, String errorCode, String errorMessage,
                              String assetType, String methodName, Object methodOutput) {
        public TrialResult(boolean success, String status, String runId, String traceId,
                           String workflowId, String revision, long apiId, String qualifiedName,
                           String environment, Object apiOutput, Map<String, Object> variables,
                           long elapsedMs, String errorCode, String errorMessage) {
            this(success, status, runId, traceId, workflowId, revision, apiId, qualifiedName, environment,
                    apiOutput, variables, elapsedMs, errorCode, errorMessage, WorkflowReadOnlyTrialPolicy.HTTP_API, null, null);
        }
    }

    public static final class Conflict extends IllegalArgumentException {
        private final String code;
        private Conflict(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}

package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.common.capability.HttpApiDraftTrialPolicy;
import com.enterprise.ai.common.capability.HttpApiFirstCallPolicy;
import com.enterprise.ai.common.capability.HttpApiRequestPolicy;
import com.enterprise.ai.common.capability.HttpApiResponseProtection;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialRuntime;
import com.enterprise.ai.runtime.execution.RuntimeHttpApiToolExecutionPort;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.runops.consolehttpapi.RuntimeHttpApiConnectionService;
import com.enterprise.ai.runtime.runops.consolehttpapi.RuntimeHttpApiVerificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Owner-backed, version-pinned HTTP API TOOL projection. Never creates a Console invocation. */
@Service
@RequiredArgsConstructor
public class RuntimeWorkflowHttpApiService implements RuntimeHttpApiToolExecutionPort {
    private final RuntimeCapabilityCatalogClient capability;
    private final RuntimeHttpApiConnectionService connections;
    private final RuntimeWorkflowHttpApiPinMapper pins;
    private final WorkflowHttpClient http;
    private final ObjectMapper json;
    private final ThreadLocal<DraftScope> draftScope = new ThreadLocal<>();

    public record ReleasePin(long id, String acceptedContractHash) { }
    /** Request-only immutable facts; neither origin nor credential value is included. */
    public record DraftPin(String workflowId, String revision, String nodeId, long apiId,
                           String qualifiedName, Long projectId, String projectCode, String environment,
                           String acceptedContractHash, String sourceSetRevision,
                           Long connectionRevision, String credentialRevision) { }

    private record DraftScope(DraftPin pin, Supplier<Boolean> revisionCurrent) { }

    public DraftPin prepareDraft(GraphSpec.Node node, RuntimeWorkflowDefinitionEntity workflow,
                                 String revision, HttpApiDraftTrialPolicy.Target allowed) {
        if (workflow == null || allowed == null || node == null || node.getRef() == null
                || !StringUtils.hasText(revision) || !Objects.equals(node.getId(), allowed.nodeId())
                || !Objects.equals(node.getRef().getQualifiedName(), allowed.qualifiedName())
                || apiId(node) != allowed.apiId()) throw invalid("HTTP_API_TRIAL_TARGET_CHANGED");
        Current current = current(allowed.apiId(), allowed.qualifiedName(),
                workflow.getProjectId(), workflow.getProjectCode());
        if (!HttpApiDraftTrialPolicy.readOnlyOwner(current.owner().httpMethod(),
                current.owner().acceptedContract())
                || HttpApiFirstCallPolicy.unsupportedReason(current.owner().acceptedContract()) != null) {
            throw invalid("HTTP_API_TRIAL_READ_ONLY_REQUIRED");
        }
        return new DraftPin(workflow.getId(), revision, node.getId(), allowed.apiId(),
                allowed.qualifiedName(), current.owner().projectId(), current.owner().projectCode(),
                current.owner().environment(), current.owner().acceptedContractHash(),
                current.owner().sourceSetRevision(), current.connection().revision(),
                current.credential() == null ? null : current.credential().revision());
    }

    public <T> T withDraftScope(DraftPin pin, Supplier<Boolean> revisionCurrent, Supplier<T> action) {
        if (pin == null || revisionCurrent == null || action == null || draftScope.get() != null) {
            throw invalid("HTTP_API_TRIAL_SCOPE_INVALID");
        }
        draftScope.set(new DraftScope(pin, revisionCurrent));
        try { return action.get(); }
        finally { draftScope.remove(); }
    }

    public ReleasePin pin(GraphSpec.Node node, RuntimeWorkflowDefinitionEntity workflow, String reference) {
        if (workflow == null || !StringUtils.hasText(workflow.getId())
                || workflow.getProjectId() == null || workflow.getProjectId() <= 0
                || !StringUtils.hasText(workflow.getProjectCode()) || !StringUtils.hasText(node.getId())) {
            throw invalid("HTTP_API_WORKFLOW_SCOPE_REQUIRED");
        }
        long apiId = apiId(node);
        Current current = current(apiId, reference, workflow.getProjectId(), workflow.getProjectCode());
        RuntimeWorkflowHttpApiPinEntity row = new RuntimeWorkflowHttpApiPinEntity();
        row.setWorkflowId(workflow.getId());
        row.setNodeId(node.getId());
        row.setApiId(apiId);
        row.setQualifiedName(reference);
        row.setProjectId(current.owner().projectId());
        row.setProjectCode(current.owner().projectCode());
        row.setEnvironment(current.owner().environment());
        row.setAcceptedContractHash(current.owner().acceptedContractHash());
        row.setSourceSetRevision(current.owner().sourceSetRevision());
        row.setConnectionRevision(current.connection().revision());
        row.setCredentialRevision(current.credential() == null ? null : current.credential().revision());
        row.setCreatedAt(LocalDateTime.now());
        if (pins.insert(row) != 1 || row.getId() == null) throw invalid("HTTP_API_PIN_PERSISTENCE_FAILED");
        return new ReleasePin(row.getId(), row.getAcceptedContractHash());
    }

    public void bindVersion(GraphSpec.Node node, long versionId) {
        Long pinId = node == null || node.getRef() == null ? null : node.getRef().getDefinitionId();
        RuntimeWorkflowHttpApiPinEntity row = pinId == null || pinId <= 0 ? null : pins.selectById(pinId);
        if (row == null || versionId <= 0 || row.getWorkflowVersionId() != null
                || !Objects.equals(row.getNodeId(), node.getId())
                || !Objects.equals(row.getQualifiedName(), node.getRef().getQualifiedName())
                || !Objects.equals(row.getAcceptedContractHash(), node.getRef().getContractHash())
                || !Objects.equals(row.getApiId(), apiId(node))) throw invalid("HTTP_API_PIN_VERSION_INVALID");
        row.setWorkflowVersionId(versionId);
        if (pins.updateById(row) != 1) throw invalid("HTTP_API_PIN_VERSION_INVALID");
    }

    public void validatePinned(GraphSpec.Node node) {
        checkedCurrent(requirePin(node));
    }

    public HttpApiResponseProtection prepareInputProtection(GraphSpec.Node node, Map<String, Object> input) {
        Current current = checkedCurrent(requirePin(node));
        Map<String, Object> path = new LinkedHashMap<>(), query = new LinkedHashMap<>(), body = new LinkedHashMap<>();
        if (input != null) input.forEach((key, value) -> {
            if (key.startsWith("pathParams.")) path.put(key.substring(11), value);
            if (key.startsWith("queryParams.")) query.put(key.substring(12), value);
            if (key.startsWith("body.")) body.put(key.substring(5), value);
        });
        return HttpApiResponseProtection.prepare(current.owner().acceptedContract(), path, query, body,
                current.credential() == null ? null : current.credential().secret());
    }

    /** Read server-created release pins even before their final version binding; no author-provided risk is trusted. */
    public String publishedRiskFloor(GraphSpec.Node node) {
        Long id = node == null || node.getRef() == null ? null : node.getRef().getDefinitionId();
        var row = id == null ? null : pins.selectById(id);
        if (row == null || !Objects.equals(row.getNodeId(), node.getId())
                || !Objects.equals(row.getApiId(), apiId(node))
                || !Objects.equals(row.getQualifiedName(), node.getRef().getQualifiedName())
                || !Objects.equals(row.getAcceptedContractHash(), node.getRef().getContractHash())) {
            throw invalid("HTTP_API_PUBLISHED_PIN_INVALID");
        }
        Current current = checkedCurrent(row);
        return "WRITE".equals(current.owner().acceptedContract().path("sideEffect").asText()) ? "WRITE" : null;
    }

    private Current checkedCurrent(RuntimeWorkflowHttpApiPinEntity row) {
        Current current = current(row.getApiId(), row.getQualifiedName(), row.getProjectId(), row.getProjectCode());
        if (!Objects.equals(row.getEnvironment(), current.owner().environment())
                || !Objects.equals(row.getAcceptedContractHash(), current.owner().acceptedContractHash())
                || !Objects.equals(row.getSourceSetRevision(), current.owner().sourceSetRevision())
                || !Objects.equals(row.getConnectionRevision(), current.connection().revision())
                || !Objects.equals(row.getCredentialRevision(),
                        current.credential() == null ? null : current.credential().revision())) {
            throw invalid("HTTP_API_PUBLISHED_PIN_STALE");
        }
        return current;
    }

    @Override
    public Invocation invoke(GraphSpec.Node node, Map<String, Object> input, WorkflowExecutionIdentity identity) {
        if (identity != null && identity.source() == WorkflowExecutionIdentity.Source.STUDIO_PROJECT_TEST) {
            return invokeDraft(node, input, identity);
        }
        try {
            RuntimeWorkflowHttpApiPinEntity row = requirePin(node);
            if (identity == null || !identity.canResolveProjectCredential()
                    || identity.source() == WorkflowExecutionIdentity.Source.DEBUG_UNTRUSTED

                    || !identity.authorizeProjectCredential(row.getProjectId(), row.getProjectCode())) {
                return failure("HTTP_API_WORKFLOW_IDENTITY_DENIED");
            }
            Current current = checkedCurrent(row);
            return dispatch(input, identity, current, row.getCredentialRevision());
        } catch (Rejected rejected) {
            return failure(rejected.getMessage());
        } catch (RuntimeException unavailable) {
            return failure("HTTP_API_WORKFLOW_PRE_DISPATCH_UNAVAILABLE");
        }
    }

    private Invocation invokeDraft(GraphSpec.Node node, Map<String, Object> input,
                                   WorkflowExecutionIdentity identity) {
        try {
            DraftScope scope = draftScope.get();
            DraftPin pin = scope == null ? null : scope.pin();
            if (pin == null || !identity.canResolveProjectCredential()
                    || !identity.authorizeProjectCredential(pin.projectId(), pin.projectCode())
                    || node == null || node.getRef() == null
                    || !Objects.equals(pin.nodeId(), node.getId())
                    || !Objects.equals(pin.qualifiedName(), node.getRef().getQualifiedName())
                    || pin.apiId() != apiId(node)) return failure("HTTP_API_TRIAL_SCOPE_DENIED");
            if (!scope.revisionCurrent().get()) return failure("HTTP_API_TRIAL_DRAFT_STALE");
            Current current = current(pin.apiId(), pin.qualifiedName(), pin.projectId(), pin.projectCode());
            if (!HttpApiDraftTrialPolicy.readOnlyOwner(current.owner().httpMethod(),
                    current.owner().acceptedContract())
                    || HttpApiFirstCallPolicy.unsupportedReason(current.owner().acceptedContract()) != null) {
                return failure("HTTP_API_TRIAL_READ_ONLY_REQUIRED");
            }
            if (!Objects.equals(pin.environment(), current.owner().environment())
                    || !Objects.equals(pin.acceptedContractHash(), current.owner().acceptedContractHash())
                    || !Objects.equals(pin.sourceSetRevision(), current.owner().sourceSetRevision())
                    || !Objects.equals(pin.connectionRevision(), current.connection().revision())
                    || !Objects.equals(pin.credentialRevision(),
                            current.credential() == null ? null : current.credential().revision())) {
                return failure("HTTP_API_TRIAL_FACTS_STALE");
            }
            if (!scope.revisionCurrent().get()) return failure("HTTP_API_TRIAL_DRAFT_STALE");
            return dispatch(input, identity, current, pin.credentialRevision());
        } catch (Rejected rejected) {
            return failure(rejected.getMessage());
        } catch (RuntimeException unavailable) {
            return failure("HTTP_API_TRIAL_PRE_DISPATCH_UNAVAILABLE");
        }
    }

    private Invocation dispatch(Map<String, Object> input, WorkflowExecutionIdentity identity,
                                Current current, String credentialRevision) {
        String sideEffect = current.owner().acceptedContract().path("sideEffect").asText();
        boolean write = "WRITE".equals(sideEffect);
        boolean attempted = false;
        try {
            Map<String, Object> path = new LinkedHashMap<>();
            Map<String, Object> query = new LinkedHashMap<>();
            Map<String, Object> body = new LinkedHashMap<>();
            if (input != null) for (Map.Entry<String, Object> entry : input.entrySet()) {
                String key = entry.getKey();
                if (key != null && key.startsWith("pathParams.") && key.length() > "pathParams.".length()) {
                    path.put(key.substring("pathParams.".length()), entry.getValue());
                } else if (key != null && key.startsWith("queryParams.") && key.length() > "queryParams.".length()) {
                    query.put(key.substring("queryParams.".length()), entry.getValue());
                } else if (key != null && key.startsWith("body.") && key.length() > "body.".length()) {
                    body.put(key.substring("body.".length()), entry.getValue());
                } else return new Invocation(false, "HTTP_API_WORKFLOW_INPUT_INVALID", null, null, key,
                        sideEffect, "NOT_DISPATCHED");
            }
            HttpApiRequestPolicy.BoundRequest bound;
            try {
                var contract = current.owner().acceptedContract();
                // A required object with no present optional fields is {}, not an absent body.
                // Optional absence stays null; mapped nulls and required fields are still validated by bind.
                boolean requiredObjectBody = "POST".equals(contract.path("identity").path("method").asText())
                        && contract.path("requestBody").path("required").asBoolean(false);
                bound = HttpApiRequestPolicy.bind(contract, path, query,
                        body.isEmpty() && !requiredObjectBody ? null : body, json);
            } catch (RuntimeException invalidInput) {
                return new Invocation(false, "HTTP_API_WORKFLOW_INPUT_INVALID", null, null,
                        invalidInputField(current.owner().acceptedContract(), invalidInput.getMessage()),
                        sideEffect, "NOT_DISPATCHED");
            }
            // This immutable value set survives a source/schema change while the response is in flight.
            var protection = HttpApiResponseProtection.prepare(current.owner().acceptedContract(), path, query,
                    bound.jsonBody(), current.credential() == null ? null : current.credential().secret());
            String requestBody = encodeBody(bound.jsonBody());
            attempted = true;
            WorkflowHttpClient.HttpExecutionResult response = http.execute(new WorkflowHttpClient.HttpExecutionRequest(
                    bound.method(), current.connection().origin() + bound.encodedRoute(), bound.queryParams(),
                    bound.jsonBody() == null ? Map.of("Accept", "application/json")
                            : Map.of("Accept", "application/json", "Content-Type", "application/json"),
                    bound.jsonBody() == null ? "NONE" : "JSON", requestBody,
                    WorkflowHttpClient.DEFAULT_TIMEOUT_MS, current.connection().credentialRef(), identity,
                    credentialRevision, !write));
            Integer status = response.statusCode() > 0 ? response.statusCode() : null;
            if (!response.success()) {
                boolean preDispatch = preDispatchCode(response.code());
                if (write && status == null && !preDispatch) {
                    return new Invocation(false, "HTTP_API_WORKFLOW_RESULT_UNCONFIRMED", null, null, null,
                            sideEffect, "UNCONFIRMED");
                }
                return new Invocation(false, response.code(), null, status, null, sideEffect,
                        preDispatch ? "NOT_DISPATCHED" : "CONFIRMED");
            }
            if (write && !(response.parsedBody() instanceof Map<?, ?>)) {
                return new Invocation(false, "HTTP_API_WORKFLOW_RESULT_UNCONFIRMED", null, status, null,
                        sideEffect, "UNCONFIRMED");
            }
            Object result = response.parsedBody() == null ? response.body() : response.parsedBody();
            return new Invocation(true, "RUNTIME_HTTP_API_EXECUTED", protection.protect(result),
                    response.statusCode(), null, sideEffect, "CONFIRMED");
        } catch (RuntimeException unavailable) {
            return new Invocation(false, attempted ? "HTTP_API_WORKFLOW_RESULT_UNCONFIRMED"
                    : "HTTP_API_WORKFLOW_PRE_DISPATCH_UNAVAILABLE", null, null, null, sideEffect,
                    attempted ? "UNCONFIRMED" : "NOT_DISPATCHED");
        }
    }

    private static boolean preDispatchCode(String code) {
        return code != null && (code.startsWith("RUNTIME_HTTP_EGRESS_") || Set.of(
                "RUNTIME_HTTP_CREDENTIAL_REVISION_CHANGED", "RUNTIME_HTTP_CREDENTIAL_DENIED",
                "RUNTIME_HTTP_CREDENTIAL_UNAVAILABLE", "RUNTIME_HTTP_URL_REQUIRED",
                "RUNTIME_HTTP_CREDENTIAL_TYPE_UNSUPPORTED").contains(code));
    }

    private String encodeBody(Map<String, Object> body) {
        if (body == null) return null;
        try { return json.writeValueAsString(body); }
        catch (Exception invalid) { throw new IllegalArgumentException("HTTP_API_WORKFLOW_INPUT_INVALID", invalid); }
    }

    private Current current(long apiId, String reference, Long projectId, String projectCode) {
        if (apiId <= 0 || !StringUtils.hasText(reference) || !reference.startsWith("http-api:")
                || projectId == null || projectId <= 0 || !StringUtils.hasText(projectCode)) {
            throw invalid("HTTP_API_OWNER_IDENTITY_INVALID");
        }
        HttpApiConsoleContracts.ExecutionContext owner = capability.getHttpApiExecutionContext(apiId, projectCode);
        if (owner == null || owner.contractVersion() != HttpApiConsoleContracts.VERSION
                || !Objects.equals(owner.apiId(), apiId) || !Objects.equals(owner.qualifiedName(), reference)
                || !Objects.equals(owner.projectId(), projectId)
                || !Objects.equals(owner.projectCode(), projectCode)
                || !StringUtils.hasText(owner.environment())) throw invalid("HTTP_API_OWNER_CHANGED");
        if (!owner.sourceConfirmed() || !"ACCEPTED".equals(owner.sourceStatus())
                || !Objects.equals(owner.acceptedContractHash(), owner.candidateContractHash())
                || !hash(owner.acceptedContractHash()) || !hash(owner.sourceSetRevision())) {
            throw invalid("HTTP_API_SOURCE_NOT_READY");
        }
        if (owner.acceptedContract() == null
                || !Objects.equals(owner.httpMethod(), owner.acceptedContract().path("identity").path("method").asText())
                || HttpApiRequestPolicy.unsupportedReason(owner.acceptedContract()) != null
                || RuntimeHttpApiVerificationService.isMarket(owner.qualifiedName()) && !"GET".equals(owner.httpMethod())) {
            throw invalid("HTTP_API_WORKFLOW_CONTRACT_UNSUPPORTED");
        }
        HttpApiConsoleContracts.ConnectionCommand command = new HttpApiConsoleContracts.ConnectionCommand(
                HttpApiConsoleContracts.VERSION, apiId, reference, projectId, projectCode, owner.environment(), null);
        HttpApiConsoleContracts.ConnectionView view = connections.read(command);
        RuntimeHttpApiConnectionService.ConnectionSnapshot connection = connections.snapshot(reference);
        if (view == null || !"CONFIGURED".equals(view.status()) || connection == null
                || !Objects.equals(view.revision(), connection.revision())
                || !Objects.equals(connection.projectId(), projectId)
                || !Objects.equals(connection.projectCode(), projectCode)
                || !Objects.equals(connection.environment(), owner.environment())) {
            throw invalid("HTTP_API_CONNECTION_NOT_READY");
        }
        RuntimeWorkflowCredentialRuntime credential = connections.selectedCredential(
                connection.authMode(), connection.credentialRef(), owner);
        if (credential != null && !StringUtils.hasText(credential.revision())) {
            throw invalid("HTTP_API_CREDENTIAL_REVISION_UNAVAILABLE");
        }
        if (RuntimeHttpApiVerificationService.isMarket(owner.qualifiedName())) {
            var proof = view.verification();
            if (proof == null || !"VERIFIED".equals(proof.status())
                    || !Objects.equals(owner.apiId(), proof.apiId())
                    || !Objects.equals(owner.acceptedContractHash(), proof.acceptedContractHash())
                    || !Objects.equals(owner.sourceSetRevision(), proof.sourceSetRevision())
                    || !Objects.equals(connection.revision(), proof.connectionRevision())
                    || !Objects.equals(credential == null ? null : credential.revision(), proof.credentialRevision())) {
                throw invalid("HTTP_API_MARKET_VERIFICATION_REQUIRED");
            }
        }
        return new Current(owner, connection, credential);
    }

    private RuntimeWorkflowHttpApiPinEntity requirePin(GraphSpec.Node node) {
        Long pinId = node == null || node.getRef() == null ? null : node.getRef().getDefinitionId();
        RuntimeWorkflowHttpApiPinEntity row = pinId == null || pinId <= 0 ? null : pins.selectById(pinId);
        if (row == null || row.getWorkflowVersionId() == null || row.getWorkflowVersionId() <= 0
                || !Objects.equals(row.getNodeId(), node.getId())
                || !Objects.equals(row.getQualifiedName(), node.getRef().getQualifiedName())
                || !Objects.equals(row.getAcceptedContractHash(), node.getRef().getContractHash())
                || row.getApiId() != apiId(node)) throw invalid("HTTP_API_PUBLISHED_PIN_INVALID");
        return row;
    }

    private long apiId(GraphSpec.Node node) {
        Object raw = node == null || node.getConfig() == null ? null : node.getConfig().get("httpApiAssetId");
        if (!(raw instanceof Number value) || value.longValue() <= 0
                || value.doubleValue() != value.longValue()) throw invalid("HTTP_API_OWNER_ID_REQUIRED");
        return value.longValue();
    }

    private String invalidInputField(com.fasterxml.jackson.databind.JsonNode contract, String reason) {
        if (contract == null || reason == null) return null;
        for (var parameter : contract.path("parameters")) {
            String name = parameter.path("name").asText();
            String location = parameter.path("location").asText();
            if (name.isBlank() || !Set.of("PATH", "QUERY").contains(location)) continue;
            if (reason.endsWith("：" + name)) return ("PATH".equals(location) ? "pathParams." : "queryParams.") + name;
        }
        var fields = contract.path("requestBody").path("schema").path("properties").fieldNames();
        while (fields.hasNext()) {
            String name = fields.next();
            if (reason.endsWith("：" + name)) return "body." + name;
        }
        return null;
    }

    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static Rejected invalid(String code) { return new Rejected(code); }
    private static Invocation failure(String code) { return new Invocation(false, code, null, null, null); }
    private static final class Rejected extends IllegalArgumentException {
        private Rejected(String code) { super(code); }
    }
    private record Current(HttpApiConsoleContracts.ExecutionContext owner,
                           RuntimeHttpApiConnectionService.ConnectionSnapshot connection,
                           RuntimeWorkflowCredentialRuntime credential) { }
}

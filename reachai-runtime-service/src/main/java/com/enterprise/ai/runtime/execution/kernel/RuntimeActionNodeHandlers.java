package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import com.enterprise.ai.common.response.BusinessResponseEnvelope;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.PageBridgeExecutionRequest;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.PageBridgeExecutionResponse;
import com.enterprise.ai.runtime.execution.policy.RuntimeEvalExecutionContext;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.execution.RuntimeHttpApiToolExecutionPort;
import com.enterprise.ai.runtime.execution.interaction.WorkflowApprovalAssignment;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionNodeHandler;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionType;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionUiRequestFactory;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Executes governed interaction, Capability, and Page Bridge GraphSpec nodes. */
final class RuntimeActionNodeHandlers {

    private static final int DEFAULT_PAGE_BRIDGE_CONFIRMATION_TIMEOUT_MS = 90_000;
    private static final int DEFAULT_PAGE_BRIDGE_EXECUTION_TIMEOUT_MS = 30_000;
    private static final long DEFAULT_CAPABILITY_TIMEOUT_MS = 30_000L;
    private static final long MIN_CAPABILITY_TIMEOUT_MS = 1_000L;
    private static final long MAX_CAPABILITY_TIMEOUT_MS = 600_000L;
    private static final String CAPABILITY_INVOCATION_CONTROLS_KEY =
            "__runtimeCapabilityInvocationControls";
    private static final String DOCUMENT_NAVIGATION_KEY = "__runtimeDocumentNavigation";

    private final RuntimeCapabilityCatalogClient capabilityClient;
    private final RuntimeControlCatalogClient controlClient;
    private final RuntimeNodeValueResolver valueResolver;
    private final RuntimeHttpApiToolExecutionPort httpApis;

    RuntimeActionNodeHandlers(RuntimeCapabilityCatalogClient capabilityClient,
                              RuntimeControlCatalogClient controlClient,
                              RuntimeNodeValueResolver valueResolver) {
        this(capabilityClient, controlClient, valueResolver, null);
    }

    RuntimeActionNodeHandlers(RuntimeCapabilityCatalogClient capabilityClient,
                              RuntimeControlCatalogClient controlClient,
                              RuntimeNodeValueResolver valueResolver,
                              RuntimeHttpApiToolExecutionPort httpApis) {
        this.capabilityClient = capabilityClient;
        this.controlClient = controlClient;
        this.valueResolver = valueResolver;
        this.httpApis = httpApis;
    }

    RuntimeGraphSpecExecutionResult executeInteraction(GraphSpec.Node node,
                                                       RuntimeNodeExecutionContext execution) {
        Map<String, Object> context = execution.variables();
        RuntimeEvalExecutionContext evaluation = RuntimeTrustedExecutionContexts.evaluation(context);
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        WorkflowInteractionType interactionType = WorkflowInteractionType.from(config.get("interactionType"));
        WorkflowExecutionIdentity identity = RuntimeTrustedExecutionContexts.identity(context);
        if ((identity.source() == WorkflowExecutionIdentity.Source.AUTOMATION
                || identity.source() == WorkflowExecutionIdentity.Source.AUTOMATION_EMPLOYEE)
                && interactionType.blocking()) {
            return failure("AUTOMATION_INTERACTION_REQUIRED",
                    "Scheduled Workflow cannot wait for human interaction at node: " + node.getId(),
                    node.getId(), "INTERACTION");
        }
        if ((identity.source() == WorkflowExecutionIdentity.Source.MCP_REMOTE_CLIENT
                || identity.source() == WorkflowExecutionIdentity.Source.MCP_EMPLOYEE)
                && interactionType.blocking()) {
            return failure("MCP_WORKFLOW_INTERACTION_UNSUPPORTED",
                    "MCP tools/call cannot suspend for human interaction at node: " + node.getId(),
                    node.getId(), "INTERACTION");
        }
        if (evaluation.isEvaluation() && interactionType.blocking()) {
            return evalSideEffectBlocked(node, "INTERACTION",
                    "Eval execution blocks stateful INTERACTION nodes because resumable sessions are not sandboxed",
                    evaluation);
        }
        return WorkflowInteractionNodeHandler.execute(node, context);
    }

    /**
     * HUMAN_APPROVAL pauses the run until the person running it approves or rejects, reusing the
     * INTERACTION pause/resume state. Routes: approved / rejected. Automation, MCP and evaluation runs
     * cannot pause and fail closed.
     */
    RuntimeGraphSpecExecutionResult executeHumanApproval(GraphSpec.Node node, RuntimeNodeExecutionContext execution) {
        Map<String, Object> context = execution.variables();
        Map<String, Object> raw = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> config = new LinkedHashMap<>();
        if (raw.get("approvalConfig") instanceof Map<?, ?> nested) {
            nested.forEach((key, value) -> config.put(String.valueOf(key), value));
        }
        raw.forEach((key, value) -> {
            if (!"approvalConfig".equals(key) && value != null) config.put(key, value);
        });
        WorkflowExecutionIdentity identity = RuntimeTrustedExecutionContexts.identity(context);
        if (identity.source() == WorkflowExecutionIdentity.Source.AUTOMATION) {
            return failure("AUTOMATION_INTERACTION_REQUIRED",
                    "定时自动化不能等待人工确认：" + node.getId(), node.getId(), "HUMAN_APPROVAL");
        }
        if (identity.source() == WorkflowExecutionIdentity.Source.MCP_REMOTE_CLIENT
                || identity.source() == WorkflowExecutionIdentity.Source.MCP_EMPLOYEE) {
            return failure("MCP_WORKFLOW_INTERACTION_UNSUPPORTED",
                    "通过 MCP 调用时不能暂停等待人工确认：" + node.getId(), node.getId(), "HUMAN_APPROVAL");
        }
        RuntimeEvalExecutionContext evaluation = RuntimeTrustedExecutionContexts.evaluation(context);
        if (evaluation.isEvaluation()) {
            return evalSideEffectBlocked(node, "HUMAN_APPROVAL",
                    "Eval execution blocks HUMAN_APPROVAL because resumable sessions are not sandboxed", evaluation);
        }
        Map<String, Object> interactionConfig = new LinkedHashMap<>();
        interactionConfig.put("interactionType", "CONFIRM_ACTION");
        interactionConfig.put("title", firstNonBlank(text(config.get("title")), node.getName(), "人工确认"));
        String prompt = text(config.get("prompt"));
        interactionConfig.put("message", valueResolver.renderTemplate(
                prompt == null || prompt.isBlank() ? "{{ lastOutput }}" : prompt, context));
        interactionConfig.put("outputAlias", firstNonBlank(text(config.get("outputAlias")), "approval_result"));
        WorkflowApprovalAssignment assignment = WorkflowApprovalAssignment.fromNodeConfig(config);
        if (assignment != null && assignment.isEmpty()) {
            return failure("WORKFLOW_APPROVAL_ASSIGNEE_REQUIRED",
                    "人工确认节点选择了指定审批人，但没有配置人员或角色：" + node.getId(), node.getId(), "HUMAN_APPROVAL");
        }
        Object timeout = config.get("timeoutSeconds");
        if (timeout != null) {
            interactionConfig.put("ttlSeconds", timeout);
        } else if (assignment != null) {
            interactionConfig.put("ttlSeconds", WorkflowApprovalAssignment.DEFAULT_TTL_SECONDS);
        }
        GraphSpec.Node synthetic = new GraphSpec.Node();
        synthetic.setId(node.getId());
        synthetic.setName(node.getName());
        synthetic.setType("INTERACTION");
        synthetic.setConfig(interactionConfig);
        RuntimeGraphSpecExecutionResult result = WorkflowInteractionNodeHandler.execute(synthetic, context);
        Map<String, Object> metadata = result.metadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(result.metadata());
        metadata.put("nodeType", "HUMAN_APPROVAL");
        // Only the Agent/Embed persistence path turns this into a named-approver wait; debug keeps the confirm card.
        if (assignment != null && !result.success()) metadata.put(WorkflowApprovalAssignment.METADATA_KEY, assignment.toMap());
        if (result.success()) {
            String route = "confirm".equalsIgnoreCase(String.valueOf(metadata.get("route"))) ? "approved" : "rejected";
            metadata.put("route", route);
            metadata.put("lastRoute", route);
            context.put("route", route);
            context.put("lastRoute", route);
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("approved", "approved".equals(route));
            structured.put("decision", route);
            WorkflowApprovalAssignment.copyDecisionFields(metadata.get("submittedPayload"), structured);
            WorkflowApprovalAssignment.mergeOutput(context, firstNonBlank(text(config.get("outputAlias")), "approval_result"), structured);
            metadata.put("structuredOutput", structured);
            return new RuntimeGraphSpecExecutionResult(true, result.code(), "approved".equals(route) ? "已批准" : "已拒绝",
                    node.getId(), "HUMAN_APPROVAL", result.steps(), metadata);
        }
        return new RuntimeGraphSpecExecutionResult(false, result.code(), result.answer(), node.getId(), "HUMAN_APPROVAL",
                result.steps(), metadata);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    RuntimeGraphSpecExecutionResult executeTool(GraphSpec.Node node,
                                                RuntimeNodeExecutionContext execution) {
        String nodeType = "TOOL";
        Map<String, Object> context = execution.variables();
        RuntimeGraphSpecExecutionCancellation cancel = execution.cancellation();
        String qualifiedName = resolveQualifiedName(node);
        if (!StringUtils.hasText(qualifiedName)) {
            return failure("RUNTIME_GRAPH_TOOL_REF_REQUIRED",
                    nodeType + " node requires ref.qualifiedName, config.qualifiedName, or config.ref",
                    node.getId(), nodeType);
        }
        if (cancel.isCancelled()) {
            return cancelled(node.getId(), nodeType);
        }
        RuntimeEvalExecutionContext evaluation = RuntimeTrustedExecutionContexts.evaluation(context);
        if (qualifiedName.startsWith("http-api:")
                || node.getConfig() != null && node.getConfig().containsKey("httpApiAssetId")) {
            if (evaluation.blocksRawExternalCalls()) {
                return evalSideEffectBlocked(node, nodeType,
                        "Eval execution blocks HTTP API TOOL nodes until a sandbox adapter is configured",
                        evaluation);
            }
            return executeHttpApiTool(node, qualifiedName, context);
        }
        if (evaluation.requiresReadOnlyCapability()) {
            Map<String, Object> definition;
            try {
                definition = capabilityClient.getToolDefinition(qualifiedName);
            } catch (Exception lookupFailure) {
                return evalSideEffectBlocked(node, nodeType,
                        "Eval could not verify the Capability side-effect declaration: " + qualifiedName,
                        evaluation);
            }
            String sideEffect = text(definition == null ? null : definition.get("sideEffect"));
            String normalizedSideEffect = StringUtils.hasText(sideEffect)
                    ? sideEffect.toUpperCase(Locale.ROOT) : "";
            if (!"READ".equals(normalizedSideEffect) && !"READ_ONLY".equals(normalizedSideEffect)) {
                return evalSideEffectBlocked(node, nodeType,
                        "Eval permits only explicitly READ_ONLY Capabilities; blocked " + qualifiedName,
                        evaluation);
            }
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("input", buildToolInput(node, context));
        request.put("context", toolExecutionContext(node, nodeType, context));
        if (node.getRef() != null && StringUtils.hasText(node.getRef().getContractHash())) {
            Map<String, Object> constraints = new LinkedHashMap<>();
            var ref = node.getRef();
            constraints.put("expectedContractHash", ref.getContractHash());
            if (ref.getAssetId() != null) constraints.put("expectedAssetId", ref.getAssetId());
            if (ref.getAcceptedRevisionId() != null) constraints.put("expectedAcceptedRevisionId", ref.getAcceptedRevisionId());
            if (StringUtils.hasText(ref.getBusinessContractHash())) constraints.put("expectedBusinessContractHash", ref.getBusinessContractHash());
            if (StringUtils.hasText(ref.getBindingHash())) constraints.put("expectedBindingHash", ref.getBindingHash());
            if (StringUtils.hasText(ref.getExecutionRevision())) constraints.put("expectedExecutionRevision", ref.getExecutionRevision());
            if (StringUtils.hasText(ref.getProjectCode())) constraints.put("expectedProjectCode", ref.getProjectCode());
            request.put("constraints", constraints);
        }
        CapabilityInvocationControl invocationControl = capabilityInvocationControl(node, qualifiedName, context);
        request.put("invocationId", invocationControl.invocationId());
        request.put("idempotencyKey", invocationControl.idempotencyKey());
        request.put("deadlineEpochMs", invocationControl.deadlineEpochMs());
        request.put("traceContext", capabilityTraceContext(context, invocationControl.invocationId()));
        request.put(RuntimeCapabilityCatalogClient.TRUSTED_IDENTITY_ATTRIBUTE,
                RuntimeTrustedExecutionContexts.identity(context));
        if (evaluation.isEvaluation()) {
            request.put(RuntimeCapabilityCatalogClient.TRUSTED_EVAL_CONTEXT_ATTRIBUTE, evaluation);
        }
        try {
            CapabilityInvocationResponse result = capabilityClient.invokeTool(qualifiedName, request);
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), nodeType);
            }
            Object businessOutput = result == null ? null : result.data();
            Object output = firstPresent(businessOutput, result == null ? null : result.toLegacyMap());
            Map<String, Object> metadata = nodeMetadata(node, nodeType);
            metadata.put("qualifiedName", qualifiedName);
            if (output != null) {
                metadata.put("structuredOutput", output);
            }
            Optional<BusinessResponseEnvelope.Failure> payloadFailure =
                    BusinessResponseEnvelope.failure(businessOutput);
            boolean upstreamBusinessFailure = result != null
                    && result.status() == CapabilityInvocationStatus.BUSINESS_FAILED;
            if (payloadFailure.isPresent() || upstreamBusinessFailure) {
                BusinessResponseEnvelope.Failure failure = payloadFailure.orElseGet(() ->
                        new BusinessResponseEnvelope.Failure(
                                result.businessCode(),
                                firstText(result.message(), BusinessResponseEnvelope.FAILURE_MESSAGE)));
                metadata.put("retryableFailure", false);
                if (failure.businessCode() != null) {
                    metadata.put("businessCode", failure.businessCode());
                }
                return new RuntimeGraphSpecExecutionResult(
                        false, "RUNTIME_GRAPH_TOOL_BUSINESS_RESPONSE_FAILED", failure.message(),
                        node.getId(), nodeType, List.of(step("execute-node", node.getId())), metadata);
            }
            if (result != null && !result.success()) {
                metadata.put("retryableFailure", result.retryable());
                metadata.put("failureCategory", result.failureCategory().name());
                return new RuntimeGraphSpecExecutionResult(
                        false,
                        firstText(result.code(), "RUNTIME_GRAPH_TOOL_FAILED"),
                        firstText(result.message(), nodeType + " node execution failed"),
                        node.getId(), nodeType, List.of(step("execute-node", node.getId())), metadata);
            }
            String answer = output == null ? "" : String.valueOf(output);
            return success(node, nodeType, answer, metadata, "execute-node");
        } catch (Exception ex) {
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), nodeType);
            }
            return failure("RUNTIME_GRAPH_TOOL_FAILED",
                    nodeType + " node execution failed: " + ex.getMessage(), node.getId(), nodeType);
        }
    }

    private RuntimeGraphSpecExecutionResult executeHttpApiTool(GraphSpec.Node node,
                                                                 String qualifiedName,
                                                                 Map<String, Object> context) {
        if (httpApis == null) return failure("HTTP_API_WORKFLOW_PROJECTION_UNAVAILABLE",
                "HTTP API Workflow projection is unavailable", node.getId(), "TOOL");
        RuntimeHttpApiToolExecutionPort.Invocation invocation = httpApis.invoke(node,
                buildHttpApiInput(node, context), RuntimeTrustedExecutionContexts.identity(context));
        Map<String, Object> metadata = nodeMetadata(node, "TOOL");
        metadata.put("qualifiedName", qualifiedName);
        // HTTP dispatch can be uncertain. A GraphSpec retry flag must never re-send it automatically.
        metadata.put("retryableFailure", false);
        if (invocation.sideEffect() != null) metadata.put("sideEffect", invocation.sideEffect());
        if (invocation.dispatchStage() != null) metadata.put("dispatchStage", invocation.dispatchStage());
        if (com.enterprise.ai.common.capability.HttpApiRequestPolicy.requiresWriteConfirmation(invocation.sideEffect()))
            metadata.put("nonRecoverableWrite", true);
        if ("UNCONFIRMED".equals(invocation.dispatchStage())) {
            metadata.put("outcomeClass", "UNKNOWN");
            metadata.put("businessOutcome", "UNCONFIRMED");
        }
        if (invocation.httpStatus() != null) metadata.put("httpStatus", invocation.httpStatus());
        if (invocation.inputField() != null) metadata.put("inputField", invocation.inputField());
        if (!invocation.success()) return new RuntimeGraphSpecExecutionResult(false, invocation.code(),
                // A business system's own failure text (HTTP 200 with an error inside) is what the user needs to hear.
                "HTTP_API_BUSINESS_ERROR".equals(invocation.code()) && invocation.output() instanceof String reason
                        ? "业务系统返回失败：" + reason
                        : "RUNTIME_HTTP_LOGIN_FAILED".equals(invocation.code())
                                && invocation.output() instanceof String loginFailure
                                ? loginFailure + "。请管理员在「接口鉴权」里核对登录凭据" : invocation.code(),
                node.getId(), "TOOL", List.of(step("execute-node", node.getId())), metadata);
        metadata.put("structuredOutput", invocation.output());
        return success(node, "TOOL", String.valueOf(invocation.output()), metadata, "execute-node");
    }

    RuntimeGraphSpecExecutionResult executePageAction(GraphSpec.Node node,
                                                      RuntimeNodeExecutionContext execution) {
        Map<String, Object> context = execution.variables();
        RuntimeGraphSpecExecutionCancellation cancel = execution.cancellation();
        RuntimeEvalExecutionContext evaluation = RuntimeTrustedExecutionContexts.evaluation(context);
        if (evaluation.blocksRawExternalCalls()) {
            return evalSideEffectBlocked(node, "PAGE_ACTION",
                    "Eval execution blocks PAGE_ACTION nodes until a sandbox Page Bridge adapter is configured",
                    evaluation);
        }
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String sessionId = text(context.get("sessionId"));
        String projectCode = firstText(text(config.get("projectCode")), text(context.get("projectCode")));
        String agentId = text(context.get("agentId"));
        String targetPageKey = text(config.get("pageKey"));
        String actionKey = text(config.get("actionKey"));
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(projectCode)
                || !StringUtils.hasText(agentId) || !StringUtils.hasText(targetPageKey)
                || !StringUtils.hasText(actionKey)) {
            return failure("RUNTIME_PAGE_ACTION_CONTEXT_REQUIRED",
                    "PAGE_ACTION requires sessionId, projectCode, agentId, pageKey and actionKey",
                    node.getId(), "PAGE_ACTION");
        }
        if (cancel.isCancelled()) {
            return cancelled(node.getId(), "PAGE_ACTION");
        }
        Object draftMarker = context.get(com.enterprise.ai.runtime.execution.context.TrustedPageDraftExecution.CONTEXT_KEY);
        com.enterprise.ai.runtime.execution.context.TrustedPageDraftExecution trustedDraft =
                draftMarker instanceof com.enterprise.ai.runtime.execution.context.TrustedPageDraftExecution marker
                        ? marker : null;
        Map<?, ?> draftDeclaration = trustedDraft != null && trustedDraft.pin().get("declaration") instanceof Map<?, ?> declaration
                ? declaration : Map.of();
        if (trustedDraft != null && (!java.util.Objects.equals(projectCode, draftDeclaration.get("projectCode"))
                || !java.util.Objects.equals(targetPageKey, draftDeclaration.get("pageKey"))
                || !java.util.Objects.equals(actionKey, draftDeclaration.get("actionKey")))) {
            return failure("RUNTIME_PAGE_DRAFT_PIN_MISMATCH", "Approved page action target changed", node.getId(), "PAGE_ACTION");
        }
        // In the trusted draft path, the exact confirmed subset is sent directly;
        // absent optional mappings must not turn into null-valued writes.
        Map<String, Object> args = trustedDraft == null ? buildToolInput(node, context) : trustedDraft.args();
        int executionTimeoutMs = intValue(
                context.get("pageBridgeTimeoutMs"), DEFAULT_PAGE_BRIDGE_EXECUTION_TIMEOUT_MS);
        WorkflowExecutionIdentity identity = RuntimeTrustedExecutionContexts.identity(context);
        boolean trustedEmbed = identity != null && identity.canResolveUserAcl()
                && identity.source() == WorkflowExecutionIdentity.Source.EMBED_SESSION;
        String resumeNavigationId = null;
        Object pendingNavigation = context.get(DOCUMENT_NAVIGATION_KEY);
        if (pendingNavigation instanceof Map<?, ?> pending && node.getId().equals(pending.get("nodeId"))) {
            Object rawResume = context.get(WorkflowInteractionCodes.RESUME_CONTEXT_KEY);
            if (!(rawResume instanceof Map<?, ?> resume)
                    || !node.getId().equals(resume.get("nodeId"))
                    || !java.util.Objects.equals(context.get(WorkflowInteractionCodes.PENDING_INTERACTION_ID_KEY),
                            resume.get("interactionId"))
                    || !"confirm".equals(resume.get("action"))) {
                return failure("PAGE_BRIDGE_DOCUMENT_NAVIGATION_INVALID",
                        "The pending navigation must be resumed by its authenticated interaction", node.getId(), "PAGE_ACTION");
            }
            resumeNavigationId = text(pending.get("requestId"));
            if (!StringUtils.hasText(resumeNavigationId)) {
                return failure("PAGE_BRIDGE_DOCUMENT_NAVIGATION_INVALID", "Missing navigation request", node.getId(), "PAGE_ACTION");
            }
        }
        String navigationInteractionId = resumeNavigationId == null ? WorkflowInteractionUiRequestFactory.newInteractionId()
                : text(context.get(WorkflowInteractionCodes.PENDING_INTERACTION_ID_KEY));
        try {
            PageBridgeExecutionResponse response = controlClient.executePageBridge(new PageBridgeExecutionRequest(
                    sessionId,
                    projectCode,
                    agentId,
                    text(context.get("pageKey")),
                    targetPageKey,
                    firstText(text(config.get("route")), text(config.get("routePattern"))),
                    actionKey,
                    args,
                    trustedDraft != null || Boolean.TRUE.equals(config.get("confirm"))
                            || Boolean.TRUE.equals(config.get("confirmRequired")),
                    DEFAULT_PAGE_BRIDGE_CONFIRMATION_TIMEOUT_MS,
                    executionTimeoutMs,
                    trustedEmbed ? identity.source().name() : null,
                    trustedEmbed ? firstText(identity.tenantId(), "default") : null,
                    trustedEmbed ? identity.userId() : null,
                    trustedEmbed && context.get(com.enterprise.ai.runtime.execution.context.TrustedSupervisorTrace.CONTEXT_KEY)
                            instanceof com.enterprise.ai.runtime.execution.context.TrustedSupervisorTrace trustedTrace
                            ? trustedTrace.traceId() : null,
                    resumeNavigationId, navigationInteractionId,
                    trustedDraft == null ? null : ((Number) draftDeclaration.get("approvalId")).longValue(),
                    trustedDraft == null ? null : ((Number) draftDeclaration.get("revision")).longValue(),
                    trustedDraft == null ? null : (String) draftDeclaration.get("catalogHash")));
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), "PAGE_ACTION");
            }
            if (response != null && "PAGE_BRIDGE_DOCUMENT_NAVIGATION_REQUIRED".equals(response.code())) {
                if (resumeNavigationId != null || !(response.data() instanceof Map<?, ?> navigation)
                        || !StringUtils.hasText(text(navigation.get("navigationRequestId")))) {
                    return failure("PAGE_BRIDGE_DOCUMENT_NAVIGATION_INVALID", "Invalid navigation response", node.getId(), "PAGE_ACTION");
                }
                String navigationId = text(navigation.get("navigationRequestId"));
                context.put(DOCUMENT_NAVIGATION_KEY, Map.of("nodeId", node.getId(), "requestId", navigationId));
                context.put(WorkflowInteractionCodes.PENDING_INTERACTION_ID_KEY, navigationInteractionId);
                context.put(WorkflowInteractionCodes.PENDING_INTERACTION_NODE_KEY, node.getId());
                GraphSpec.Node interaction = new GraphSpec.Node();
                interaction.setId(node.getId());
                interaction.setName(node.getName());
                interaction.setType("INTERACTION");
                interaction.setConfig(Map.of("interactionType", "CONFIRM_ACTION", "title", "继续页面操作",
                        "message", "请先确认并打开目标页面，随后继续操作。", "ttlSeconds", 120));
                RuntimeGraphSpecExecutionResult waiting = WorkflowInteractionNodeHandler.execute(interaction, context);
                Map<String, Object> waitingMetadata = new LinkedHashMap<>(waiting.metadata());
                if (waiting.uiRequest() instanceof Map<?, ?> originalUi) {
                    Map<String, Object> ui = new LinkedHashMap<>();
                    originalUi.forEach((key, value) -> ui.put(String.valueOf(key), value));
                    ui.put("extension", Map.of("documentNavigation", Map.of("version", 1,
                            "navigationRequestId", navigationId)));
                    Map<String, Object> behavior = new LinkedHashMap<>();
                    if (ui.get("behavior") instanceof Map<?, ?> existing) {
                        existing.forEach((key, value) -> behavior.put(String.valueOf(key), value));
                    }
                    // The document handoff is resumed only after target rebind;
                    // do not offer an early source-page interaction submission.
                    behavior.put("readonly", true);
                    behavior.put("blocking", true);
                    ui.put("behavior", behavior);
                    waitingMetadata.put("uiRequest", ui);
                }
                waitingMetadata.put("nodeType", "PAGE_ACTION");
                return new RuntimeGraphSpecExecutionResult(false, waiting.code(), waiting.answer(), node.getId(),
                        "PAGE_ACTION", waiting.steps(), waitingMetadata);
            }
            if (resumeNavigationId != null) {
                context.remove(DOCUMENT_NAVIGATION_KEY);
                context.remove(WorkflowInteractionCodes.RESUME_CONTEXT_KEY);
                context.remove(WorkflowInteractionCodes.PENDING_INTERACTION_ID_KEY);
                context.remove(WorkflowInteractionCodes.PENDING_INTERACTION_NODE_KEY);
            }
            Map<String, Object> metadata = nodeMetadata(node, "PAGE_ACTION");
            metadata.put("pageKey", targetPageKey);
            metadata.put("actionKey", actionKey);
            if (response == null || "PAGE_BRIDGE_RESULT_UNCONFIRMED".equals(response.code())) {
                metadata.put("outcomeClass", "UNKNOWN");
                metadata.put("businessOutcome", "UNCONFIRMED");
            }
            if (response != null) {
                putIfPresent(metadata, "pageBridgeCode", response.code());
                putIfPresent(metadata, "pageBridgeStatus", response.status());
                putIfPresent(metadata, "pageBridgePhases", response.phases());
                putIfPresent(metadata, "pageBridgeData", response.data());
                Map<String, Object> pageActionSummary = RuntimePageActionResults.pageActionResultSummary(actionKey, response);
                metadata.put("pageActionResultSummary", pageActionSummary);
                // Trace stays useful without replaying business rows or raw bridge data.
                metadata.put("traceSummary", pageActionSummary);
                if (response.data() != null) {
                    metadata.put("structuredOutput", response.data());
                }
                if (RuntimePageActionResults.isBusinessTerminalPageAction(response)) {
                    metadata.put("outcomeClass", "BUSINESS_TERMINAL");
                    metadata.put("businessOutcome", response.status());
                }
            }
            if (response == null || !response.success()) {
                return new RuntimeGraphSpecExecutionResult(
                        false,
                        response == null ? "PAGE_BRIDGE_RESULT_UNCONFIRMED" : response.code(),
                        response == null ? "页面操作结果未确认，请核对业务记录。" : response.status(),
                        node.getId(), "PAGE_ACTION",
                        List.of(step("execute-page-action", node.getId())), metadata);
            }
            if (RuntimePageActionResults.isBusinessTerminalPageAction(response)) {
                String status = text(response.status());
                // These outcomes explicitly prove the requested operation did not
                // happen. Stop the graph and the Supervisor continuation rather
                // than allowing an unconditional success ANSWER downstream.
                if ("PRECONDITION_FAILED".equalsIgnoreCase(status)) {
                    return new RuntimeGraphSpecExecutionResult(
                            false, "PAGE_BRIDGE_PRECONDITION_FAILED", RuntimePageActionResults.pageActionBusinessMessage(response),
                            node.getId(), "PAGE_ACTION",
                            List.of(step("execute-page-action", node.getId())), metadata);
                }
                if ("USER_CANCELLED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status)) {
                    return new RuntimeGraphSpecExecutionResult(
                            false, "RUNTIME_GRAPH_CANCELLED", RuntimePageActionResults.pageActionBusinessMessage(response),
                            node.getId(), "PAGE_ACTION",
                            List.of(step("execute-page-action", node.getId())), metadata);
                }
                return new RuntimeGraphSpecExecutionResult(
                        true, "RUNTIME_PAGE_ACTION_BUSINESS_TERMINAL", RuntimePageActionResults.pageActionBusinessMessage(response),
                        node.getId(), "PAGE_ACTION",
                        List.of(step("execute-page-action", node.getId())), metadata);
            }
            return new RuntimeGraphSpecExecutionResult(
                    true, "RUNTIME_PAGE_ACTION_EXECUTED",
                    trustedDraft != null ? trustedDraftResultMessage(draftDeclaration, args, trustedDraft.unhandledItemLabels())
                            : response.data() == null ? response.status() : String.valueOf(response.data()),
                    node.getId(), "PAGE_ACTION",
                    List.of(step("execute-page-action", node.getId())), metadata);
        } catch (Exception ex) {
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), "PAGE_ACTION");
            }
            Map<String, Object> metadata = nodeMetadata(node, "PAGE_ACTION");
            metadata.put("outcomeClass", "UNKNOWN");
            metadata.put("businessOutcome", "UNCONFIRMED");
            return new RuntimeGraphSpecExecutionResult(false, "PAGE_BRIDGE_RESULT_UNCONFIRMED",
                    "页面操作结果未确认，请核对业务记录。", node.getId(), "PAGE_ACTION", List.of(), metadata);
        }
    }

    private static String trustedDraftResultMessage(Map<?, ?> declaration, Map<String, Object> args, List<String> unhandledItemLabels) {
        Map<?, ?> labels = declaration.get("fieldLabels") instanceof Map<?, ?> map ? map : Map.of();
        java.util.StringJoiner fields = new java.util.StringJoiner("、");
        args.keySet().forEach(key -> fields.add(labels.get(key) instanceof String label ? label : key));
        String missing = unhandledItemLabels.isEmpty() ? "" : "原请求中的这些条目未处理：" + String.join("、", unhandledItemLabels) + "。";
        return "页面动作已执行；本次请求填写的字段：" + fields + "。" + missing
                + "未列出的内容未处理；业务方声明此动作不会主动提交，但业务页面可能自动保存，请检查。";
    }

    @SuppressWarnings("unchecked")
    private CapabilityInvocationControl capabilityInvocationControl(GraphSpec.Node node,
                                                                    String qualifiedName,
                                                                    Map<String, Object> context) {
        Map<String, Object> controls;
        Object existingControls = context.get(CAPABILITY_INVOCATION_CONTROLS_KEY);
        if (existingControls instanceof Map<?, ?> raw) {
            controls = (Map<String, Object>) raw;
        } else {
            controls = new LinkedHashMap<>();
            context.put(CAPABILITY_INVOCATION_CONTROLS_KEY, controls);
        }
        String controlKey = node.getId() + "|" + qualifiedName;
        Object existing = controls.get(controlKey);
        if (existing instanceof Map<?, ?> raw) {
            String invocationId = text(raw.get("invocationId"));
            String idempotencyKey = text(raw.get("idempotencyKey"));
            Long deadline = longValue(raw.get("deadlineEpochMs"));
            if (StringUtils.hasText(invocationId) && StringUtils.hasText(idempotencyKey) && deadline != null) {
                return new CapabilityInvocationControl(invocationId, idempotencyKey, deadline);
            }
        }
        String invocationId = UUID.randomUUID().toString();
        String idempotencyKey = "wf:" + invocationId;
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Long configuredTimeout = longValue(config.get("timeoutMs"));
        long timeoutMs = configuredTimeout == null ? DEFAULT_CAPABILITY_TIMEOUT_MS : configuredTimeout;
        timeoutMs = Math.max(MIN_CAPABILITY_TIMEOUT_MS, Math.min(MAX_CAPABILITY_TIMEOUT_MS, timeoutMs));
        long deadlineEpochMs = Math.addExact(System.currentTimeMillis(), timeoutMs);
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("invocationId", invocationId);
        stored.put("idempotencyKey", idempotencyKey);
        stored.put("deadlineEpochMs", deadlineEpochMs);
        controls.put(controlKey, stored);
        return new CapabilityInvocationControl(invocationId, idempotencyKey, deadlineEpochMs);
    }

    private Map<String, Object> capabilityTraceContext(Map<String, Object> context, String invocationId) {
        String sourceTraceId = firstText(
                text(context.get("supervisorTraceId")), text(context.get("traceId")),
                text(context.get("runId")), invocationId);
        String traceId = hexadecimalId(sourceTraceId, 32);
        String spanId = hexadecimalId(invocationId, 16);
        return Map.of(
                "traceId", traceId,
                "spanId", spanId,
                "traceparent", "00-" + traceId + "-" + spanId + "-01");
    }

    private String hexadecimalId(String value, int length) {
        String normalized = value == null ? "" : value.replace("-", "").toLowerCase(Locale.ROOT);
        if (normalized.matches("[0-9a-f]{" + length + "}")
                && !normalized.matches("0{" + length + "}")) {
            return normalized;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            String hashed = HexFormat.of().formatHex(digest).substring(0, length);
            return hashed.matches("0{" + length + "}") ? "0".repeat(length - 1) + "1" : hashed;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private Map<String, Object> toolExecutionContext(GraphSpec.Node node,
                                                     String nodeType,
                                                     Map<String, Object> context) {
        Map<String, Object> executionContext = new LinkedHashMap<>();
        executionContext.put("nodeId", node.getId());
        executionContext.put("nodeType", nodeType);
        Map<String, Object> metadata = mapValue(context.get("metadata"));
        copyContextValue(executionContext, "tenantId", context, metadata);
        copyContextValue(executionContext, "externalUserId", context, metadata);
        copyContextValue(executionContext, "globalUserId", context, metadata);
        copyContextValue(executionContext, "userName", context, metadata);
        copyContextValue(executionContext, "deptId", context, metadata);
        copyContextValue(executionContext, "deptName", context, metadata);
        copyContextValue(executionContext, "roles", context, metadata);
        copyContextValue(executionContext, "attributes", context, metadata);
        copyContextValue(executionContext, "agentId", context, metadata);
        copyContextValue(executionContext, "sessionId", context, metadata);
        copyContextValue(executionContext, "supervisorTraceId", context, metadata);
        copyContextValue(executionContext, "pageInstanceId", context, metadata);
        copyContextValue(executionContext, "origin", context, metadata);
        copyContextValue(executionContext, "route", context, metadata);
        if (!executionContext.containsKey("externalUserId") && context.get("userId") != null) {
            executionContext.put("externalUserId", context.get("userId"));
        }
        return executionContext;
    }

    private void copyContextValue(Map<String, Object> target,
                                  String key,
                                  Map<String, Object> context,
                                  Map<String, Object> metadata) {
        Object value = context.get(key);
        if (value == null && metadata != null) {
            value = metadata.get(key);
        }
        if (value != null) {
            target.put(key, value);
        }
    }

    private Map<String, Object> buildHttpApiInput(GraphSpec.Node node, Map<String, Object> context) {
        return RuntimeHttpApiInputBindings.resolve(node, context);
    }

    private Map<String, Object> buildToolInput(GraphSpec.Node node, Map<String, Object> context) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> mapping = mapValue(config.get("inputMapping"));
        if (mapping == null || mapping.isEmpty()) {
            mapping = mapValue(config.get("args"));
        }
        if (mapping == null || mapping.isEmpty()) {
            // An explicit empty inputMapping/args is a zero-argument contract (e.g. a parameterless business
            // method). Only a node that declares neither keeps the legacy {input: lastOutput} fallback.
            if (config.containsKey("args") || config.get("inputMapping") instanceof Map<?, ?>) return Map.of();
            String input = firstText(text(context.get("lastOutput")), text(context.get("input")));
            return Map.of("input", input == null ? "" : input);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : mapping.entrySet()) {
            input.put(entry.getKey(), valueResolver.renderInputValue(entry.getValue(), context));
        }
        return input;
    }

    private String resolveQualifiedName(GraphSpec.Node node) {
        return com.enterprise.ai.agent.graph.GraphSpecToolContract.resolve(node);
    }

    private String configuredReference(Object rawReference) {
        Map<String, Object> reference = mapValue(rawReference);
        if (reference != null) {
            return firstText(
                    text(reference.get("qualifiedName")),
                    text(reference.get("name")),
                    reference.get("ref") instanceof Map<?, ?>
                            ? configuredReference(reference.get("ref")) : text(reference.get("ref")),
                    text(reference.get("toolName")));
        }
        return text(rawReference);
    }

    private RuntimeGraphSpecExecutionResult success(GraphSpec.Node node,
                                                    String nodeType,
                                                    String answer,
                                                    Map<String, Object> metadata,
                                                    String stepName) {
        return new RuntimeGraphSpecExecutionResult(
                true, "RUNTIME_GRAPH_EXECUTED", answer, node.getId(), nodeType,
                List.of(step(stepName, node.getId())), metadata);
    }

    private RuntimeGraphSpecExecutionResult cancelled(String nodeId, String nodeType) {
        return failure("RUNTIME_GRAPH_CANCELLED", "Workflow execution cancelled", nodeId, nodeType);
    }

    private RuntimeGraphSpecExecutionResult failure(String code, String answer, String nodeId, String nodeType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (StringUtils.hasText(nodeId)) metadata.put("nodeId", nodeId);
        if (StringUtils.hasText(nodeType)) metadata.put("nodeType", nodeType);
        return new RuntimeGraphSpecExecutionResult(false, code, answer, nodeId, nodeType, List.of(), metadata);
    }

    private RuntimeGraphSpecExecutionResult evalSideEffectBlocked(GraphSpec.Node node,
                                                                  String nodeType,
                                                                  String message,
                                                                  RuntimeEvalExecutionContext evaluation) {
        Map<String, Object> metadata = nodeMetadata(node, nodeType);
        metadata.put("evalMode", evaluation.mode().name());
        putIfPresent(metadata, "experimentId", evaluation.experimentId());
        putIfPresent(metadata, "itemId", evaluation.itemId());
        return new RuntimeGraphSpecExecutionResult(
                false, "EVAL_SIDE_EFFECT_BLOCKED", message, node.getId(), nodeType,
                List.of(step("eval-side-effect-blocked", node.getId())), metadata);
    }

    private Map<String, Object> nodeMetadata(GraphSpec.Node node, String nodeType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeId", node.getId());
        metadata.put("nodeType", nodeType);
        return metadata;
    }

    private Map<String, Object> step(String name, String detail) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("detail", detail);
        return step;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            String parsed = text(value);
            return parsed == null ? null : Long.parseLong(parsed);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // Use fallback.
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private Object firstPresent(Object first, Object fallback) {
        return first != null ? first : fallback;
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }

    private record CapabilityInvocationControl(String invocationId,
                                               String idempotencyKey,
                                               long deadlineEpochMs) {
    }
}

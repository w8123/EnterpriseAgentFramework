package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import com.enterprise.ai.common.response.BusinessResponseEnvelope;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.PageBridgeExecutionRequest;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.PageBridgeExecutionResponse;
import com.enterprise.ai.runtime.eval.RuntimeEvalExecutionContext;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionNodeHandler;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionType;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
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

    private final RuntimeCapabilityCatalogClient capabilityClient;
    private final RuntimeControlCatalogClient controlClient;
    private final RuntimeNodeValueResolver valueResolver;

    RuntimeActionNodeHandlers(RuntimeCapabilityCatalogClient capabilityClient,
                              RuntimeControlCatalogClient controlClient,
                              RuntimeNodeValueResolver valueResolver) {
        this.capabilityClient = capabilityClient;
        this.controlClient = controlClient;
        this.valueResolver = valueResolver;
    }

    RuntimeGraphSpecExecutionResult executeInteraction(GraphSpec.Node node,
                                                       RuntimeNodeExecutionContext execution) {
        Map<String, Object> context = execution.variables();
        RuntimeEvalExecutionContext evaluation = RuntimeTrustedExecutionContexts.evaluation(context);
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        WorkflowInteractionType interactionType = WorkflowInteractionType.from(config.get("interactionType"));
        WorkflowExecutionIdentity identity = RuntimeTrustedExecutionContexts.identity(context);
        if (identity.source() == WorkflowExecutionIdentity.Source.AUTOMATION && interactionType.blocking()) {
            return failure("AUTOMATION_INTERACTION_REQUIRED",
                    "Scheduled Workflow cannot wait for human interaction at node: " + node.getId(),
                    node.getId(), "INTERACTION");
        }
        if (identity.source() == WorkflowExecutionIdentity.Source.MCP_REMOTE_CLIENT
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
        Map<String, Object> args = buildToolInput(node, context);
        int executionTimeoutMs = intValue(
                context.get("pageBridgeTimeoutMs"), DEFAULT_PAGE_BRIDGE_EXECUTION_TIMEOUT_MS);
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
                    Boolean.TRUE.equals(config.get("confirm"))
                            || Boolean.TRUE.equals(config.get("confirmRequired")),
                    DEFAULT_PAGE_BRIDGE_CONFIRMATION_TIMEOUT_MS,
                    executionTimeoutMs));
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), "PAGE_ACTION");
            }
            Map<String, Object> metadata = nodeMetadata(node, "PAGE_ACTION");
            metadata.put("pageKey", targetPageKey);
            metadata.put("actionKey", actionKey);
            if (response != null) {
                putIfPresent(metadata, "pageBridgeCode", response.code());
                putIfPresent(metadata, "pageBridgeStatus", response.status());
                putIfPresent(metadata, "pageBridgePhases", response.phases());
                putIfPresent(metadata, "pageBridgeData", response.data());
                Map<String, Object> pageActionSummary = pageActionResultSummary(actionKey, response);
                metadata.put("pageActionResultSummary", pageActionSummary);
                // Trace stays useful without replaying business rows or raw bridge data.
                metadata.put("traceSummary", pageActionSummary);
                if (response.data() != null) {
                    metadata.put("structuredOutput", response.data());
                }
                if (isBusinessTerminalPageAction(response)) {
                    metadata.put("outcomeClass", "BUSINESS_TERMINAL");
                    metadata.put("businessOutcome", response.status());
                }
            }
            if (response == null || !response.success()) {
                return new RuntimeGraphSpecExecutionResult(
                        false,
                        response == null ? "RUNTIME_PAGE_ACTION_EMPTY" : response.code(),
                        response == null ? "Page Bridge returned no response" : response.status(),
                        node.getId(), "PAGE_ACTION",
                        List.of(step("execute-page-action", node.getId())), metadata);
            }
            if (isBusinessTerminalPageAction(response)) {
                return new RuntimeGraphSpecExecutionResult(
                        true, "RUNTIME_PAGE_ACTION_BUSINESS_TERMINAL", pageActionBusinessMessage(response),
                        node.getId(), "PAGE_ACTION",
                        List.of(step("execute-page-action", node.getId())), metadata);
            }
            return new RuntimeGraphSpecExecutionResult(
                    true, "RUNTIME_PAGE_ACTION_EXECUTED",
                    response.data() == null ? response.status() : String.valueOf(response.data()),
                    node.getId(), "PAGE_ACTION",
                    List.of(step("execute-page-action", node.getId())), metadata);
        } catch (Exception ex) {
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), "PAGE_ACTION");
            }
            return failure("RUNTIME_PAGE_ACTION_FAILED", "PAGE_ACTION failed: " + ex.getMessage(),
                    node.getId(), "PAGE_ACTION");
        }
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

    /** Keeps only action state, an aggregate count, and an optional short business message. */
    private Map<String, Object> pageActionResultSummary(String actionKey,
                                                        PageBridgeExecutionResponse response) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("actionKey", actionKey);
        summary.put("success", response.success());
        String status = firstText(response.status(), response.success() ? "SUCCESS" : "FAILED");
        summary.put("status", status);
        if (isBusinessTerminalPageAction(response)) {
            summary.put("outcomeClass", "BUSINESS_TERMINAL");
            summary.put("businessOutcome", status);
        }
        Long total = null;
        String message = null;
        for (Map<String, Object> payload : pageActionPayloadCandidates(mapValue(response.data()))) {
            if (!StringUtils.hasText(message)) message = safePageActionMessage(payload.get("message"));
            if (total == null) total = pageActionCount(payload);
        }
        if (StringUtils.hasText(message)) {
            summary.put("message", message);
        } else if (isBusinessTerminalPageAction(response)) {
            summary.put("message", pageActionBusinessMessage(response));
        }
        if (total != null) {
            summary.put("total", total);
            summary.put("empty", total == 0L);
        }
        if (pageActionPayloadCandidates(mapValue(response.data())).stream()
                .anyMatch(payload -> Boolean.TRUE.equals(payload.get("userConfirmed")))) {
            summary.put("userConfirmed", true);
        }
        return Map.copyOf(summary);
    }

    private boolean isBusinessTerminalPageAction(PageBridgeExecutionResponse response) {
        if (response == null || !response.success()) return false;
        if ("PAGE_BRIDGE_BUSINESS_TERMINAL".equalsIgnoreCase(response.code())) return true;
        String status = text(response.status());
        return "NO_DATA".equalsIgnoreCase(status)
                || "PRECONDITION_FAILED".equalsIgnoreCase(status)
                || "USER_CANCELLED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status);
    }

    private String pageActionBusinessMessage(PageBridgeExecutionResponse response) {
        for (Map<String, Object> payload : pageActionPayloadCandidates(
                mapValue(response == null ? null : response.data()))) {
            String message = safePageActionMessage(payload.get("message"));
            if (StringUtils.hasText(message)) return message;
        }
        String status = response == null ? null : text(response.status());
        if ("NO_DATA".equalsIgnoreCase(status)) return "未查询到符合条件的数据。";
        if ("PRECONDITION_FAILED".equalsIgnoreCase(status)) {
            return "当前业务状态不满足执行该页面操作的条件。";
        }
        if ("USER_CANCELLED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status)) {
            return "已取消本次页面操作。";
        }
        return "页面操作已结束，未产生可继续执行的业务结果。";
    }

    private List<Map<String, Object>> pageActionPayloadCandidates(Map<String, Object> root) {
        if (root == null || root.isEmpty()) return List.of();
        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.add(root);
        for (int index = 0; index < candidates.size() && index < 4; index++) {
            Map<String, Object> candidate = candidates.get(index);
            for (String nestedKey : List.of("data", "result", "payload")) {
                Map<String, Object> nested = mapValue(candidate.get(nestedKey));
                if (nested != null && !candidates.contains(nested)) candidates.add(nested);
            }
        }
        return candidates;
    }

    private Long pageActionCount(Map<String, Object> payload) {
        for (String countKey : List.of("total", "totalCount", "rowCount", "count")) {
            Long count = nonNegativeIntegralCount(payload.get(countKey));
            if (count != null) return count;
        }
        for (String collectionKey : List.of("records", "rows", "items", "list", "courses")) {
            Object value = payload.get(collectionKey);
            if (value instanceof Collection<?> collection) return (long) collection.size();
        }
        return null;
    }

    private Long nonNegativeIntegralCount(Object value) {
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            long integral = number.longValue();
            return Double.isFinite(decimal) && decimal == integral && integral >= 0 ? integral : null;
        }
        if (value instanceof CharSequence sequence) {
            String text = sequence.toString().trim();
            if (text.matches("\\d+")) {
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private String safePageActionMessage(Object value) {
        if (!(value instanceof CharSequence sequence)) return null;
        String message = sequence.toString().trim();
        if (!StringUtils.hasText(message)) return null;
        return message.length() <= 240 ? message : message.substring(0, 240);
    }

    private Map<String, Object> buildToolInput(GraphSpec.Node node, Map<String, Object> context) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> mapping = mapValue(config.get("inputMapping"));
        if (mapping == null || mapping.isEmpty()) {
            mapping = mapValue(config.get("args"));
        }
        if (mapping == null || mapping.isEmpty()) {
            // PAGE_ACTION supports an explicit zero-argument contract serialized as args={ }.
            if (config.containsKey("args")) return Map.of();
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
        if (node.getRef() != null) {
            String qualifiedName = firstText(
                    text(node.getRef().getQualifiedName()), text(node.getRef().getName()));
            if (StringUtils.hasText(qualifiedName)) return qualifiedName;
        }
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("toolConfig"));
        return firstText(
                text(config.get("qualifiedName")),
                configuredReference(config.get("ref")),
                text(config.get("toolName")),
                nested == null ? null : text(nested.get("qualifiedName")),
                nested == null ? null : configuredReference(nested.get("ref")),
                nested == null ? null : text(nested.get("toolName")));
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

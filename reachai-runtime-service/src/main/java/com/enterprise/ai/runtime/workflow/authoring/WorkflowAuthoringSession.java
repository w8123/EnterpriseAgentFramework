package com.enterprise.ai.runtime.workflow.authoring;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalEditOperationView;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService.MutationOperation;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService.MutationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-request in-memory candidate state. Never shared across authoring requests.
 */
final class WorkflowAuthoringSession {

    static final int MAX_MUTATION_ROUNDS = 5;

    private final String sessionId = UUID.randomUUID().toString();
    private final WorkflowAuthoringRequest request;
    private final ObjectMapper objectMapper;
    private final RuntimeWorkflowGraphMutationService mutationService;
    private final GraphSpec originalGraphSpec;
    private final String originalFingerprint;
    private GraphSpec candidateGraphSpec;
    private RuntimeWorkflowReleaseValidationResult lastValidation;
    private boolean lastValidationValid;
    private boolean finalized;
    private String summary = "";
    private final List<String> warnings = new ArrayList<>();
    private final List<Map<String, Object>> toolCallSummaries = new ArrayList<>();
    private int mutationRounds;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    WorkflowAuthoringSession(WorkflowAuthoringRequest request,
                             ObjectMapper objectMapper,
                             RuntimeWorkflowGraphMutationService mutationService) {
        this.request = request;
        this.objectMapper = objectMapper;
        this.mutationService = mutationService;
        GraphSpec source = request.currentGraphSpec() == null
                ? GraphSpec.builder().build()
                : request.currentGraphSpec();
        this.originalGraphSpec = deepCopy(source);
        this.originalFingerprint = fingerprint(this.originalGraphSpec);
        this.candidateGraphSpec = deepCopy(this.originalGraphSpec);
    }

    String sessionId() {
        return sessionId;
    }

    WorkflowAuthoringRequest request() {
        return request;
    }

    GraphSpec originalGraphSpec() {
        return deepCopy(originalGraphSpec);
    }

    GraphSpec candidateGraphSpec() {
        return deepCopy(candidateGraphSpec);
    }

    boolean originalUnmodified() {
        return originalFingerprint.equals(fingerprint(originalGraphSpec));
    }

    int mutationRounds() {
        return mutationRounds;
    }

    boolean finalized() {
        return finalized;
    }

    boolean lastValidationValid() {
        return lastValidationValid;
    }

    RuntimeWorkflowReleaseValidationResult lastValidation() {
        return lastValidation;
    }

    /**
     * Net operations from the immutable original GraphSpec to the current candidate.
     * Failed mutation rounds are excluded because they never change the candidate.
     */
    List<RuntimeWorkflowProposalEditOperationView> acceptedOperations() {
        return WorkflowAuthoringNetOperations.diff(objectMapper, originalGraphSpec, candidateGraphSpec);
    }

    List<String> warnings() {
        return List.copyOf(warnings);
    }

    List<Map<String, Object>> toolCallSummaries() {
        return List.copyOf(toolCallSummaries);
    }

    String summary() {
        return summary;
    }

    void setSummary(String value) {
        if (StringUtils.hasText(value)) {
            this.summary = value.trim();
        }
    }

    void close() {
        closed.set(true);
    }

    boolean closed() {
        return closed.get();
    }

    Map<String, Object> inspectContext() {
        ensureOpen();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("sessionId", sessionId);
        context.put("authoringId", sessionId);
        putIfHasText(context, "workflowId", request.workflowId());
        putIfHasText(context, "workflowName", request.workflowName());
        putIfHasText(context, "projectCode", request.projectCode());
        context.put("workflowKind", firstText(request.workflowKind(), "GENERAL"));
        putIfHasText(context, "modelInstanceId", request.modelInstanceId());
        context.put("selectedNodeIds", request.selectedNodeIds());
        context.put("selectedEdgeIds", request.selectedEdgeIds());
        context.put("currentGraphSpec", candidateGraphSpec());
        context.put("availableResources", request.availableResources());
        context.put("mutationRounds", mutationRounds);
        context.put("maxMutationRounds", MAX_MUTATION_ROUNDS);
        context.put("lastValidationValid", lastValidationValid);
        context.put("finalized", finalized);
        recordTool("inspect_workflow_context", true, null);
        return context;
    }

    Map<String, Object> applyOperations(List<MutationOperation> operations,
                                        List<RuntimeWorkflowProposalEditOperationView> operationViews) {
        ensureOpen();
        if (finalized) {
            return WorkflowAuthoringMutationErrors.failure(
                    "ALREADY_FINALIZED", "Candidate already finalized", null, false);
        }
        if (mutationRounds >= MAX_MUTATION_ROUNDS) {
            return WorkflowAuthoringMutationErrors.failure(
                    "MAX_MUTATION_ROUNDS_EXCEEDED",
                    "Candidate mutation limit reached (" + MAX_MUTATION_ROUNDS + ")",
                    null,
                    false);
        }
        if (operations == null || operations.isEmpty()) {
            return WorkflowAuthoringMutationErrors.failure(
                    "OPERATIONS_REQUIRED", "operations must not be empty", null, true);
        }
        try {
            MutationResult mutation = mutationService.mutate(candidateGraphSpec, operations);
            candidateGraphSpec = mutation.graphSpec();
            mutationRounds++;
            lastValidation = null;
            lastValidationValid = false;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("success", true);
            payload.put("mutationRound", mutationRounds);
            payload.put("operationCount", mutation.operationCount());
            payload.put("acceptedOperationViews", operationViews == null ? 0 : operationViews.size());
            payload.put("netOperationCount", acceptedOperations().size());
            payload.put("changedNodes", mutation.changedNodes());
            payload.put("changedEdges", mutation.changedEdges());
            payload.put("candidateNodeCount", candidateGraphSpec.getNodes() == null
                    ? 0 : candidateGraphSpec.getNodes().size());
            payload.put("candidateEdgeCount", candidateGraphSpec.getEdges() == null
                    ? 0 : candidateGraphSpec.getEdges().size());
            payload.put("entryNodeId", candidateGraphSpec.getEntryNodeId());
            payload.put("exitNodeIds", candidateGraphSpec.getExitNodeIds());
            recordTool("apply_candidate_operations", true, null);
            return payload;
        } catch (IllegalArgumentException ex) {
            Integer index = extractOperationIndex(ex.getMessage());
            Map<String, Object> failure = WorkflowAuthoringMutationErrors.fromException(ex, index);
            recordTool("apply_candidate_operations", false, String.valueOf(failure.get("code")));
            return failure;
        }
    }

    Map<String, Object> recordValidation(RuntimeWorkflowReleaseValidationResult validation) {
        ensureOpen();
        this.lastValidation = validation;
        this.lastValidationValid = validation != null && validation.valid();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("valid", lastValidationValid);
        payload.put("errors", validation == null ? List.of() : validation.errors().stream()
                .map(item -> {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("code", item.code());
                    error.put("message", item.message());
                    error.put("nodeId", item.nodeId());
                    return error;
                }).toList());
        payload.put("warnings", validation == null ? List.of() : validation.warnings().stream()
                .map(item -> {
                    Map<String, Object> warning = new LinkedHashMap<>();
                    warning.put("code", item.code());
                    warning.put("message", item.message());
                    warning.put("nodeId", item.nodeId());
                    return warning;
                }).toList());
        if (validation != null) {
            validation.warnings().stream()
                    .map(item -> item.code() + ": " + item.message())
                    .forEach(warnings::add);
        }
        recordTool("validate_candidate", lastValidationValid, lastValidationValid ? null : "VALIDATION_FAILED");
        return payload;
    }

    Map<String, Object> finalizePreview(String summaryText) {
        ensureOpen();
        if (mutationRounds <= 0) {
            Map<String, Object> failure = WorkflowAuthoringMutationErrors.failure(
                    "CANDIDATE_MISSING",
                    "No candidate mutation has been applied yet",
                    null,
                    true);
            recordTool("finalize_preview", false, "CANDIDATE_MISSING");
            return failure;
        }
        if (!lastValidationValid || lastValidation == null) {
            Map<String, Object> failure = WorkflowAuthoringMutationErrors.failure(
                    "VALIDATION_REQUIRED",
                    "validate_candidate must succeed before finalize_preview",
                    null,
                    true);
            recordTool("finalize_preview", false, "VALIDATION_REQUIRED");
            return failure;
        }
        if (!originalUnmodified()) {
            Map<String, Object> failure = WorkflowAuthoringMutationErrors.failure(
                    "ORIGINAL_TAMPERED",
                    "Original GraphSpec was modified unexpectedly",
                    null,
                    false);
            recordTool("finalize_preview", false, "ORIGINAL_TAMPERED");
            return failure;
        }
        setSummary(summaryText);
        finalized = true;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("finalized", true);
        payload.put("summary", summary());
        payload.put("operationCount", acceptedOperations().size());
        payload.put("mutationRounds", mutationRounds);
        recordTool("finalize_preview", true, null);
        return payload;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Authoring session already closed");
        }
    }

    void recordToolResult(String tool, boolean success, String code) {
        recordTool(tool, success, code);
    }

    private void recordTool(String tool, boolean success, String code) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tool", tool);
        item.put("success", success);
        if (StringUtils.hasText(code)) {
            item.put("code", code);
        }
        toolCallSummaries.add(item);
    }

    private Integer extractOperationIndex(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        int start = message.indexOf("operation[");
        if (start < 0) {
            return null;
        }
        int end = message.indexOf(']', start);
        if (end <= start) {
            return null;
        }
        try {
            return Integer.parseInt(message.substring(start + "operation[".length(), end));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private GraphSpec deepCopy(GraphSpec source) {
        try {
            return objectMapper.treeToValue(objectMapper.valueToTree(source), GraphSpec.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to copy GraphSpec", ex);
        }
    }

    private String fingerprint(GraphSpec graphSpec) {
        try {
            return objectMapper.writeValueAsString(graphSpec);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to fingerprint GraphSpec", ex);
        }
    }

    private void putIfHasText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }
}

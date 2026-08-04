package com.enterprise.ai.runtime.workflow.authoring;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValidationService;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalEditOperationType;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalEditOperationView;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService.MutationOperation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Constrained AgentScope tools for in-memory Workflow candidate authoring.
 */
final class WorkflowAuthoringToolkit {

    private static final Logger log = LoggerFactory.getLogger(WorkflowAuthoringToolkit.class);

    static final String INSPECT = "inspect_workflow_context";
    static final String APPLY = "apply_candidate_operations";
    static final String VALIDATE = "validate_candidate";
    static final String FINALIZE = "finalize_preview";
    private static final Set<String> OPERATION_FIELDS = Set.of(
            "op", "node", "nodeId", "patch", "edge", "edgeId", "entryNodeId", "exitNodeIds", "reason");
    private static final Set<String> EDGE_FIELDS = Set.of(
            "id", "from", "to", "condition", "sourceHandle", "targetHandle", "priority");

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final RuntimeWorkflowProposalValidationService validationService;

    WorkflowAuthoringToolkit(ObjectMapper objectMapper,
                             RuntimeWorkflowProposalValidationService validationService) {
        this.objectMapper = objectMapper;
        this.validationService = validationService;
    }

    Toolkit create(WorkflowAuthoringSession session) {
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder().parallel(false).build());
        toolkit.registerAgentTool(inspectTool(session));
        toolkit.registerAgentTool(applyTool(session));
        toolkit.registerAgentTool(validateTool(session));
        toolkit.registerAgentTool(finalizeTool(session));
        return toolkit;
    }

    private AgentTool inspectTool(WorkflowAuthoringSession session) {
        return new AgentTool() {
            @Override
            public String getName() {
                return INSPECT;
            }

            @Override
            public String getDescription() {
                return "Read the current Workflow authoring context, candidate GraphSpec, selections and available resources.";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(),
                        "additionalProperties", false);
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.fromCallable(() -> text(session.inspectContext()))
                        .subscribeOn(Schedulers.boundedElastic());
            }
        };
    }

    private AgentTool applyTool(WorkflowAuthoringSession session) {
        return new AgentTool() {
            @Override
            public String getName() {
                return APPLY;
            }

            @Override
            public String getDescription() {
                return "Atomically apply GraphSpec operations to the in-memory candidate. "
                        + "Each ADD_NODE must include node.id. Never writes the database.";
            }

            @Override
            public Map<String, Object> getParameters() {
                return applyParametersSchema();
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.fromCallable(() -> {
                    Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
                    Object rawOps = input.get("operations");
                    if (!(rawOps instanceof List<?> list) || list.isEmpty()) {
                        return text(WorkflowAuthoringMutationErrors.failure(
                                "OPERATIONS_REQUIRED", "operations must be a non-empty array", null, true));
                    }
                    List<MutationOperation> mutations = new ArrayList<>();
                    List<RuntimeWorkflowProposalEditOperationView> views = new ArrayList<>();
                    for (int index = 0; index < list.size(); index++) {
                        Object item = list.get(index);
                        try {
                            ParsedOperation parsed = parseOperation(item, index);
                            mutations.add(parsed.mutation());
                            views.add(parsed.view());
                        } catch (IllegalArgumentException ex) {
                            Map<String, Object> failure =
                                    WorkflowAuthoringMutationErrors.fromException(ex, index);
                            String code = String.valueOf(failure.get("code"));
                            session.recordToolResult(APPLY, false, code);
                            log.debug("Workflow authoring mutation rejected: authoringId={}, code={}, operationIndex={}",
                                    session.sessionId(), code, index);
                            return text(failure);
                        }
                    }
                    Map<String, Object> applied = session.applyOperations(mutations, views);
                    if (Boolean.FALSE.equals(applied.get("success"))) {
                        log.debug("Workflow authoring mutation failed: authoringId={}, code={}, mutationRounds={}",
                                session.sessionId(),
                                applied.get("code"),
                                session.mutationRounds());
                    }
                    return text(applied);
                }).subscribeOn(Schedulers.boundedElastic());
            }
        };
    }

    private AgentTool validateTool(WorkflowAuthoringSession session) {
        return new AgentTool() {
            @Override
            public String getName() {
                return VALIDATE;
            }

            @Override
            public String getDescription() {
                return "Run deterministic candidate/release validation on the current in-memory GraphSpec candidate.";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(),
                        "additionalProperties", false);
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.fromCallable(() -> {
                    WorkflowAuthoringRequest request = session.request();
                    RuntimeWorkflowReleaseValidationResult validation = validationService.validate(
                            request.workflowId(),
                            request.projectCode(),
                            request.workflowKind(),
                            request.modelInstanceId(),
                            session.candidateGraphSpec());
                    Map<String, Object> payload = session.recordValidation(validation);
                    if (!Boolean.TRUE.equals(payload.get("valid"))) {
                        List<String> codes = validation == null || validation.errors() == null
                                ? List.of()
                                : validation.errors().stream()
                                .map(RuntimeWorkflowReleaseValidationResult.Item::code)
                                .filter(StringUtils::hasText)
                                .toList();
                        log.debug("Workflow authoring validation retry needed: authoringId={}, errorCodes={}",
                                session.sessionId(), codes);
                    }
                    return text(payload);
                }).subscribeOn(Schedulers.boundedElastic());
            }
        };
    }

    private AgentTool finalizeTool(WorkflowAuthoringSession session) {
        return new AgentTool() {
            @Override
            public String getName() {
                return FINALIZE;
            }

            @Override
            public String getDescription() {
                return "Finalize a preview only after validate_candidate returned valid=true. "
                        + "Does not save, publish or execute the Workflow.";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "summary", Map.of(
                                        "type", "string",
                                        "description", "Short Chinese summary of the authored Workflow")),
                        "required", List.of("summary"),
                        "additionalProperties", false);
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.fromCallable(() -> {
                    Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
                    String summary = textValue(input.get("summary"));
                    return text(session.finalizePreview(summary));
                }).subscribeOn(Schedulers.boundedElastic());
            }
        };
    }

    private Map<String, Object> applyParametersSchema() {
        // Keep schema guidance strict in descriptions, but do not hard-require node.id here.
        // AgentScope may reject tool calls before invocation when required fields are missing;
        // server-side validation still returns structured retryable errors for the agent loop.
        Map<String, Object> nodeSchema = new LinkedHashMap<>();
        nodeSchema.put("type", "object");
        Map<String, Object> nodeProperties = new LinkedHashMap<>();
        nodeProperties.put("id", Map.of(
                "type", "string",
                "minLength", 1,
                "description", "Required stable unique node id"));
        nodeProperties.put("type", Map.of("type", "string"));
        nodeProperties.put("name", Map.of("type", "string"));
        nodeProperties.put("description", Map.of("type", "string"));
        nodeProperties.put("ref", Map.of("type", "object"));
        nodeProperties.put("inputs", Map.of("type", "array"));
        nodeProperties.put("outputs", Map.of("type", "array"));
        nodeProperties.put("inputSchema", Map.of("type", "object"));
        nodeProperties.put("outputSchema", Map.of("type", "object"));
        nodeProperties.put("retry", Map.of("type", "object"));
        nodeProperties.put("errorPolicy", Map.of("type", "object"));
        nodeProperties.put("config", Map.of("type", "object"));
        nodeSchema.put("properties", nodeProperties);
        nodeSchema.put("required", List.of("type"));
        nodeSchema.put("additionalProperties", false);

        Map<String, Object> edgeSchema = new LinkedHashMap<>();
        edgeSchema.put("type", "object");
        edgeSchema.put("properties", Map.of(
                "id", Map.of("type", "string"),
                "from", Map.of("type", "string", "minLength", 1),
                "to", Map.of("type", "string", "minLength", 1),
                "condition", Map.of("type", "string"),
                "sourceHandle", Map.of("type", "string"),
                "targetHandle", Map.of("type", "string")));
        edgeSchema.put("required", List.of("from", "to"));
        edgeSchema.put("additionalProperties", false);

        Map<String, Object> operationSchema = new LinkedHashMap<>();
        operationSchema.put("type", "object");
        operationSchema.put("properties", Map.of(
                "op", Map.of(
                        "type", "string",
                        "enum", List.of(
                                "ADD_NODE", "UPDATE_NODE", "DELETE_NODE",
                                "ADD_EDGE", "UPDATE_EDGE", "DELETE_EDGE",
                                "SET_ENTRY_NODE", "SET_EXIT_NODES", "SET_INPUT_SCHEMA")),
                "node", nodeSchema,
                "nodeId", Map.of("type", "string"),
                "edge", edgeSchema,
                "edgeId", Map.of("type", "string"),
                "patch", Map.of("type", "object"),
                "entryNodeId", Map.of("type", "string"),
                "exitNodeIds", Map.of("type", "array", "items", Map.of("type", "string")),
                "reason", Map.of("type", "string")));
        operationSchema.put("required", List.of("op"));
        operationSchema.put("additionalProperties", false);

        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "operations", Map.of(
                                "type", "array",
                                "minItems", 1,
                                "items", operationSchema)),
                "required", List.of("operations"),
                "additionalProperties", false);
    }

    private ParsedOperation parseOperation(Object raw, int index) {
        Map<String, Object> map = mutableMap(raw);
        assertAllowedFields(map, OPERATION_FIELDS, "operation[" + index + "]");
        String opText = textValue(map.get("op"));
        if (!StringUtils.hasText(opText)) {
            throw new IllegalArgumentException("operation[" + index + "].op is required");
        }
        MutationOperation.Op op;
        try {
            op = MutationOperation.Op.valueOf(opText.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("operation[" + index + "] has unknown op: " + opText);
        }
        RuntimeWorkflowProposalEditOperationType viewType = toViewType(op);
        String reason = textValue(map.get("reason"));
        return switch (op) {
            case ADD_NODE -> {
                Map<String, Object> nodeMap = mutableMap(map.get("node"));
                if (nodeMap.isEmpty()) {
                    throw new IllegalArgumentException("ADD_NODE requires node");
                }
                if (!StringUtils.hasText(textValue(nodeMap.get("id")))) {
                    throw new IllegalArgumentException("ADD_NODE requires node.id");
                }
                GraphSpec.Node node = toNode(nodeMap);
                yield new ParsedOperation(
                        new MutationOperation(op, node, null, null, null, null, null, null),
                        RuntimeWorkflowProposalEditOperationView.builder()
                                .type(viewType)
                                .node(nodeMap)
                                .reason(reason)
                                .build());
            }
            case UPDATE_NODE -> {
                String nodeId = textValue(map.get("nodeId"));
                if (!StringUtils.hasText(nodeId)) {
                    throw new IllegalArgumentException("UPDATE_NODE requires nodeId");
                }
                Map<String, Object> patch = mutableMap(map.get("patch"));
                if (patch.isEmpty()) {
                    throw new IllegalArgumentException("UPDATE_NODE requires patch");
                }
                yield new ParsedOperation(
                        new MutationOperation(op, null, nodeId, patch, null, null, null, null),
                        RuntimeWorkflowProposalEditOperationView.builder()
                                .type(viewType)
                                .nodeId(nodeId)
                                .patch(patch)
                                .reason(reason)
                                .build());
            }
            case DELETE_NODE -> {
                String nodeId = textValue(map.get("nodeId"));
                if (!StringUtils.hasText(nodeId)) {
                    throw new IllegalArgumentException("DELETE_NODE requires nodeId");
                }
                yield new ParsedOperation(
                        new MutationOperation(op, null, nodeId, null, null, null, null, null),
                        RuntimeWorkflowProposalEditOperationView.builder()
                                .type(viewType)
                                .nodeId(nodeId)
                                .reason(reason)
                                .build());
            }
            case ADD_EDGE -> {
                Map<String, Object> edgeMap = mutableMap(map.get("edge"));
                if (edgeMap.isEmpty()) {
                    throw new IllegalArgumentException("ADD_EDGE requires edge");
                }
                assertAllowedFields(edgeMap, EDGE_FIELDS, "ADD_EDGE.edge");
                String from = textValue(edgeMap.get("from"));
                String to = textValue(edgeMap.get("to"));
                if (!StringUtils.hasText(from) || !StringUtils.hasText(to)) {
                    throw new IllegalArgumentException("ADD_EDGE requires edge.from and edge.to");
                }
                GraphSpec.Edge edge = objectMapper.convertValue(edgeMap, GraphSpec.Edge.class);
                edge.setCondition(firstText(edge.getCondition(), "always"));
                yield new ParsedOperation(
                        new MutationOperation(op, null, null, null, edge, null, null, null),
                        RuntimeWorkflowProposalEditOperationView.builder()
                                .type(viewType)
                                .edge(edgeMap)
                                .reason(reason)
                                .build());
            }
            case UPDATE_EDGE -> {
                String edgeId = textValue(map.get("edgeId"));
                if (!StringUtils.hasText(edgeId)) {
                    throw new IllegalArgumentException("UPDATE_EDGE requires edgeId");
                }
                Map<String, Object> patch = mutableMap(map.get("patch"));
                yield new ParsedOperation(
                        new MutationOperation(op, null, null, patch, null, edgeId, null, null),
                        RuntimeWorkflowProposalEditOperationView.builder()
                                .type(viewType)
                                .edgeId(edgeId)
                                .patch(patch)
                                .reason(reason)
                                .build());
            }
            case DELETE_EDGE -> {
                String edgeId = textValue(map.get("edgeId"));
                if (!StringUtils.hasText(edgeId)) {
                    throw new IllegalArgumentException("DELETE_EDGE requires edgeId");
                }
                yield new ParsedOperation(
                        new MutationOperation(op, null, null, null, null, edgeId, null, null),
                        RuntimeWorkflowProposalEditOperationView.builder()
                                .type(viewType)
                                .edgeId(edgeId)
                                .reason(reason)
                                .build());
            }
            case SET_ENTRY_NODE -> {
                String entryNodeId = textValue(map.get("entryNodeId"));
                if (!StringUtils.hasText(entryNodeId)) {
                    throw new IllegalArgumentException("SET_ENTRY_NODE requires entryNodeId");
                }
                yield new ParsedOperation(
                        new MutationOperation(op, null, null, null, null, null, entryNodeId, null),
                        RuntimeWorkflowProposalEditOperationView.builder()
                                .type(viewType)
                                .patch(Map.of("entryNodeId", entryNodeId))
                                .reason(reason)
                                .build());
            }
            case SET_EXIT_NODES -> {
                List<String> exitNodeIds = new ArrayList<>();
                Object rawExitNodeIds = map.get("exitNodeIds");
                if (rawExitNodeIds instanceof List<?> items) {
                    for (Object item : items) {
                        if (StringUtils.hasText(textValue(item))) {
                            exitNodeIds.add(textValue(item));
                        }
                    }
                }
                yield new ParsedOperation(
                        new MutationOperation(op, null, null, null, null, null, null, exitNodeIds),
                        RuntimeWorkflowProposalEditOperationView.builder()
                                .type(viewType)
                                .patch(Map.of("exitNodeIds", exitNodeIds))
                                .reason(reason)
                                .build());
            }
            case SET_INPUT_SCHEMA -> {
                Map<String, Object> inputSchema = mutableMap(map.get("patch"));
                if (inputSchema.isEmpty()) {
                    throw new IllegalArgumentException(
                            "SET_INPUT_SCHEMA requires a non-empty JSON Schema object in patch");
                }
                yield new ParsedOperation(
                        new MutationOperation(op, null, null, inputSchema, null, null, null, null),
                        RuntimeWorkflowProposalEditOperationView.builder()
                                .type(viewType)
                                .patch(inputSchema)
                                .reason(reason)
                                .build());
            }
        };
    }

    private GraphSpec.Node toNode(Map<String, Object> nodeMap) {
        return objectMapper.convertValue(nodeMap, GraphSpec.Node.class);
    }

    private void assertAllowedFields(Map<String, Object> values, Set<String> allowedFields, String context) {
        for (String field : values.keySet()) {
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException(context + " contains unsupported field: " + field);
            }
        }
    }

    private RuntimeWorkflowProposalEditOperationType toViewType(MutationOperation.Op op) {
        return switch (op) {
            case ADD_NODE -> RuntimeWorkflowProposalEditOperationType.ADD_NODE;
            case UPDATE_NODE -> RuntimeWorkflowProposalEditOperationType.UPDATE_NODE;
            case DELETE_NODE -> RuntimeWorkflowProposalEditOperationType.DELETE_NODE;
            case ADD_EDGE -> RuntimeWorkflowProposalEditOperationType.ADD_EDGE;
            case UPDATE_EDGE -> RuntimeWorkflowProposalEditOperationType.UPDATE_EDGE;
            case DELETE_EDGE -> RuntimeWorkflowProposalEditOperationType.DELETE_EDGE;
            case SET_ENTRY_NODE -> RuntimeWorkflowProposalEditOperationType.SET_ENTRY_NODE;
            case SET_EXIT_NODES -> RuntimeWorkflowProposalEditOperationType.SET_EXIT_NODES;
            case SET_INPUT_SCHEMA -> RuntimeWorkflowProposalEditOperationType.SET_INPUT_SCHEMA;
        };
    }

    private ToolResultBlock text(Object payload) {
        try {
            return ToolResultBlock.text(objectMapper.writeValueAsString(payload));
        } catch (Exception ex) {
            log.warn("Workflow authoring tool result serialization failed", ex);
            return ToolResultBlock.error("Failed to serialize tool result");
        }
    }

    private Map<String, Object> mutableMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (value instanceof Map<?, ?>) {
            return objectMapper.convertValue(value, MAP_TYPE);
        }
        return objectMapper.convertValue(value, MAP_TYPE);
    }

    private String textValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private record ParsedOperation(MutationOperation mutation, RuntimeWorkflowProposalEditOperationView view) {
    }
}

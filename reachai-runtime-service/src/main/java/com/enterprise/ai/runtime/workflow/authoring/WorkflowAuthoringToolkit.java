package com.enterprise.ai.runtime.workflow.authoring;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.draft.RuntimeWorkflowDraftCandidateValidationService;
import com.enterprise.ai.runtime.workflow.draft.RuntimeWorkflowDraftEditOperationType;
import com.enterprise.ai.runtime.workflow.draft.RuntimeWorkflowDraftEditOperationView;
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

/**
 * Constrained AgentScope tools for in-memory Workflow candidate authoring.
 */
final class WorkflowAuthoringToolkit {

    private static final Logger log = LoggerFactory.getLogger(WorkflowAuthoringToolkit.class);

    static final String INSPECT = "inspect_workflow_context";
    static final String APPLY = "apply_candidate_operations";
    static final String VALIDATE = "validate_candidate";
    static final String FINALIZE = "finalize_preview";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final RuntimeWorkflowDraftCandidateValidationService validationService;

    WorkflowAuthoringToolkit(ObjectMapper objectMapper,
                             RuntimeWorkflowDraftCandidateValidationService validationService) {
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
                    List<RuntimeWorkflowDraftEditOperationView> views = new ArrayList<>();
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
                            request.workflowType(),
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
        nodeSchema.put("properties", Map.of(
                "id", Map.of(
                        "type", "string",
                        "minLength", 1,
                        "description", "Required stable unique node id"),
                "type", Map.of("type", "string"),
                "name", Map.of("type", "string"),
                "description", Map.of("type", "string"),
                "config", Map.of("type", "object")));
        nodeSchema.put("required", List.of("type"));
        nodeSchema.put("additionalProperties", true);

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
        edgeSchema.put("additionalProperties", true);

        Map<String, Object> operationSchema = new LinkedHashMap<>();
        operationSchema.put("type", "object");
        operationSchema.put("properties", Map.of(
                "op", Map.of(
                        "type", "string",
                        "enum", List.of(
                                "ADD_NODE", "UPDATE_NODE", "DELETE_NODE",
                                "ADD_EDGE", "UPDATE_EDGE", "DELETE_EDGE",
                                "SET_ENTRY", "SET_FINISH")),
                "node", nodeSchema,
                "nodeId", Map.of("type", "string"),
                "edge", edgeSchema,
                "edgeId", Map.of("type", "string"),
                "patch", Map.of("type", "object"),
                "entry", Map.of("type", "string"),
                "finish", Map.of("type", "array", "items", Map.of("type", "string")),
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
        String opText = firstText(textValue(map.get("op")), textValue(map.get("type")));
        if (!StringUtils.hasText(opText)) {
            throw new IllegalArgumentException("operation[" + index + "].op is required");
        }
        MutationOperation.Op op;
        try {
            op = MutationOperation.Op.valueOf(opText.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("operation[" + index + "] has unknown op: " + opText);
        }
        RuntimeWorkflowDraftEditOperationType viewType = toViewType(op);
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
                        RuntimeWorkflowDraftEditOperationView.builder()
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
                    patch = mutableMap(map.get("node"));
                }
                yield new ParsedOperation(
                        new MutationOperation(op, null, nodeId, patch, null, null, null, null),
                        RuntimeWorkflowDraftEditOperationView.builder()
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
                        RuntimeWorkflowDraftEditOperationView.builder()
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
                String from = firstText(textValue(edgeMap.get("from")), textValue(edgeMap.get("source")));
                String to = firstText(textValue(edgeMap.get("to")), textValue(edgeMap.get("target")));
                if (!StringUtils.hasText(from) || !StringUtils.hasText(to)) {
                    throw new IllegalArgumentException("ADD_EDGE requires edge.from and edge.to");
                }
                GraphSpec.Edge edge = GraphSpec.Edge.builder()
                        .id(textValue(edgeMap.get("id")))
                        .from(from)
                        .to(to)
                        .condition(firstText(textValue(edgeMap.get("condition")), "always"))
                        .sourceHandle(textValue(edgeMap.get("sourceHandle")))
                        .targetHandle(textValue(edgeMap.get("targetHandle")))
                        .build();
                yield new ParsedOperation(
                        new MutationOperation(op, null, null, null, edge, null, null, null),
                        RuntimeWorkflowDraftEditOperationView.builder()
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
                        RuntimeWorkflowDraftEditOperationView.builder()
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
                        RuntimeWorkflowDraftEditOperationView.builder()
                                .type(viewType)
                                .edgeId(edgeId)
                                .reason(reason)
                                .build());
            }
            case SET_ENTRY -> {
                String entry = textValue(map.get("entry"));
                if (!StringUtils.hasText(entry)) {
                    throw new IllegalArgumentException("SET_ENTRY requires entry");
                }
                yield new ParsedOperation(
                        new MutationOperation(op, null, null, null, null, null, entry, null),
                        RuntimeWorkflowDraftEditOperationView.builder()
                                .type(viewType)
                                .patch(Map.of("entry", entry))
                                .reason(reason)
                                .build());
            }
            case SET_FINISH -> {
                List<String> finish = new ArrayList<>();
                Object rawFinish = map.get("finish");
                if (rawFinish instanceof List<?> items) {
                    for (Object item : items) {
                        if (StringUtils.hasText(textValue(item))) {
                            finish.add(textValue(item));
                        }
                    }
                }
                yield new ParsedOperation(
                        new MutationOperation(op, null, null, null, null, null, null, finish),
                        RuntimeWorkflowDraftEditOperationView.builder()
                                .type(viewType)
                                .patch(Map.of("finish", finish))
                                .reason(reason)
                                .build());
            }
        };
    }

    private GraphSpec.Node toNode(Map<String, Object> nodeMap) {
        GraphSpec.Node node = objectMapper.convertValue(nodeMap, GraphSpec.Node.class);
        if (StringUtils.hasText(node.getType())) {
            node.setType(AgentGraphNodeType.normalize(node.getType()));
        }
        return node;
    }

    private RuntimeWorkflowDraftEditOperationType toViewType(MutationOperation.Op op) {
        return switch (op) {
            case ADD_NODE -> RuntimeWorkflowDraftEditOperationType.ADD_NODE;
            case UPDATE_NODE -> RuntimeWorkflowDraftEditOperationType.UPDATE_NODE;
            case DELETE_NODE -> RuntimeWorkflowDraftEditOperationType.DELETE_NODE;
            case ADD_EDGE -> RuntimeWorkflowDraftEditOperationType.ADD_EDGE;
            case UPDATE_EDGE -> RuntimeWorkflowDraftEditOperationType.UPDATE_EDGE;
            case DELETE_EDGE -> RuntimeWorkflowDraftEditOperationType.DELETE_EDGE;
            case SET_ENTRY -> RuntimeWorkflowDraftEditOperationType.SET_ENTRY;
            case SET_FINISH -> RuntimeWorkflowDraftEditOperationType.SET_FINISH;
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

    private record ParsedOperation(MutationOperation mutation, RuntimeWorkflowDraftEditOperationView view) {
    }
}

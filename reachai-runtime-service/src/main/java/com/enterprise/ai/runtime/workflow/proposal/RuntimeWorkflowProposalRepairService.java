package com.enterprise.ai.runtime.workflow.proposal;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest.ChatMessage;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService.MutationOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded proposal-only repair loop used by the proposal-generation entry.
 *
 * <p>This is not an independent "repair model". It reuses the same selected
 * {@code modelInstanceId} for direct repair rounds after deterministic validation.
 * Workflow Studio proposal editing does not use this loop; that path is owned by
 * AgentScope Workflow Authoring tools and limited re-planning.</p>
 *
 * <p>It never persists a workflow and never executes runtime side effects.</p>
 */
@Service
@RequiredArgsConstructor
public class RuntimeWorkflowProposalRepairService {

    public static final int DEFAULT_MAX_REPAIR_ROUNDS = 2;
    private static final Set<String> REPAIR_RESPONSE_FIELDS = Set.of("summary", "operations");
    private static final Set<String> REPAIR_OPERATION_FIELDS = Set.of(
            "op", "node", "nodeId", "patch", "edge", "edgeId", "entryNodeId", "exitNodeIds");

    private final ObjectMapper objectMapper;
    private final RuntimeModelServiceClient modelServiceClient;
    private final RuntimeWorkflowGraphMutationService graphMutationService;
    private final RuntimeWorkflowProposalValidationService candidateValidationService;

    public RepairResult repair(RepairRequest request,
                               GraphSpec initialGraph,
                               RuntimeWorkflowReleaseValidationResult initialValidation) {
        GraphSpec candidate = initialGraph;
        RuntimeWorkflowReleaseValidationResult validation = initialValidation;
        List<String> warnings = new ArrayList<>();
        int maxRounds = request == null || request.maxRounds() == null
                ? DEFAULT_MAX_REPAIR_ROUNDS
                : Math.max(0, Math.min(request.maxRounds(), DEFAULT_MAX_REPAIR_ROUNDS));
        if (validation == null || validation.valid() || maxRounds == 0
                || request == null || !StringUtils.hasText(request.modelInstanceId())) {
            return new RepairResult(candidate, validation, 0, warnings);
        }

        int completedRounds = 0;
        for (int round = 1; round <= maxRounds && !validation.valid(); round++) {
            try {
                List<MutationOperation> operations = requestRepairOperations(request, candidate, validation, round);
                if (operations.isEmpty()) {
                    warnings.add("AI repair round " + round + " returned no operations");
                    break;
                }
                candidate = graphMutationService.mutate(candidate, operations).graphSpec();
                validation = candidateValidationService.validate(
                        request.workflowId(),
                        request.projectCode(),
                        request.workflowKind(),
                        request.modelInstanceId(),
                        candidate);
                completedRounds = round;
            } catch (Exception ex) {
                warnings.add("AI repair round " + round + " stopped: " + ex.getMessage());
                break;
            }
        }
        if (completedRounds > 0) {
            warnings.add(validation.valid()
                    ? "AI repaired and revalidated the Workflow proposal in " + completedRounds + " round(s)"
                    : "AI repair reached the bounded limit; unresolved validation errors remain");
        }
        return new RepairResult(candidate, validation, completedRounds, warnings);
    }

    private List<MutationOperation> requestRepairOperations(
            RepairRequest request,
            GraphSpec candidate,
            RuntimeWorkflowReleaseValidationResult validation,
            int round) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("originalInstruction", request.instruction());
        payload.put("repairRound", round);
        payload.put("maxRepairRounds", request.maxRounds());
        payload.put("selectedNodeIds", request.selectedNodeIds() == null ? List.of() : request.selectedNodeIds());
        payload.put("selectedEdgeIds", request.selectedEdgeIds() == null ? List.of() : request.selectedEdgeIds());
        payload.put("graphSpec", candidate);
        payload.put("validationErrors", validation.errors());
        payload.put("availableResources", request.availableResources() == null ? Map.of() : request.availableResources());
        ModelChatRequest modelRequest = ModelChatRequest.builder()
                .modelInstanceId(request.modelInstanceId().trim())
                .messages(List.of(
                        ChatMessage.builder().role("system").content(repairSystemPrompt()).build(),
                        ChatMessage.builder().role("user")
                                .content(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload))
                                .build()))
                .build();
        ModelChatResult result = modelServiceClient.chat(modelRequest);
        String raw = result == null || result.getData() == null ? null : result.getData().getContent();
        JsonNode root = objectMapper.readTree(extractJsonObject(raw));
        validateRepairResponse(root);
        JsonNode operations = root.get("operations");
        List<MutationOperation> parsed = new ArrayList<>();
        for (JsonNode operation : operations) {
            parsed.add(objectMapper.treeToValue(operation, MutationOperation.class));
        }
        return parsed;
    }

    private void validateRepairResponse(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("repair response must be a JSON object");
        }
        assertAllowedFields(root, REPAIR_RESPONSE_FIELDS, "repair response");
        JsonNode operations = root.get("operations");
        if (operations == null || !operations.isArray()) {
            throw new IllegalArgumentException("repair response.operations must be an array");
        }
        for (int index = 0; index < operations.size(); index++) {
            JsonNode operation = operations.get(index);
            String context = "repair response.operations[" + index + "]";
            if (operation == null || !operation.isObject()) {
                throw new IllegalArgumentException(context + " must be an object");
            }
            assertAllowedFields(operation, REPAIR_OPERATION_FIELDS, context);
            JsonNode op = operation.get("op");
            if (op == null || !op.isTextual() || !StringUtils.hasText(op.asText())) {
                throw new IllegalArgumentException(context + ".op is required");
            }
            try {
                MutationOperation.Op.valueOf(op.asText());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(context + ".op is not canonical: " + op.asText());
            }
        }
    }

    private void assertAllowedFields(JsonNode object, Set<String> allowedFields, String context) {
        object.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException(context + " contains unsupported field: " + field);
            }
        });
    }

    private String repairSystemPrompt() {
        return """
                You repair a ReachAI Workflow GraphSpec after deterministic validation. Return only strict JSON.
                Schema:
                {"summary":"short summary","operations":[{"op":"ADD_NODE|UPDATE_NODE|DELETE_NODE|ADD_EDGE|UPDATE_EDGE|DELETE_EDGE|SET_ENTRY_NODE|SET_EXIT_NODES","node":{},"nodeId":"optional","patch":{},"edge":{},"edgeId":"optional","entryNodeId":"optional","exitNodeIds":[]}]}
                Make the smallest safe change that resolves the supplied validation errors.
                GraphSpec is semantic truth. It never contains START/END nodes or boundary edges; use SET_ENTRY_NODE and SET_EXIT_NODES for boundaries.
                Never change an existing node or edge id. Put runtime node settings under config.
                Do not remove unrelated user work and do not invent unavailable model, tool, capability, knowledge, or page-action ids.
                """;
    }

    private String extractJsonObject(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("empty model response");
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("missing JSON object");
        }
        return text.substring(start, end + 1);
    }

    public record RepairRequest(String workflowId,
                                String projectCode,
                                String workflowKind,
                                String modelInstanceId,
                                String instruction,
                                List<String> selectedNodeIds,
                                List<String> selectedEdgeIds,
                                Map<String, Object> availableResources,
                                Integer maxRounds) {
    }

    public record RepairResult(GraphSpec graphSpec,
                               RuntimeWorkflowReleaseValidationResult validation,
                               int completedRounds,
                               List<String> warnings) {
        public RepairResult {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}

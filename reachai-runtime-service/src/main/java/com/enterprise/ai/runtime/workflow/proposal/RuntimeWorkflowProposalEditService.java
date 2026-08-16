package com.enterprise.ai.runtime.workflow.proposal;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDocumentCanonicalizer;
import com.enterprise.ai.runtime.workflow.authoring.WorkflowAuthoringAgentAdapter;
import com.enterprise.ai.runtime.workflow.authoring.WorkflowAuthoringRequest;
import com.enterprise.ai.runtime.workflow.authoring.WorkflowAuthoringResult;
import com.enterprise.ai.runtime.workflow.layout.RuntimeWorkflowCanvasLayoutService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Workflow Studio proposal-editing application entry.
 *
 * <p>Parses the current working-copy context, delegates reasoning/tool-use to
 * {@link WorkflowAuthoringAgentAdapter}, then projects a canvas snapshot for preview.</p>
 */
@Service
@RequiredArgsConstructor
public class RuntimeWorkflowProposalEditService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final RuntimeWorkflowCanvasLayoutService canvasLayoutService;
    private final WorkflowAuthoringAgentAdapter authoringAgentAdapter;
    private final RuntimeWorkflowDocumentCanonicalizer documentCanonicalizer;

    public RuntimeWorkflowProposalEditView edit(RuntimeWorkflowProposalEditRequest request) {
        Map<String, Object> canvas = mutableCanvas(request == null ? null : request.currentCanvas());
        if (request == null || request.currentGraphSpec() == null) {
            return failedView(
                    "currentGraphSpec is required",
                    "CURRENT_GRAPH_SPEC_REQUIRED",
                    canvas,
                    GraphSpec.builder().build(),
                    List.of("currentGraphSpec is required"),
                    0,
                    UUID.randomUUID().toString());
        }

        GraphSpec originalGraphSpec;
        try {
            originalGraphSpec = documentCanonicalizer.canonicalizeGraphSpec(deepCopy(request.currentGraphSpec()));
        } catch (IllegalArgumentException ex) {
            return failedView(
                    ex.getMessage(),
                    "CURRENT_GRAPH_SPEC_INVALID",
                    canvas,
                    GraphSpec.builder().build(),
                    List.of(ex.getMessage()),
                    0,
                    UUID.randomUUID().toString());
        }
        String originalFingerprint = fingerprint(originalGraphSpec);

        if (request == null || !StringUtils.hasText(request.instruction())) {
            return failedView(
                    "instruction is required",
                    "INSTRUCTION_REQUIRED",
                    canvas,
                    originalGraphSpec,
                    List.of("instruction is required"),
                    0,
                    UUID.randomUUID().toString());
        }
        if (!StringUtils.hasText(request.modelInstanceId())) {
            return failedView(
                    "modelInstanceId is required",
                    "MODEL_INSTANCE_REQUIRED",
                    canvas,
                    originalGraphSpec,
                    List.of("modelInstanceId is required"),
                    0,
                    UUID.randomUUID().toString());
        }

        WorkflowAuthoringResult authored = authoringAgentAdapter.author(new WorkflowAuthoringRequest(
                request.workflowId(),
                firstText(request.workflowName(), "Workflow"),
                request.projectCode(),
                request.workflowKind(),
                request.instruction(),
                request.modelInstanceId(),
                deepCopy(originalGraphSpec),
                request.selectedNodeIds(),
                request.selectedEdgeIds(),
                proposalResources(request)));

        if (!originalFingerprint.equals(fingerprint(originalGraphSpec))) {
            return failedView(
                    "原始 GraphSpec 被意外修改，已拒绝返回预览",
                    "ORIGINAL_GRAPH_TAMPERED",
                    canvas,
                    originalGraphSpec,
                    List.of("ORIGINAL_GRAPH_TAMPERED: original GraphSpec was modified in place"),
                    authored == null ? 0 : authored.attempts(),
                    authored == null ? null : authored.authoringId());
        }

        GraphSpec candidate = authored.graphSpec() == null ? originalGraphSpec : authored.graphSpec();
        Map<String, Object> projected = canvasLayoutService.projectAndLayout(
                candidate,
                canvas,
                RuntimeWorkflowCanvasLayoutService.Options.defaults());
        List<String> warnings = new ArrayList<>(authored.warnings());
        warnings.addAll(buildWarnings(projected));

        String status = authored.succeeded() && authored.validationErrors().isEmpty()
                ? "SUCCEEDED"
                : "FAILED";
        return new RuntimeWorkflowProposalEditView(
                status,
                authored.provider(),
                authored.summary(),
                authored.operations(),
                projected,
                candidate,
                warnings,
                placeholderNodes(projected),
                authored.validationErrors(),
                authored.attempts(),
                authored.failureCode(),
                authored.authoringId());
    }

    private RuntimeWorkflowProposalEditView failedView(String summary,
                                                    String failureCode,
                                                    Map<String, Object> canvas,
                                                    GraphSpec graphSpec,
                                                    List<String> validationErrors,
                                                    int attempts,
                                                    String authoringId) {
        Map<String, Object> projected = canvasLayoutService.projectAndLayout(
                graphSpec,
                canvas,
                RuntimeWorkflowCanvasLayoutService.Options.defaults());
        return new RuntimeWorkflowProposalEditView(
                "FAILED",
                WorkflowAuthoringResult.PROVIDER,
                summary,
                List.of(),
                projected,
                graphSpec,
                buildWarnings(projected),
                placeholderNodes(projected),
                validationErrors,
                attempts,
                failureCode,
                authoringId);
    }

    private Map<String, Object> proposalResources(RuntimeWorkflowProposalEditRequest request) {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("tools", request.tools() == null ? List.of() : request.tools());
        resources.put("capabilities", request.capabilities() == null ? List.of() : request.capabilities());
        resources.put("knowledgeBases", request.knowledgeBases() == null ? List.of() : request.knowledgeBases());
        return resources;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableCanvas(Map<String, Object> rawCanvas) {
        Map<String, Object> copied = rawCanvas == null
                ? new LinkedHashMap<>()
                : objectMapper.convertValue(rawCanvas, MAP_TYPE);
        copied.putIfAbsent("version", 2);
        copied.putIfAbsent("nodes", new ArrayList<Map<String, Object>>());
        copied.putIfAbsent("edges", new ArrayList<Map<String, Object>>());
        copied.computeIfPresent("nodes", (key, value) -> mutableList(value));
        copied.computeIfPresent("edges", (key, value) -> mutableList(value));
        return copied;
    }

    private List<Object> mutableList(Object value) {
        if (!(value instanceof List<?> items)) {
            return new ArrayList<>();
        }
        List<Object> copied = new ArrayList<>();
        for (Object item : items) {
            copied.add(item instanceof Map<?, ?> ? objectMapper.convertValue(item, MAP_TYPE) : item);
        }
        return copied;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nodes(Map<String, Object> canvas) {
        return (List<Map<String, Object>>) (List<?>) canvas.computeIfAbsent("nodes", key -> new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> edges(Map<String, Object> canvas) {
        return (List<Map<String, Object>>) (List<?>) canvas.computeIfAbsent("edges", key -> new ArrayList<>());
    }

    private Map<String, Object> findNode(Map<String, Object> canvas, String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        return nodes(canvas).stream()
                .filter(node -> Objects.equals(id, text(node.get("id"))))
                .findFirst()
                .orElse(null);
    }

    private List<String> buildWarnings(Map<String, Object> canvas) {
        List<String> warnings = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes(canvas)) {
            String id = text(node.get("id"));
            if (!StringUtils.hasText(id)) {
                warnings.add("Canvas contains a node without id");
            } else if (!ids.add(id)) {
                warnings.add("Canvas contains duplicate node id: " + id);
            }
        }
        for (Map<String, Object> edge : edges(canvas)) {
            String source = text(edge.get("source"));
            String target = text(edge.get("target"));
            // canvas_json is a layout-only document. Its edge records intentionally keep
            // visual metadata by id and omit GraphSpec-owned source/target semantics.
            if (!StringUtils.hasText(source) && !StringUtils.hasText(target)) {
                continue;
            }
            if (findNode(canvas, source) == null || findNode(canvas, target) == null) {
                warnings.add("Canvas contains edge with missing source/target: " + text(edge.get("id")));
            }
        }
        return warnings;
    }

    private List<RuntimeWorkflowProposalPlaceholderView> placeholderNodes(Map<String, Object> canvas) {
        List<RuntimeWorkflowProposalPlaceholderView> placeholders = new ArrayList<>();
        for (Map<String, Object> node : nodes(canvas)) {
            Map<String, Object> data = mutableMap(node.get("data"));
            if (Boolean.TRUE.equals(data.get("needsConfiguration"))) {
                placeholders.add(new RuntimeWorkflowProposalPlaceholderView(
                        text(node.get("id")),
                        firstText(text(data.get("kind")), text(node.get("type"))),
                        firstText(text(data.get("label")), text(node.get("id"))),
                        firstText(text(data.get("placeholderReason")), "AI generated placeholder needs configuration")));
            }
        }
        return placeholders;
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

    private Map<String, Object> mutableMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(value, MAP_TYPE);
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

}

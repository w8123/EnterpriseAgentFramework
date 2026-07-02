package com.enterprise.ai.runtime.workflow.aicoding;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsQueryService;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeWorkflowAiCodingService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final DateTimeFormatter VERSION_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RuntimeWorkflowDefinitionService workflowService;
    private final RuntimeWorkflowReleaseValidationService validationService;
    private final RuntimeWorkflowDebugService debugService;
    private final RuntimeWorkflowVersionService versionService;
    private final RuntimeRunOpsQueryService runOpsQueryService;
    private final ObjectMapper objectMapper;

    public ContextView createWorkflow(CreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("workflow ai-coding create request is required");
        }
        RuntimeWorkflowDefinitionEntity entity = new RuntimeWorkflowDefinitionEntity();
        entity.setName(requireText(request.name(), "workflow name is required"));
        entity.setKeySlug(requireText(request.keySlug(), "workflow keySlug is required"));
        entity.setProjectId(request.projectId());
        entity.setProjectCode(request.projectCode());
        entity.setDescription(request.description());
        entity.setWorkflowType(defaultText(request.workflowType(), "PAGE_ASSISTANT"));
        entity.setRuntimeType(defaultText(request.runtimeType(), "LANGGRAPH4J"));
        entity.setDefaultModelInstanceId(request.defaultModelInstanceId());
        entity.setManagedBy("AI_CODING");
        entity.setStatus("DRAFT");
        entity.setGraphSpecJson(writeJson(request.graphSpec() == null ? emptyGraph(entity.getKeySlug(), entity.getName()) : request.graphSpec()));
        entity.setCanvasJson(writeJsonOrNull(request.canvas()));
        entity.setExtraJson(writeJsonOrNull(request.extra()));
        RuntimeWorkflowDefinitionEntity created = workflowService.create(entity);
        return contextFromWorkflow(created);
    }

    public ContextView context(String workflowId) {
        return contextFromWorkflow(requireWorkflow(workflowId));
    }

    public ValidationView validateWorkflow(String workflowId, ValidateRequest request) {
        RuntimeWorkflowDefinitionEntity workflow = requireWorkflow(workflowId);
        String mode = request == null || request.mode() == null ? "CURRENT" : request.mode().name();
        RuntimeWorkflowReleaseValidationResult validation;
        if ("PROPOSED".equals(mode) && request != null && request.graphSpec() != null) {
            validation = validationService.validateProposed(workflow, request.graphSpec());
        } else {
            validation = validationService.validate(workflow);
        }
        return validationView(workflow.getId(), mode, validation);
    }

    public PatchView patchWorkflow(String workflowId, PatchRequest request) {
        RuntimeWorkflowDefinitionEntity workflow = requireWorkflow(workflowId);
        PatchRequest actual = request == null ? PatchRequest.empty() : request;
        GraphSpec graph = readGraph(workflow.getGraphSpecJson());
        Map<String, Object> canvas = readMap(workflow.getCanvasJson());
        PatchAccumulator accumulator = applyPatch(graph, canvas, actual.operations());
        RuntimeWorkflowReleaseValidationResult validation = validationService.validateProposed(workflow, graph);
        boolean dryRun = actual.dryRun() == null || actual.dryRun();
        RuntimeWorkflowDefinitionEntity savedWorkflow = workflow;
        if (!dryRun) {
            RuntimeWorkflowDefinitionEntity update = new RuntimeWorkflowDefinitionEntity();
            update.setGraphSpecJson(writeJson(graph));
            update.setCanvasJson(writeJsonOrNull(canvas));
            savedWorkflow = workflowService.update(workflowId, update);
        }
        return new PatchView(
                dryRun,
                !dryRun,
                accumulator.summary(),
                List.copyOf(accumulator.changedNodes()),
                List.copyOf(accumulator.changedEdges()),
                graph,
                canvas,
                validationView(workflowId, "PROPOSED", validation),
                dryRun ? snapshot(workflow) : snapshot(savedWorkflow),
                List.of(),
                List.of());
    }

    public RunView runWorkflow(String workflowId, RunRequest request) {
        RuntimeWorkflowDefinitionEntity workflow = requireWorkflow(workflowId);
        RunRequest actual = request == null ? RunRequest.empty() : request;
        Map<String, Object> input = new LinkedHashMap<>();
        if (actual.input() != null) {
            input.putAll(actual.input());
        }
        if (actual.runtimeContext() != null) {
            input.put("runtimeContext", actual.runtimeContext());
        }
        Map<String, Object> debugOptions = new LinkedHashMap<>();
        debugOptions.put("source", "workflow-ai-coding");
        debugOptions.put("dryRun", actual.dryRun());
        RuntimeWorkflowDebugService.DebugRunResult result = debugService.debugRun(
                new RuntimeWorkflowDebugService.DebugRunRequest(
                        workflow.getId(),
                        workflow.getKeySlug(),
                        workflow.getName(),
                        workflow.getWorkflowType(),
                        workflow.getProjectCode(),
                        workflow.getRuntimeType(),
                        workflow.getDefaultModelInstanceId(),
                        workflow.getGraphSpecJson(),
                        workflow.getCanvasJson(),
                        actual.message(),
                        input,
                        debugOptions));
        return new RunView(
                normalizeStatus(result.status()),
                result.answer(),
                result.traceId(),
                result.runId(),
                result.steps(),
                result.success() ? List.of() : errorList(result.errorMessage(), result.errorCode()),
                List.of(),
                Map.of("finalState", result.finalState() == null ? Map.of() : result.finalState()));
    }

    public VersionsView versions(String workflowId) {
        RuntimeWorkflowDefinitionEntity workflow = requireWorkflow(workflowId);
        RuntimeWorkflowReleaseValidationResult validation = validationService.validate(workflow);
        List<VersionView> versions = versionService.listVersions(workflowId).stream()
                .map(this::versionView)
                .toList();
        return new VersionsView(
                workflowId,
                workflow.getStatus(),
                versions.stream().filter(version -> "ACTIVE".equalsIgnoreCase(version.status())).findFirst().orElse(null),
                versions,
                validationView(workflowId, "CURRENT", validation),
                true,
                List.of());
    }

    public PublishView publishWorkflow(String workflowId, PublishRequest request) {
        PublishRequest actual = request == null ? PublishRequest.empty() : request;
        String version = StringUtils.hasText(actual.version())
                ? actual.version().trim()
                : "v" + VERSION_TIME.format(LocalDateTime.now());
        int rolloutPercent = actual.rolloutPercent() == null ? 100 : actual.rolloutPercent();
        String publishedBy = defaultText(actual.publishedBy(), "workflow-ai-coding");
        RuntimeWorkflowVersionEntity published = versionService.publish(
                workflowId,
                version,
                rolloutPercent,
                actual.note(),
                publishedBy);
        return publishView(published);
    }

    public RunListView runs(String workflowId, Integer limit, Integer days) {
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        int safeDays = days == null ? 7 : Math.max(1, Math.min(days, 30));
        List<RuntimeRunOpsViews.RuntimeRunOpsSummaryView> runs = runOpsQueryService.recent(null, safeLimit, safeDays).stream()
                .filter(run -> workflowId.equals(run.workflowId()) || workflowId.equals(run.sourceId()))
                .toList();
        return new RunListView(workflowId, runs, List.of());
    }

    public RunDetailView runDetail(String workflowId, String traceId) {
        RuntimeRunOpsViews.RuntimeRunOpsDetailView detail = runOpsQueryService.detail(traceId);
        return new RunDetailView(workflowId, traceId, detail, List.of());
    }

    public PageAssistantCatalogView pageAssistantCatalog(String workflowId) {
        RuntimeWorkflowDefinitionEntity workflow = requireWorkflow(workflowId);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("workflowId", workflowId);
        context.put("projectId", workflow.getProjectId());
        context.put("projectCode", workflow.getProjectCode());
        context.put("workflowType", workflow.getWorkflowType());
        return new PageAssistantCatalogView(workflowId, context, List.of(), List.of());
    }

    public PageAssistantValidateView validatePageAssistant(String workflowId, PageAssistantValidateRequest request) {
        GraphSpec proposed = request == null ? null : request.graphSpec();
        ValidationView validation = validateWorkflow(workflowId,
                new ValidateRequest(proposed == null ? ValidateRequest.Mode.CURRENT : ValidateRequest.Mode.PROPOSED, proposed));
        return new PageAssistantValidateView(workflowId, validation, List.of());
    }

    public RunView smokeTestPageAssistant(String workflowId, RunRequest request) {
        return runWorkflow(workflowId, request);
    }

    private ContextView contextFromWorkflow(RuntimeWorkflowDefinitionEntity workflow) {
        GraphSpec graphSpec = readGraph(workflow.getGraphSpecJson());
        RuntimeWorkflowReleaseValidationResult validation = validationService.validate(workflow);
        return new ContextView(
                snapshot(workflow),
                graphSpec,
                readMap(workflow.getCanvasJson()),
                validationView(workflow.getId(), "CURRENT", validation),
                AgentGraphNodeType.catalog(),
                runtimeHints(workflow),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private RuntimeWorkflowDefinitionEntity requireWorkflow(String workflowId) {
        if (!StringUtils.hasText(workflowId)) {
            throw new IllegalArgumentException("workflowId is required");
        }
        return workflowService.findById(workflowId.trim())
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
    }

    private GraphSpec readGraph(String graphSpecJson) {
        if (!StringUtils.hasText(graphSpecJson)) {
            return emptyGraph(null, null);
        }
        try {
            return objectMapper.readValue(graphSpecJson, GraphSpec.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("GraphSpec JSON is invalid: " + ex.getMessage(), ex);
        }
    }

    private Map<String, Object> readMap(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(raw, MAP_TYPE);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (Exception ex) {
            throw new IllegalArgumentException("JSON object is invalid: " + ex.getMessage(), ex);
        }
    }

    private PatchAccumulator applyPatch(GraphSpec graph, Map<String, Object> canvas, List<GraphPatchOperation> operations) {
        PatchAccumulator accumulator = new PatchAccumulator();
        List<GraphPatchOperation> actualOperations = operations == null ? List.of() : operations;
        for (GraphPatchOperation operation : actualOperations) {
            if (operation == null || operation.op() == null) {
                continue;
            }
            switch (operation.op()) {
                case ADD_NODE -> addNode(graph, operation.node(), accumulator);
                case UPDATE_NODE -> updateNode(graph, operation.nodeId(), operation.patch(), accumulator);
                case DELETE_NODE -> deleteNode(graph, operation.nodeId(), accumulator);
                case ADD_EDGE -> addEdge(graph, operation.edge(), accumulator);
                case DELETE_EDGE -> deleteEdge(graph, operation.edgeId(), operation.edge(), accumulator);
                case SET_ENTRY -> setEntry(graph, operation.entry(), accumulator);
            }
        }
        if (canvas != null && !canvas.containsKey("updatedBy")) {
            canvas.put("updatedBy", "workflow-ai-coding");
        }
        accumulator.operationCount(actualOperations.size());
        return accumulator;
    }

    private void addNode(GraphSpec graph, GraphSpec.Node node, PatchAccumulator accumulator) {
        if (node == null || !StringUtils.hasText(node.getId())) {
            throw new IllegalArgumentException("ADD_NODE requires node.id");
        }
        List<GraphSpec.Node> nodes = mutableNodes(graph);
        String nodeId = node.getId().trim();
        if (nodes.stream().anyMatch(item -> item != null && nodeId.equals(item.getId()))) {
            throw new IllegalArgumentException("duplicate graph node id: " + nodeId);
        }
        nodes.add(node);
        graph.setNodes(nodes);
        accumulator.changedNode(nodeId);
    }

    private void updateNode(GraphSpec graph, String nodeId, Map<String, Object> patch, PatchAccumulator accumulator) {
        String id = requireText(nodeId, "UPDATE_NODE requires nodeId");
        GraphSpec.Node node = findNode(graph, id);
        if (node == null) {
            throw new IllegalArgumentException("graph node not found: " + id);
        }
        Map<String, Object> merged = objectMapper.convertValue(node, MAP_TYPE);
        if (patch != null) {
            merged.putAll(patch);
        }
        GraphSpec.Node updated = objectMapper.convertValue(merged, GraphSpec.Node.class);
        List<GraphSpec.Node> nodes = mutableNodes(graph);
        for (int i = 0; i < nodes.size(); i++) {
            if (id.equals(nodes.get(i).getId())) {
                nodes.set(i, updated);
                break;
            }
        }
        graph.setNodes(nodes);
        accumulator.changedNode(id);
    }

    private void deleteNode(GraphSpec graph, String nodeId, PatchAccumulator accumulator) {
        String id = requireText(nodeId, "DELETE_NODE requires nodeId");
        List<GraphSpec.Node> nodes = mutableNodes(graph);
        if (nodes.removeIf(node -> node != null && id.equals(node.getId()))) {
            graph.setNodes(nodes);
            List<GraphSpec.Edge> edges = mutableEdges(graph);
            edges.removeIf(edge -> edge != null && (id.equals(edge.getFrom()) || id.equals(edge.getTo())));
            graph.setEdges(edges);
            if (id.equals(graph.getEntry())) {
                graph.setEntry(null);
            }
            accumulator.changedNode(id);
        }
    }

    private void addEdge(GraphSpec graph, GraphSpec.Edge edge, PatchAccumulator accumulator) {
        if (edge == null || !StringUtils.hasText(edge.getFrom()) || !StringUtils.hasText(edge.getTo())) {
            throw new IllegalArgumentException("ADD_EDGE requires edge.from and edge.to");
        }
        List<GraphSpec.Edge> edges = mutableEdges(graph);
        String edgeId = edgeId(edge);
        if (edges.stream().noneMatch(item -> edgeId.equals(edgeId(item)))) {
            edges.add(edge);
            graph.setEdges(edges);
            accumulator.changedEdge(edgeId);
        }
    }

    private void deleteEdge(GraphSpec graph, String edgeId, GraphSpec.Edge edge, PatchAccumulator accumulator) {
        String id = StringUtils.hasText(edgeId) ? edgeId.trim() : edgeId(edge);
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("DELETE_EDGE requires edgeId or edge");
        }
        List<GraphSpec.Edge> edges = mutableEdges(graph);
        if (edges.removeIf(item -> id.equals(edgeId(item)))) {
            graph.setEdges(edges);
            accumulator.changedEdge(id);
        }
    }

    private void setEntry(GraphSpec graph, String entry, PatchAccumulator accumulator) {
        String nodeId = requireText(entry, "SET_ENTRY requires entry");
        graph.setEntry(nodeId);
        accumulator.changedNode(nodeId);
    }

    private List<GraphSpec.Node> mutableNodes(GraphSpec graph) {
        return new ArrayList<>(graph.getNodes() == null ? List.of() : graph.getNodes());
    }

    private List<GraphSpec.Edge> mutableEdges(GraphSpec graph) {
        return new ArrayList<>(graph.getEdges() == null ? List.of() : graph.getEdges());
    }

    private GraphSpec.Node findNode(GraphSpec graph, String nodeId) {
        return graph.getNodes() == null ? null : graph.getNodes().stream()
                .filter(node -> node != null && nodeId.equals(node.getId()))
                .findFirst()
                .orElse(null);
    }

    private String edgeId(GraphSpec.Edge edge) {
        if (edge == null) {
            return null;
        }
        if (StringUtils.hasText(edge.getId())) {
            return edge.getId().trim();
        }
        if (StringUtils.hasText(edge.getFrom()) && StringUtils.hasText(edge.getTo())) {
            return edge.getFrom().trim() + "->" + edge.getTo().trim();
        }
        return null;
    }

    private GraphSpec emptyGraph(String code, String name) {
        GraphSpec graph = new GraphSpec();
        graph.setCode(code);
        graph.setName(name);
        graph.setMode("WORKFLOW");
        graph.setNodes(List.of());
        graph.setEdges(List.of());
        return graph;
    }

    private WorkflowSnapshot snapshot(RuntimeWorkflowDefinitionEntity workflow) {
        return new WorkflowSnapshot(
                workflow.getId(),
                workflow.getKeySlug(),
                workflow.getName(),
                workflow.getDescription(),
                workflow.getProjectId(),
                workflow.getProjectCode(),
                workflow.getWorkflowType(),
                workflow.getRuntimeType(),
                workflow.getDefaultModelInstanceId(),
                workflow.getStatus(),
                workflow.getManagedBy(),
                workflow.getUpdatedAt());
    }

    private ValidationView validationView(String workflowId, String mode, RuntimeWorkflowReleaseValidationResult result) {
        RuntimeWorkflowReleaseValidationResult actual = result == null
                ? RuntimeWorkflowReleaseValidationResult.builder().build()
                : result;
        return new ValidationView(workflowId, mode, actual.valid(), actual.errors(), actual.warnings());
    }

    private VersionView versionView(RuntimeWorkflowVersionEntity entity) {
        return new VersionView(
                entity.getId(),
                entity.getVersion(),
                entity.getStatus(),
                entity.getRolloutPercent(),
                entity.getPublishedBy(),
                entity.getPublishedAt(),
                entity.getNote());
    }

    private PublishView publishView(RuntimeWorkflowVersionEntity entity) {
        return new PublishView(
                entity.getWorkflowId(),
                entity.getId(),
                entity.getVersion(),
                entity.getStatus(),
                entity.getRolloutPercent(),
                entity.getPublishedBy(),
                entity.getPublishedAt());
    }

    private Map<String, Object> runtimeHints(RuntimeWorkflowDefinitionEntity workflow) {
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("runtimeType", workflow.getRuntimeType());
        hints.put("projectCode", workflow.getProjectCode());
        hints.put("defaultModelInstanceId", workflow.getDefaultModelInstanceId());
        return hints;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("JSON serialize failed: " + ex.getMessage(), ex);
        }
    }

    private String writeJsonOrNull(Object value) {
        if (value == null) {
            return null;
        }
        return writeJson(value);
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String nonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private List<String> errorList(String message, String code) {
        String error = nonBlank(message, code);
        return StringUtils.hasText(error) ? List.of(error) : List.of();
    }

    private String normalizeStatus(String status) {
        return "WAITING_USER".equals(status) ? "WAITING" : status;
    }

    public record CreateRequest(String name,
                                String keySlug,
                                Long projectId,
                                String projectCode,
                                String description,
                                String workflowType,
                                String runtimeType,
                                String defaultModelInstanceId,
                                GraphSpec graphSpec,
                                Map<String, Object> canvas,
                                Map<String, Object> extra,
                                String reason) {
    }

    public record ContextView(WorkflowSnapshot workflow,
                              GraphSpec graphSpec,
                              Map<String, Object> canvas,
                              ValidationView validation,
                              List<AgentGraphNodeType.Descriptor> nodeTypes,
                              Map<String, Object> runtimeHints,
                              List<Object> bindings,
                              Map<String, Object> pageAssistantContext,
                              List<Object> availableModels,
                              List<Object> availableTools,
                              List<String> warnings) {
    }

    public record WorkflowSnapshot(String id,
                                   String keySlug,
                                   String name,
                                   String description,
                                   Long projectId,
                                   String projectCode,
                                   String workflowType,
                                   String runtimeType,
                                   String defaultModelInstanceId,
                                   String status,
                                   String managedBy,
                                   LocalDateTime updatedAt) {
    }

    public record ValidateRequest(Mode mode,
                                  GraphSpec graphSpec) {
        public enum Mode {
            CURRENT,
            PROPOSED
        }
    }

    public record ValidationView(String workflowId,
                                 String mode,
                                 boolean valid,
                                 List<RuntimeWorkflowReleaseValidationResult.Item> errors,
                                 List<RuntimeWorkflowReleaseValidationResult.Item> warnings) {
    }

    public record PatchRequest(String baseRevision,
                               Boolean dryRun,
                               List<GraphPatchOperation> operations,
                               LayoutOptions layout,
                               String reason) {
        private static PatchRequest empty() {
            return new PatchRequest(null, true, List.of(), null, null);
        }
    }

    public record GraphPatchOperation(Op op,
                                      GraphSpec.Node node,
                                      String nodeId,
                                      Map<String, Object> patch,
                                      GraphSpec.Edge edge,
                                      String edgeId,
                                      String entry) {
        public enum Op {
            ADD_NODE,
            UPDATE_NODE,
            DELETE_NODE,
            ADD_EDGE,
            DELETE_EDGE,
            SET_ENTRY
        }
    }

    public record LayoutOptions(String direction,
                                Integer columnGap,
                                Integer rowGap) {
    }

    public record PatchView(boolean dryRun,
                            boolean saved,
                            String patchSummary,
                            List<String> changedNodes,
                            List<String> changedEdges,
                            GraphSpec proposedGraphSpec,
                            Map<String, Object> proposedCanvas,
                            ValidationView validation,
                            WorkflowSnapshot workflow,
                            List<String> warnings,
                            List<String> errors) {
    }

    public record RunRequest(Map<String, Object> input,
                             String message,
                             Map<String, Object> runtimeContext,
                             Boolean dryRun) {
        private static RunRequest empty() {
            return new RunRequest(Map.of(), null, Map.of(), true);
        }
    }

    public record RunView(String status,
                          String answer,
                          String traceId,
                          String runId,
                          List<RuntimeWorkflowDebugService.DebugStepResult> nodeOutputs,
                          List<String> errors,
                          List<String> warnings,
                          Map<String, Object> metadata) {
    }

    public record VersionsView(String workflowId,
                               String currentStatus,
                               VersionView publishedVersion,
                               List<VersionView> versions,
                               ValidationView releaseValidation,
                               boolean draftDirty,
                               List<String> warnings) {
    }

    public record VersionView(Long versionId,
                              String version,
                              String status,
                              Integer rolloutPercent,
                              String publishedBy,
                              LocalDateTime publishedAt,
                              String note) {
    }

    public record PublishRequest(String version,
                                 Integer rolloutPercent,
                                 String note,
                                 String publishedBy) {
        private static PublishRequest empty() {
            return new PublishRequest(null, 100, null, "workflow-ai-coding");
        }
    }

    public record PublishView(String workflowId,
                              Long versionId,
                              String version,
                              String status,
                              Integer rolloutPercent,
                              String publishedBy,
                              LocalDateTime publishedAt) {
    }

    public record RunListView(String workflowId,
                              List<RuntimeRunOpsViews.RuntimeRunOpsSummaryView> runs,
                              List<String> warnings) {
    }

    public record RunDetailView(String workflowId,
                                String traceId,
                                RuntimeRunOpsViews.RuntimeRunOpsDetailView detail,
                                List<String> warnings) {
    }

    public record PageAssistantCatalogView(String workflowId,
                                           Map<String, Object> context,
                                           List<Object> pages,
                                           List<String> warnings) {
    }

    public record PageAssistantValidateRequest(GraphSpec graphSpec,
                                               Map<String, Object> pageAssistantContext) {
    }

    public record PageAssistantValidateView(String workflowId,
                                            ValidationView validation,
                                            List<String> warnings) {
    }

    private static final class PatchAccumulator {
        private final LinkedHashSet<String> changedNodes = new LinkedHashSet<>();
        private final LinkedHashSet<String> changedEdges = new LinkedHashSet<>();
        private int operationCount;

        private void changedNode(String nodeId) {
            if (StringUtils.hasText(nodeId)) {
                changedNodes.add(nodeId);
            }
        }

        private void changedEdge(String edgeId) {
            if (StringUtils.hasText(edgeId)) {
                changedEdges.add(edgeId);
            }
        }

        private void operationCount(int operationCount) {
            this.operationCount = operationCount;
        }

        private LinkedHashSet<String> changedNodes() {
            return changedNodes;
        }

        private LinkedHashSet<String> changedEdges() {
            return changedEdges;
        }

        private String summary() {
            return operationCount + " operations";
        }
    }
}

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
import com.enterprise.ai.runtime.workflow.layout.RuntimeWorkflowCanvasLayoutService;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelCatalogClient;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService.MutationOperation;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService.MutationResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private final RuntimeWorkflowCanvasLayoutService canvasLayoutService;
    private final RuntimeWorkflowGraphMutationService graphMutationService;
    private final RuntimeModelCatalogClient modelCatalogClient;
    private final RuntimeCapabilityCatalogClient capabilityCatalogClient;

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
        entity.setWorkflowType(defaultText(request.workflowType(), "CHAT"));
        entity.setRuntimeType(defaultText(request.runtimeType(), "LANGGRAPH4J"));
        entity.setDefaultModelInstanceId(request.defaultModelInstanceId());
        entity.setManagedBy("AI_CODING");
        entity.setStatus("DRAFT");
        GraphSpec graph = request.graphSpec() == null
                ? emptyGraph(entity.getKeySlug(), entity.getName())
                : request.graphSpec();
        Map<String, Object> canvas = canvasLayoutService.projectAndLayout(
                graph,
                request.canvas(),
                RuntimeWorkflowCanvasLayoutService.Options.defaults());
        entity.setGraphSpecJson(writeJson(graph));
        entity.setCanvasJson(writeJson(canvas));
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
        if ("PROPOSED".equals(mode)) {
            validation = validationService.validateProposed(
                    workflow,
                    request == null ? null : request.graphSpec());
        } else {
            validation = validationService.validate(workflow);
        }
        return validationView(workflow.getId(), mode, validation);
    }

    public PatchView patchWorkflow(String workflowId, PatchRequest request) {
        RuntimeWorkflowDefinitionEntity workflow = requireWorkflow(workflowId);
        PatchRequest actual = request == null ? PatchRequest.empty() : request;
        workflowService.assertRevision(workflow, actual.baseRevision());
        GraphSpec currentGraph = readGraph(workflow.getGraphSpecJson());
        Map<String, Object> canvas = readMap(workflow.getCanvasJson());
        MutationResult mutation = graphMutationService.mutate(currentGraph, mutationOperations(actual.operations()));
        GraphSpec graph = mutation.graphSpec();
        canvas = canvasLayoutService.projectAndLayout(graph, canvas, layoutOptions(actual.layout()));
        RuntimeWorkflowReleaseValidationResult validation = validationService.validateProposed(workflow, graph);
        boolean dryRun = actual.dryRun() == null || actual.dryRun();
        boolean saveBlocked = !dryRun && !validation.valid();
        RuntimeWorkflowDefinitionEntity savedWorkflow = workflow;
        if (!dryRun && !saveBlocked) {
            RuntimeWorkflowDefinitionEntity update = new RuntimeWorkflowDefinitionEntity();
            update.setGraphSpecJson(writeJson(graph));
            update.setCanvasJson(writeJsonOrNull(canvas));
            savedWorkflow = StringUtils.hasText(actual.baseRevision())
                    ? workflowService.update(workflowId, update, actual.baseRevision())
                    : workflowService.update(workflowId, update);
        }
        return new PatchView(
                dryRun,
                !dryRun && !saveBlocked,
                mutation.summary(),
                mutation.changedNodes(),
                mutation.changedEdges(),
                graph,
                canvas,
                validationView(workflowId, "PROPOSED", validation),
                dryRun || saveBlocked ? snapshot(workflow) : snapshot(savedWorkflow),
                List.of(),
                saveBlocked
                        ? validation.errors().stream().map(RuntimeWorkflowReleaseValidationResult.Item::message).toList()
                        : List.of());
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
                publishedBy,
                actual.baseRevision());
        return publishView(published);
    }

    public RunListView runs(String workflowId, Integer limit, Integer days) {
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        int safeDays = days == null ? 7 : Math.max(1, Math.min(days, 30));
        List<RuntimeRunOpsViews.RuntimeRunOpsSummaryView> runs = runOpsQueryService.recent(
                        null, null, "WORKFLOW", null, null, null, safeLimit, safeDays).stream()
                .filter(run -> workflowId.equals(run.workflowId()))
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
        List<WarningView> warnings = new ArrayList<>();
        List<Object> models = loadAvailableModels(warnings);
        List<Object> tools = loadAvailableTools(workflow, warnings);
        return new ContextView(
                snapshot(workflow),
                graphSpec,
                readMap(workflow.getCanvasJson()),
                validationView(workflow.getId(), "CURRENT", validation),
                AgentGraphNodeType.catalog(),
                runtimeHints(workflow),
                pageAssistantContext(workflow, graphSpec),
                models,
                tools,
                warnings.stream().map(warning -> (Object) warning).toList());
    }

    private List<Object> loadAvailableModels(List<WarningView> warnings) {
        try {
            List<Map<String, Object>> models = modelCatalogClient.listActiveLlms();
            if (models.isEmpty()) {
                warnings.add(new WarningView(
                        "NO_ACTIVE_LLM",
                        "No ACTIVE LLM model instances were found in Model Center.",
                        "model-service",
                        false));
            }
            return List.copyOf(models);
        } catch (Exception ex) {
            warnings.add(new WarningView(
                    "MODEL_CATALOG_UNAVAILABLE",
                    "Model catalog is unavailable: " + ex.getMessage(),
                    "model-service",
                    true));
            return List.of();
        }
    }

    private List<Object> loadAvailableTools(RuntimeWorkflowDefinitionEntity workflow, List<WarningView> warnings) {
        if (workflow.getProjectId() == null) {
            warnings.add(new WarningView(
                    "NO_PROJECT_TOOLS",
                    "Workflow has no projectId; project tools cannot be listed.",
                    "capability-service",
                    false));
            return List.of();
        }
        try {
            List<Map<String, Object>> tools = capabilityCatalogClient.listProjectTools(workflow.getProjectId());
            if (tools == null || tools.isEmpty()) {
                warnings.add(new WarningView(
                        "NO_PROJECT_TOOLS",
                        "No enabled tools were found for projectId=" + workflow.getProjectId(),
                        "capability-service",
                        false));
                return List.of();
            }
            return List.copyOf(tools);
        } catch (Exception ex) {
            warnings.add(new WarningView(
                    "CAPABILITY_CATALOG_UNAVAILABLE",
                    "Capability tool catalog is unavailable: " + ex.getMessage(),
                    "capability-service",
                    true));
            return List.of();
        }
    }

    private Map<String, Object> pageAssistantContext(RuntimeWorkflowDefinitionEntity workflow, GraphSpec graphSpec) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("workflowId", workflow.getId());
        context.put("projectId", workflow.getProjectId());
        context.put("projectCode", workflow.getProjectCode());
        context.put("workflowType", workflow.getWorkflowType());
        Map<String, Object> extra = readMap(workflow.getExtraJson());
        if (extra.containsKey("pageKey")) {
            context.put("pageKey", extra.get("pageKey"));
        }
        if (extra.containsKey("routePattern")) {
            context.put("routePattern", extra.get("routePattern"));
        }
        if (extra.containsKey("actionKeys")) {
            context.put("actionKeys", extra.get("actionKeys"));
        }
        if ("PAGE_ASSISTANT".equalsIgnoreCase(String.valueOf(workflow.getWorkflowType()))) {
            context.put("graphEntry", graphSpec == null ? null : graphSpec.getEntry());
        }
        return context;
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

    private List<MutationOperation> mutationOperations(List<GraphPatchOperation> operations) {
        if (operations == null) {
            return List.of();
        }
        return operations.stream()
                .map(operation -> {
                    if (operation == null || operation.op() == null) {
                        return null;
                    }
                    Map<String, Object> patch = operation.patch();
                    if (operation.op() == GraphPatchOperation.Op.UPDATE_NODE
                            && (patch == null || patch.isEmpty())
                            && operation.node() != null) {
                        patch = objectMapper.convertValue(operation.node(), MAP_TYPE);
                    }
                    return new MutationOperation(
                            MutationOperation.Op.valueOf(operation.op().name()),
                            operation.node(),
                            operation.nodeId(),
                            patch,
                            operation.edge(),
                            operation.edgeId(),
                            operation.entry(),
                            operation.finish());
                })
                .toList();
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

    private RuntimeWorkflowCanvasLayoutService.Options layoutOptions(LayoutOptions options) {
        if (options == null) {
            return RuntimeWorkflowCanvasLayoutService.Options.defaults();
        }
        return new RuntimeWorkflowCanvasLayoutService.Options(
                options.autoLayout(),
                options.direction(),
                options.columnGap(),
                options.rowGap());
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
                              Map<String, Object> pageAssistantContext,
                              List<Object> availableModels,
                              List<Object> availableTools,
                              List<Object> warnings) {
    }

    public record WarningView(String code, String message, String source, boolean retryable) {
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
                                      String entry,
                                      List<String> finish) {
        public GraphPatchOperation(Op op,
                                   GraphSpec.Node node,
                                   String nodeId,
                                   Map<String, Object> patch,
                                   GraphSpec.Edge edge,
                                   String edgeId,
                                   String entry) {
            this(op, node, nodeId, patch, edge, edgeId, entry, null);
        }

        public enum Op {
            ADD_NODE,
            UPDATE_NODE,
            DELETE_NODE,
            ADD_EDGE,
            UPDATE_EDGE,
            DELETE_EDGE,
            SET_ENTRY,
            SET_FINISH
        }
    }

    public record LayoutOptions(Boolean autoLayout,
                                 String direction,
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

    public record PublishRequest(String baseRevision,
                                 String version,
                                 Integer rolloutPercent,
                                 String note,
                                 String publishedBy) {
        public PublishRequest(String version,
                              Integer rolloutPercent,
                              String note,
                              String publishedBy) {
            this(null, version, rolloutPercent, note, publishedBy);
        }

        private static PublishRequest empty() {
            return new PublishRequest(null, null, 100, null, "workflow-ai-coding");
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

}

package com.enterprise.ai.runtime.workflow.aicoding;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.execution.WorkflowExecutionStatus;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsQueryService;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDocumentCanonicalizer;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowInputContract;
import com.enterprise.ai.runtime.workflow.WorkflowSemanticValues;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService.BindingInput;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService.BindingView;
import com.enterprise.ai.runtime.workflow.layout.RuntimeWorkflowCanvasLayoutService;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.PageActionCatalogEntry;
import com.enterprise.ai.runtime.client.model.RuntimeModelCatalogClient;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService.MutationOperation;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService.MutationResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final RuntimeWorkflowDocumentCanonicalizer documentCanonicalizer;
    private final RuntimeWorkflowResourceBindingService resourceBindingService;
    private final RuntimeControlCatalogClient controlCatalogClient;

    @Transactional
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
        entity.setWorkflowKind(WorkflowSemanticValues.normalizeWorkflowKind(
                defaultText(request.workflowKind(), WorkflowSemanticValues.KIND_GENERAL)));
        entity.setExecutionEngine(WorkflowSemanticValues.normalizeExecutionEngine(
                defaultText(request.executionEngine(), WorkflowSemanticValues.ENGINE_GRAPH_SPEC)));
        entity.setDefaultModelInstanceId(request.defaultModelInstanceId());
        entity.setDefinitionAuthority(WorkflowSemanticValues.AUTHORITY_USER);
        entity.setCreationChannel(WorkflowSemanticValues.CHANNEL_AI_CODING);
        entity.setStatus("DRAFT");
        GraphSpec graph = initialGraph(entity.getWorkflowKind(), request.graphSpec());
        Map<String, Object> canvas = canvasLayoutService.projectAndLayout(
                graph,
                request.canvas(),
                RuntimeWorkflowCanvasLayoutService.Options.defaults());
        graph = documentCanonicalizer.canonicalizeGraphSpec(graph);
        entity.setGraphSpecJson(writeJson(graph));
        entity.setCanvasJson(writeJson(canvas));
        entity.setExtraJson(writeJsonOrNull(request.extra()));
        if (WorkflowSemanticValues.KIND_PAGE_ASSISTANT.equals(entity.getWorkflowKind())
                && (request.resourceBindings() == null || request.resourceBindings().stream()
                .filter(binding -> binding != null
                        && RuntimeWorkflowResourceBindingService.RESOURCE_PAGE
                        .equalsIgnoreCase(binding.resourceType())
                        && (!StringUtils.hasText(binding.bindingRole())
                        || RuntimeWorkflowResourceBindingService.ROLE_TARGET
                        .equalsIgnoreCase(binding.bindingRole())))
                .count() != 1)) {
            throw new IllegalArgumentException(
                    "PAGE_ASSISTANT workflow requires exactly one TARGET PAGE resource binding");
        }
        RuntimeWorkflowDefinitionEntity created = workflowService.create(entity);
        resourceBindingService.replace(created, request.resourceBindings());
        return contextFromWorkflow(created);
    }

    /**
     * Replaces the working copy of an existing DRAFT created by Page Workbench
     * AI Coding. Same-request retries stay on one Workflow id; corrected retries
     * must overwrite GraphSpec/canvas/metadata instead of silently reusing the
     * first orphan draft left by a rolled-back Control transaction.
     */
    @Transactional
    public ContextView replaceDraft(String workflowId, CreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("workflow ai-coding replace request is required");
        }
        RuntimeWorkflowDefinitionEntity workflow = requireWorkflow(workflowId);
        if (!"DRAFT".equalsIgnoreCase(workflow.getStatus())) {
            throw new IllegalArgumentException(
                    "task-scoped workflow is no longer a draft");
        }
        GraphSpec graph = initialGraph(workflow.getWorkflowKind(), request.graphSpec());
        Map<String, Object> canvas = canvasLayoutService.projectAndLayout(
                graph,
                request.canvas(),
                RuntimeWorkflowCanvasLayoutService.Options.defaults());
        graph = documentCanonicalizer.canonicalizeGraphSpec(graph);
        if (WorkflowSemanticValues.KIND_PAGE_ASSISTANT.equals(
                WorkflowSemanticValues.normalizeWorkflowKind(
                        defaultText(request.workflowKind(), workflow.getWorkflowKind())))
                && (request.resourceBindings() == null || request.resourceBindings().stream()
                .filter(binding -> binding != null
                        && RuntimeWorkflowResourceBindingService.RESOURCE_PAGE
                        .equalsIgnoreCase(binding.resourceType())
                        && (!StringUtils.hasText(binding.bindingRole())
                        || RuntimeWorkflowResourceBindingService.ROLE_TARGET
                        .equalsIgnoreCase(binding.bindingRole())))
                .count() != 1)) {
            throw new IllegalArgumentException(
                    "PAGE_ASSISTANT workflow requires exactly one TARGET PAGE resource binding");
        }

        RuntimeWorkflowDefinitionEntity update = new RuntimeWorkflowDefinitionEntity();
        update.setName(requireText(request.name(), "workflow name is required"));
        update.setDescription(request.description());
        update.setDefaultModelInstanceId(request.defaultModelInstanceId());
        update.setGraphSpecJson(writeJson(graph));
        update.setCanvasJson(writeJson(canvas));
        update.setExtraJson(writeJsonOrNull(request.extra()));
        RuntimeWorkflowDefinitionEntity saved = workflowService.update(
                workflow.getId(),
                update);
        resourceBindingService.replace(saved, request.resourceBindings());
        return contextFromWorkflow(saved);
    }

    public ContextView context(String workflowId) {
        return contextFromWorkflow(requireWorkflow(workflowId));
    }

    @Transactional
    public ContextView replaceResourceBindings(
            String workflowId,
            ResourceBindingsRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("workflow resource bindings request is required");
        }
        RuntimeWorkflowDefinitionEntity workflow = requireWorkflow(workflowId);
        if (!"DRAFT".equalsIgnoreCase(workflow.getStatus())) {
            throw new IllegalArgumentException(
                    "workflow resource bindings are immutable after the first publish");
        }
        String baseRevision = requireText(
                request.baseRevision(),
                "baseRevision is required for workflow resource binding changes");
        workflowService.assertRevision(workflow, baseRevision);
        resourceBindingService.replace(workflow, request.resourceBindings());

        // Touch the owning Workflow with the same optimistic revision. If another
        // writer won the race, the outer transaction rolls the binding replacement
        // back together with this failed revision update.
        RuntimeWorkflowDefinitionEntity updated = workflowService.update(
                workflow.getId(),
                new RuntimeWorkflowDefinitionEntity(),
                baseRevision);
        return contextFromWorkflow(updated);
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
        graph = documentCanonicalizer.canonicalizeGraphSpec(graph);
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
        return executeWorkflow(workflow, actual,
                normalizeRuntimeContext(workflow, actual.runtimeContext(), false));
    }

    private RunView executeWorkflow(RuntimeWorkflowDefinitionEntity workflow,
                                    RunRequest actual,
                                    RuntimeContextNormalization contextNormalization) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (actual.input() != null) {
            input.putAll(actual.input());
        }
        input.putAll(contextNormalization.context());
        // The Workflow definition, never a caller supplied runtimeContext, owns project scope.
        if (StringUtils.hasText(workflow.getProjectCode())) {
            input.put("projectCode", workflow.getProjectCode());
        }
        if (actual.runtimeContext() != null && !actual.runtimeContext().isEmpty()) {
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
                        workflow.getWorkflowKind(),
                        workflow.getProjectCode(),
                        workflow.getExecutionEngine(),
                        workflow.getDefaultModelInstanceId(),
                        workflow.getGraphSpecJson(),
                        workflow.getCanvasJson(),
                        actual.message(),
                        input,
                        debugOptions));
        Map<String, Object> metadata = new LinkedHashMap<>(debugStateArtifact(result));
        metadata.put("contextResolution", contextNormalization.evidence());
        return new RunView(
                normalizeStatus(result.status()),
                result.answer(),
                result.traceId(),
                result.runId(),
                result.steps(),
                result.success() ? List.of() : errorList(result.errorMessage(), result.errorCode()),
                List.of(),
                Map.copyOf(metadata));
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
        RuntimeWorkflowDefinitionEntity workflow = requirePageAssistantWorkflow(workflowId);
        GraphSpec graph = readGraph(workflow.getGraphSpecJson());
        List<BindingView> bindings = resourceBindingService.list(workflowId);
        Set<String> boundPages = bindings.stream()
                .filter(binding -> RuntimeWorkflowResourceBindingService.RESOURCE_PAGE
                        .equals(binding.resourceType()))
                .map(BindingView::resourceKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<PageActionCatalogEntry> actions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String boundPage : boundPages) {
            try {
                List<PageActionCatalogEntry> pageActions = controlCatalogClient.listPageActions(
                        workflow.getProjectCode(),
                        boundPage,
                        null,
                        1000);
                if (pageActions != null) {
                    actions.addAll(pageActions);
                }
            } catch (RuntimeException ex) {
                warnings.add("Page action catalog is unavailable for " + boundPage + ": " + ex.getMessage());
            }
        }
        Map<String, PageActionCatalogEntry> actionsByKey = new LinkedHashMap<>();
        for (PageActionCatalogEntry action : actions) {
            actionsByKey.put(action.pageKey() + "\u0000" + action.actionKey(), action);
        }
        List<PageActionNodeMatch> nodes = graph.getNodes() == null
                ? List.of()
                : graph.getNodes().stream()
                .filter(node -> node != null && "PAGE_ACTION".equals(node.getType()))
                .map(node -> pageActionMatch(node, boundPages, actionsByKey))
                .toList();
        return new PageAssistantCatalogView(
                workflowId,
                pageAssistantContext(workflow, graph),
                nodes,
                actions,
                warnings);
    }

    public PageAssistantValidateView validatePageAssistant(String workflowId, PageAssistantValidateRequest request) {
        requirePageAssistantWorkflow(workflowId);
        GraphSpec proposed = request == null ? null : request.graphSpec();
        ValidationView validation = validateWorkflow(workflowId,
                new ValidateRequest(proposed == null ? ValidateRequest.Mode.CURRENT : ValidateRequest.Mode.PROPOSED, proposed));
        return new PageAssistantValidateView(workflowId, validation, List.of());
    }

    public RunView smokeTestPageAssistant(String workflowId, RunRequest request) {
        RuntimeWorkflowDefinitionEntity workflow = requirePageAssistantWorkflow(workflowId);
        RunRequest actual = request == null ? RunRequest.empty() : request;
        RuntimeContextNormalization context = normalizeRuntimeContext(workflow, actual.runtimeContext(), true);
        if (!context.ready()) {
            return new RunView(
                    "CONTEXT_REQUIRED",
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(context.message()),
                    List.of(),
                    Map.of("contextResolution", context.evidence()));
        }
        return executeWorkflow(workflow, actual, context);
    }

    private PageActionNodeMatch pageActionMatch(
            GraphSpec.Node node,
            Set<String> boundPages,
            Map<String, PageActionCatalogEntry> actionsByKey) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String pageKey = text(config.get("pageKey"));
        String actionKey = text(config.get("actionKey"));
        String matchStatus;
        if (!StringUtils.hasText(pageKey)) {
            matchStatus = "PAGE_KEY_EMPTY";
        } else if (!StringUtils.hasText(actionKey)) {
            matchStatus = "ACTION_KEY_EMPTY";
        } else if (!boundPages.contains(pageKey)) {
            matchStatus = "UNBOUND_PAGE";
        } else {
            PageActionCatalogEntry action = actionsByKey.get(pageKey + "\u0000" + actionKey);
            matchStatus = action == null
                    ? "MISSING"
                    : ("ACTIVE".equalsIgnoreCase(action.status()) ? "MATCHED" : "INACTIVE");
        }
        return new PageActionNodeMatch(node.getId(), pageKey, actionKey, matchStatus);
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
        context.put("workflowKind", workflow.getWorkflowKind());
        List<BindingView> bindings = resourceBindingService.list(workflow.getId());
        context.put("resourceBindings", bindings);
        bindings.stream()
                .filter(binding -> RuntimeWorkflowResourceBindingService.RESOURCE_PAGE
                        .equals(binding.resourceType()))
                .filter(binding -> RuntimeWorkflowResourceBindingService.ROLE_TARGET
                        .equals(binding.bindingRole()))
                .findFirst()
                .ifPresent(binding -> context.put("pageKey", binding.resourceKey()));
        if (graphSpec != null && graphSpec.getNodes() != null) {
            List<String> actionKeys = graphSpec.getNodes().stream()
                    .filter(node -> node != null && node.getConfig() != null)
                    .map(node -> text(node.getConfig().get("actionKey")))
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
            context.put("actionKeys", actionKeys);
        }
        if (WorkflowSemanticValues.KIND_PAGE_ASSISTANT.equalsIgnoreCase(
                String.valueOf(workflow.getWorkflowKind()))) {
            context.put("graphEntry", graphSpec == null ? null : graphSpec.getEntryNodeId());
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

    private RuntimeWorkflowDefinitionEntity requirePageAssistantWorkflow(String workflowId) {
        RuntimeWorkflowDefinitionEntity workflow = requireWorkflow(workflowId);
        if (!WorkflowSemanticValues.KIND_PAGE_ASSISTANT.equals(workflow.getWorkflowKind())) {
            throw new IllegalArgumentException(
                    "page-assistant endpoints require workflowKind PAGE_ASSISTANT");
        }
        return workflow;
    }

    /**
     * Supports the documented runtimeContext/pageBridge/pageContext/bridgeGlobal
     * shapes while resolving a PAGE_ASSISTANT smoke-test session through Control.
     * The returned map contains no caller-controlled project or Agent identity
     * after a session has been resolved.
     */
    private RuntimeContextNormalization normalizeRuntimeContext(
            RuntimeWorkflowDefinitionEntity workflow,
            Map<String, Object> runtimeContext,
            boolean resolvePageBridge) {
        Map<String, Object> raw = mapValue(runtimeContext);
        Map<String, Object> normalized = new LinkedHashMap<>();
        mergeContext(normalized, mapValue(raw.get("bridgeGlobal")));
        mergeContext(normalized, mapValue(raw.get("pageContext")));
        mergeContext(normalized, mapValue(raw.get("pageBridge")));
        mergeContext(normalized, raw);

        String sessionId = firstText(
                text(raw.get("embedSessionId")),
                text(raw.get("sessionId")),
                text(mapValue(raw.get("pageBridge")).get("sessionId")),
                text(mapValue(raw.get("pageContext")).get("sessionId")),
                text(mapValue(raw.get("bridgeGlobal")).get("sessionId")));
        if (StringUtils.hasText(sessionId)) {
            normalized.put("sessionId", sessionId);
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("providedKeys", raw.keySet().stream().sorted().toList());
        evidence.put("pageBridgeResolutionRequested", resolvePageBridge);

        if (!resolvePageBridge || !requiresPageBridge(workflow)) {
            evidence.put("status", "NORMALIZED");
            evidence.put("code", "RUNTIME_CONTEXT_NORMALIZED");
            return new RuntimeContextNormalization(true, normalized, Map.copyOf(evidence), null);
        }
        if (!StringUtils.hasText(sessionId)) {
            evidence.put("status", "MISSING");
            evidence.put("code", "PAGE_BRIDGE_CONTEXT_REQUIRED");
            evidence.put("missing", List.of("embedSessionId"));
            return new RuntimeContextNormalization(false, normalized, Map.copyOf(evidence),
                    "此 Workflow 包含页面动作。请先打开目标业务页面，再传入当前 Embed 会话的 embedSessionId 后重试。");
        }
        RuntimeControlCatalogClient.PageBridgeContextResolution resolved;
        try {
            resolved = controlCatalogClient.resolvePageBridgeContext(
                    new RuntimeControlCatalogClient.PageBridgeContextResolutionRequest(
                            sessionId,
                            workflow.getProjectCode()));
        } catch (RuntimeException ex) {
            evidence.put("status", "UNAVAILABLE");
            evidence.put("code", "PAGE_BRIDGE_CONTEXT_UNAVAILABLE");
            return new RuntimeContextNormalization(false, normalized, Map.copyOf(evidence),
                    "无法校验当前 Embed 会话，请确认页面仍在线后重试：" + safeMessage(ex));
        }
        if (resolved == null || !resolved.resolved()) {
            String code = resolved == null ? "PAGE_BRIDGE_CONTEXT_UNAVAILABLE" : resolved.code();
            evidence.put("status", "MISSING");
            evidence.put("code", StringUtils.hasText(code) ? code : "PAGE_BRIDGE_CONTEXT_REQUIRED");
            return new RuntimeContextNormalization(false, normalized, Map.copyOf(evidence),
                    resolved == null || !StringUtils.hasText(resolved.message())
                            ? "当前 Embed 会话不可用，请打开目标业务页面后重试。"
                            : resolved.message());
        }
        if (!StringUtils.hasText(resolved.agentId())
                || !StringUtils.hasText(resolved.projectCode())
                || !StringUtils.hasText(resolved.sessionId())) {
            evidence.put("status", "INVALID");
            evidence.put("code", "PAGE_BRIDGE_CONTEXT_INVALID");
            return new RuntimeContextNormalization(false, normalized, Map.copyOf(evidence),
                    "当前 Embed 会话缺少页面执行所需身份信息，请刷新业务页面后重试。");
        }
        normalized.put("sessionId", resolved.sessionId());
        normalized.put("projectCode", resolved.projectCode());
        normalized.put("agentId", resolved.agentId());
        putIfText(normalized, "pageKey", resolved.currentPageKey());
        putIfText(normalized, "currentPageKey", resolved.currentPageKey());
        putIfText(normalized, "pageInstanceId", resolved.pageInstanceId());
        putIfText(normalized, "route", resolved.route());
        evidence.put("status", "RESOLVED");
        evidence.put("code", defaultText(resolved.code(), "PAGE_BRIDGE_CONTEXT_RESOLVED"));
        evidence.put("sessionId", redactedIdentifier(resolved.sessionId()));
        evidence.put("resolvedKeys", List.of("sessionId", "projectCode", "agentId", "currentPageKey", "pageInstanceId", "route"));
        return new RuntimeContextNormalization(true, normalized, Map.copyOf(evidence), null);
    }

    private boolean requiresPageBridge(RuntimeWorkflowDefinitionEntity workflow) {
        try {
            GraphSpec graph = readGraph(workflow.getGraphSpecJson());
            return graph.getNodes() != null && graph.getNodes().stream()
                    .anyMatch(node -> node != null && "PAGE_ACTION".equals(node.getType()));
        } catch (RuntimeException ignored) {
            // Release validation will report malformed GraphSpec separately. A smoke test must
            // still require a trusted context when it cannot safely classify the graph.
            return true;
        }
    }

    private void mergeContext(Map<String, Object> target, Map<String, Object> source) {
        if (source == null) {
            return;
        }
        source.forEach((key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
                target.put(key, value);
            }
        });
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null && item != null) {
                copy.put(String.valueOf(key), item);
            }
        });
        return copy;
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return "控制面页面会话服务暂不可用";
        }
        return message.length() <= 160 ? message : message.substring(0, 160);
    }

    private String redactedIdentifier(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= 12 ? normalized : normalized.substring(0, 12) + "…";
    }

    private GraphSpec readGraph(String graphSpecJson) {
        if (!StringUtils.hasText(graphSpecJson)) {
            return emptyGraph();
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
                            operation.entryNodeId(),
                            operation.exitNodeIds());
                })
                .toList();
    }

    private GraphSpec emptyGraph() {
        GraphSpec graph = new GraphSpec();
        graph.setSchemaVersion(2);
        graph.setNodes(List.of());
        graph.setEdges(List.of());
        graph.setExitNodeIds(List.of());
        return graph;
    }

    private GraphSpec initialGraph(String workflowKind, GraphSpec requestedGraph) {
        if (requestedGraph != null) {
            return requestedGraph;
        }
        if (WorkflowSemanticValues.KIND_PAGE_ASSISTANT.equals(
                WorkflowSemanticValues.normalizeWorkflowKind(workflowKind))) {
            return RuntimeWorkflowInputContract.pageAssistantStarterGraph();
        }
        return emptyGraph();
    }

    private WorkflowSnapshot snapshot(RuntimeWorkflowDefinitionEntity workflow) {
        return new WorkflowSnapshot(
                workflow.getId(),
                workflow.getKeySlug(),
                workflow.getName(),
                workflow.getDescription(),
                workflow.getProjectId(),
                workflow.getProjectCode(),
                workflow.getWorkflowKind(),
                workflow.getExecutionEngine(),
                workflow.getDefinitionAuthority(),
                workflow.getCreationChannel(),
                workflow.getDefaultModelInstanceId(),
                workflow.getStatus(),
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
        hints.put("executionEngine", workflow.getExecutionEngine());
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

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String nonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private List<String> errorList(String message, String code) {
        String error = nonBlank(message, code);
        return StringUtils.hasText(error) ? List.of(error) : List.of();
    }

    private String normalizeStatus(String status) {
        return WorkflowExecutionStatus.parse(status).name();
    }

    private Map<String, Object> debugStateArtifact(RuntimeWorkflowDebugService.DebugRunResult result) {
        Map<String, Object> stateSnapshot = result.stateSnapshot() == null ? Map.of() : result.stateSnapshot();
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("stateSnapshot", stateSnapshot);
        return artifact;
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
                                 String workflowKind,
                                 String executionEngine,
                                String defaultModelInstanceId,
                                GraphSpec graphSpec,
                                Map<String, Object> canvas,
                                Map<String, Object> extra,
                                List<BindingInput> resourceBindings,
                                String reason) {
        public CreateRequest(String name,
                             String keySlug,
                             Long projectId,
                             String projectCode,
                             String description,
                             String workflowKind,
                             String executionEngine,
                             String defaultModelInstanceId,
                             GraphSpec graphSpec,
                             Map<String, Object> canvas,
                             Map<String, Object> extra,
                             String reason) {
            this(name, keySlug, projectId, projectCode, description, workflowKind,
                    executionEngine, defaultModelInstanceId, graphSpec, canvas, extra,
                    List.of(), reason);
        }
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

    public record ResourceBindingsRequest(
            String baseRevision,
            List<BindingInput> resourceBindings,
            String reason) {
    }

    public record WorkflowSnapshot(String id,
                                   String keySlug,
                                   String name,
                                   String description,
                                   Long projectId,
                                   String projectCode,
                                   String workflowKind,
                                   String executionEngine,
                                   String definitionAuthority,
                                   String creationChannel,
                                   String defaultModelInstanceId,
                                   String status,
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
                                      String entryNodeId,
                                      List<String> exitNodeIds) {

        public enum Op {
            ADD_NODE,
            UPDATE_NODE,
            DELETE_NODE,
            ADD_EDGE,
            UPDATE_EDGE,
            DELETE_EDGE,
            SET_ENTRY_NODE,
            SET_EXIT_NODES,
            SET_INPUT_SCHEMA
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
                               boolean hasUnpublishedChanges,
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
                                           List<PageActionNodeMatch> pageActionNodes,
                                           List<PageActionCatalogEntry> catalogActions,
                                           List<String> warnings) {
    }

    public record PageActionNodeMatch(
            String nodeId,
            String pageKey,
            String actionKey,
            String matchStatus) {
    }

    public record PageAssistantValidateRequest(GraphSpec graphSpec,
                                               Map<String, Object> pageAssistantContext) {
    }

    public record PageAssistantValidateView(String workflowId,
                                            ValidationView validation,
                                            List<String> warnings) {
    }

    private record RuntimeContextNormalization(boolean ready,
                                               Map<String, Object> context,
                                               Map<String, Object> evidence,
                                               String message) {
    }

}

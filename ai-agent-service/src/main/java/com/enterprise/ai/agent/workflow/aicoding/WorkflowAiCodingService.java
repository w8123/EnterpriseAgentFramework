package com.enterprise.ai.agent.workflow.aicoding;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.client.ModelServiceClient;
import com.enterprise.ai.agent.platform.control.governance.GuardDecisionLogService;
import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.platform.control.identity.PageActionRegistryEntity;
import com.enterprise.ai.agent.platform.control.identity.PageActionRegistryMapper;
import com.enterprise.ai.agent.platform.control.auth.PlatformAuthContext;
import com.enterprise.ai.agent.platform.control.auth.PlatformPrincipal;
import com.enterprise.ai.agent.runops.RunOpsService;
import com.enterprise.ai.agent.runtime.GraphRuntimeContext;
import com.enterprise.ai.agent.runtime.host.LangGraph4jRuntimeAdapter;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionService;
import com.enterprise.ai.agent.workflow.AgentWorkflowBindingEntity;
import com.enterprise.ai.agent.workflow.AgentWorkflowBindingService;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionService;
import com.enterprise.ai.agent.workflow.WorkflowReleaseValidationResult;
import com.enterprise.ai.agent.workflow.WorkflowReleaseValidationService;
import com.enterprise.ai.agent.workflow.WorkflowRuntimeGraphAdapter;
import com.enterprise.ai.agent.workflow.WorkflowVersionEntity;
import com.enterprise.ai.agent.workflow.WorkflowVersionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WorkflowAiCodingService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final Set<String> SIDE_EFFECT_NODE_TYPES = Set.of(
            "HTTP_REQUEST",
            "TOOL",
            "CAPABILITY",
            "MCP_CALL",
            "KNOWLEDGE_WRITE");

    private static final int MODEL_SERVICE_SUCCESS_CODE = 200;
    private static final int MAX_TOOL_OPTIONS = 100;
    private static final String MODEL_TYPE_LLM = "LLM";
    private static final String MODEL_STATUS_ACTIVE = "ACTIVE";

    private final WorkflowDefinitionService workflowDefinitionService;
    private final WorkflowVersionService versionService;
    private final WorkflowReleaseValidationService releaseValidationService;
    private final WorkflowGraphPatchService graphPatchService;
    private final WorkflowRuntimeGraphAdapter workflowRuntimeGraphAdapter;
    private final LangGraph4jRuntimeAdapter langGraph4jRuntimeAdapter;
    private final AgentWorkflowBindingService bindingService;
    private final PageActionRegistryMapper pageActionRegistryMapper;
    private final WorkflowAiCodingAuthService aiCodingAuthService;
    private final RunOpsService runOpsService;
    private final ModelServiceClient modelServiceClient;
    private final ToolDefinitionService toolDefinitionService;
    private final GuardDecisionLogService guardDecisionLogService;
    private final ObjectMapper objectMapper;

    @Transactional
    public WorkflowAiCodingContextResponse create(WorkflowAiCodingCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("create request is required");
        }
        if (!StringUtils.hasText(request.getName())) {
            throw new IllegalArgumentException("name is required");
        }
        if (!StringUtils.hasText(request.getKeySlug())) {
            throw new IllegalArgumentException("keySlug is required");
        }
        if (request.getProjectId() == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        if (!StringUtils.hasText(request.getProjectCode())) {
            throw new IllegalArgumentException("projectCode is required");
        }
        aiCodingAuthService.requireAiCodingKeyForProject(request.getProjectId());
        aiCodingAuthService.requireProjectCodeMatches(request.getProjectId(), request.getProjectCode());

        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
        entity.setName(request.getName().trim());
        entity.setKeySlug(request.getKeySlug().trim());
        entity.setProjectId(request.getProjectId());
        entity.setProjectCode(request.getProjectCode().trim());
        if (StringUtils.hasText(request.getDescription())) {
            entity.setDescription(request.getDescription().trim());
        }
        if (StringUtils.hasText(request.getWorkflowType())) {
            entity.setWorkflowType(request.getWorkflowType().trim());
        }
        if (StringUtils.hasText(request.getRuntimeType())) {
            entity.setRuntimeType(request.getRuntimeType().trim());
        }
        if (StringUtils.hasText(request.getDefaultModelInstanceId())) {
            entity.setDefaultModelInstanceId(request.getDefaultModelInstanceId().trim());
        }
        try {
            GraphSpec graphSpec = request.getGraphSpec() == null
                    ? GraphSpec.builder().build()
                    : request.getGraphSpec();
            entity.setGraphSpecJson(objectMapper.writeValueAsString(graphSpec));
            Map<String, Object> canvas = request.getCanvas() == null
                    ? Map.of("version", 2, "nodes", List.of(), "edges", List.of())
                    : request.getCanvas();
            if (graphSpec.getNodes() != null && !graphSpec.getNodes().isEmpty()) {
                canvas = graphPatchService.layoutCanvas(graphSpec, canvas, true);
            }
            entity.setCanvasJson(objectMapper.writeValueAsString(canvas));
            if (request.getExtra() != null && !request.getExtra().isEmpty()) {
                entity.setExtraJson(objectMapper.writeValueAsString(request.getExtra()));
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("failed to serialize initial workflow draft: " + ex.getMessage(), ex);
        }

        WorkflowDefinitionEntity saved = workflowDefinitionService.create(entity);
        auditCreate(saved.getId(), request);
        return getContext(saved.getId());
    }

    public WorkflowAiCodingContextResponse getContext(String workflowId) {
        WorkflowDefinitionEntity workflow = requireAccessibleWorkflow(workflowId);
        GraphReadResult graphRead = readGraphSpec(workflow);
        CanvasReadResult canvasRead = readCanvas(workflow.getCanvasJson());
        WorkflowReleaseValidationResult validation = releaseValidationService.validate(workflow);
        List<String> warnings = buildContextWarnings(workflow, graphRead.graphSpec(), validation);
        warnings.addAll(graphRead.warnings());
        warnings.addAll(canvasRead.warnings());

        return WorkflowAiCodingContextResponse.builder()
                .workflow(toWorkflowInfo(workflow))
                .graphSpec(graphRead.graphSpec())
                .canvas(canvasRead.canvas())
                .validation(validation)
                .nodeTypes(AgentGraphNodeType.catalog())
                .runtimeHints(buildRuntimeHints(workflow, graphRead.graphSpec()))
                .bindings(buildBindingSummaries(bindingService.listByWorkflowId(workflowId)))
                .pageAssistantContext(buildPageAssistantContext(workflow))
                .availableModels(loadAvailableModels(warnings))
                .availableTools(loadAvailableTools(workflow, warnings))
                .warnings(warnings)
                .build();
    }

    public WorkflowAiCodingValidateResponse validate(String workflowId, WorkflowAiCodingValidateRequest request) {
        WorkflowDefinitionEntity workflow = requireAccessibleWorkflow(workflowId);
        WorkflowAiCodingValidateRequest.ValidateMode mode = request == null || request.getMode() == null
                ? WorkflowAiCodingValidateRequest.ValidateMode.CURRENT
                : request.getMode();
        if (mode == WorkflowAiCodingValidateRequest.ValidateMode.CURRENT
                && request != null
                && request.getGraphSpec() != null) {
            throw new IllegalArgumentException("graphSpec requires mode=PROPOSED");
        }

        WorkflowReleaseValidationResult validation;
        if (mode == WorkflowAiCodingValidateRequest.ValidateMode.PROPOSED) {
            GraphSpec proposed = request == null ? null : request.getGraphSpec();
            if (proposed == null) {
                throw new IllegalArgumentException("graphSpec is required for PROPOSED validation");
            }
            validation = releaseValidationService.validateProposed(workflow, proposed);
        } else {
            validation = releaseValidationService.validate(workflow);
        }

        return WorkflowAiCodingValidateResponse.builder()
                .workflowId(workflowId)
                .mode(WorkflowAiCodingValidateResponse.ValidateMode.valueOf(mode.name()))
                .validation(validation)
                .build();
    }

    @Transactional
    public WorkflowAiCodingPatchResponse patch(String workflowId, WorkflowAiCodingPatchRequest request) {
        WorkflowDefinitionEntity workflow = requireAccessibleWorkflow(workflowId);
        if (request != null && StringUtils.hasText(request.getBaseRevision())) {
            String currentRevision = workflow.getUpdatedAt() == null ? "" : workflow.getUpdatedAt().toString();
            if (!Objects.equals(currentRevision, request.getBaseRevision().trim())) {
                throw new IllegalArgumentException("baseRevision mismatch: expected " + currentRevision);
            }
        }

        GraphReadResult graphRead = readGraphSpec(workflow);
        CanvasReadResult canvasRead = readCanvas(workflow.getCanvasJson());
        boolean autoLayout = request == null
                || request.getLayout() == null
                || request.getLayout().isAutoLayout();
        WorkflowGraphPatchService.PatchResult patchResult = graphPatchService.apply(
                graphRead.graphSpec(),
                canvasRead.canvas(),
                request == null ? List.of() : request.getOperations(),
                autoLayout);

        WorkflowReleaseValidationResult validation = releaseValidationService.validateProposed(
                workflow,
                patchResult.getGraphSpec());

        List<String> warnings = new ArrayList<>();
        warnings.addAll(graphRead.warnings());
        warnings.addAll(canvasRead.warnings());
        if (!patchResult.getErrors().isEmpty()) {
            warnings.addAll(patchResult.getErrors());
        }

        boolean dryRun = resolvePatchDryRun(request);
        WorkflowDefinitionEntity saved = workflow;
        if (!dryRun) {
            if (!patchResult.getErrors().isEmpty()) {
                throw new IllegalArgumentException("patch operations failed: " + String.join("; ", patchResult.getErrors()));
            }
            if (!validation.valid()) {
                throw new IllegalArgumentException("patch validation failed: "
                        + formatValidationErrors(validation));
            }
            Map<String, Object> canvasToSave = mergeCanvasPreservingTopLevel(
                    workflow.getCanvasJson(),
                    patchResult.getCanvas());
            saved = saveDraft(workflowId, patchResult.getGraphSpec(), canvasToSave);
            auditPatchSave(workflowId, request, patchResult, saved);
        }

        String summary = buildPatchSummary(request, patchResult);
        return WorkflowAiCodingPatchResponse.builder()
                .dryRun(dryRun)
                .saved(!dryRun)
                .patchSummary(summary)
                .changedNodes(patchResult.getChangedNodes())
                .changedEdges(patchResult.getChangedEdges())
                .proposedGraphSpec(patchResult.getGraphSpec())
                .proposedCanvas(patchResult.getCanvas())
                .validation(validation)
                .workflow(WorkflowAiCodingPatchResponse.WorkflowSnapshot.from(saved))
                .warnings(warnings)
                .errors(patchResult.getErrors())
                .build();
    }

    public WorkflowAiCodingRunResponse run(String workflowId, WorkflowAiCodingRunRequest request) {
        WorkflowDefinitionEntity workflow = requireAccessibleWorkflow(workflowId);
        GraphReadResult graphRead = readGraphSpec(workflow);
        GraphSpec graphSpec = graphRead.graphSpec();
        Map<String, Object> runtimeContext = request == null || request.getRuntimeContext() == null
                ? Map.of()
                : request.getRuntimeContext();

        List<String> warnings = new ArrayList<>(graphRead.warnings());
        List<String> errors = new ArrayList<>();
        boolean hasPageAction = containsPageAction(graphSpec);
        boolean hasBridge = hasPageBridgeContext(runtimeContext);
        if (request != null && request.isDryRun()) {
            warnings.addAll(buildRunDryRunWarnings(hasPageAction, hasBridge, graphSpec, runtimeContext));
            return WorkflowAiCodingRunResponse.builder()
                    .status("DRY_RUN")
                    .warnings(warnings)
                    .metadata(buildRunMetadata(workflow, graphSpec, runtimeContext, true))
                    .build();
        }
        if (hasPageAction && !hasBridge) {
            warnings.add("PAGE_ACTION nodes require embed session / page bridge runtime context; execution skipped");
            return WorkflowAiCodingRunResponse.builder()
                    .status("SKIPPED")
                    .warnings(warnings)
                    .metadata(buildRunMetadata(workflow, graphSpec, runtimeContext, false))
                    .build();
        }
        if (containsSideEffectNode(graphSpec) && !confirmSideEffects(runtimeContext)) {
            warnings.add("Workflow contains side-effect nodes (HTTP/TOOL/CAPABILITY/MCP/KNOWLEDGE_WRITE); "
                    + "set runtimeContext.confirmSideEffects=true to execute");
            return WorkflowAiCodingRunResponse.builder()
                    .status("SKIPPED")
                    .warnings(warnings)
                    .metadata(buildRunMetadata(workflow, graphSpec, runtimeContext, false))
                    .build();
        }
        if (hasPageAction) {
            warnings.add("PAGE_ACTION nodes queue client-side actions; real page execution is not verified here");
        }

        GraphRuntimeContext context = buildRuntimeContext(workflow, runtimeContext);
        Map<String, Object> inputParams = mergeInput(request);
        String message = request == null ? null : request.getMessage();
        Map<String, Object> debugOptions = new LinkedHashMap<>();
        if (runtimeContext.get("traceId") != null) {
            debugOptions.put("traceId", runtimeContext.get("traceId"));
        }
        if (runtimeContext.get("sessionId") != null) {
            debugOptions.put("sessionId", runtimeContext.get("sessionId"));
        }
        if (runtimeContext.get("entryNodeId") != null) {
            debugOptions.put("entryNodeId", runtimeContext.get("entryNodeId"));
        }

        try {
            LangGraph4jRuntimeAdapter.WorkflowDebugRunResult debug = langGraph4jRuntimeAdapter.debugRun(
                    graphSpec,
                    context,
                    message,
                    inputParams,
                    debugOptions);
            auditRun(workflow, debug.getTraceId(), firstText(debug.getStatus(), debug.isSuccess() ? "SUCCESS" : "FAILED"));
            return WorkflowAiCodingRunResponse.builder()
                    .status(firstText(debug.getStatus(), debug.isSuccess() ? "SUCCESS" : "FAILED"))
                    .answer(debug.getAnswer())
                    .traceId(debug.getTraceId())
                    .runId(debug.getRunId())
                    .nodeOutputs(toNodeOutputs(debug))
                    .errors(debug.isSuccess() || !StringUtils.hasText(debug.getErrorMessage())
                            ? List.of()
                            : List.of(firstText(debug.getErrorCode(), "RUN_FAILED") + ": " + debug.getErrorMessage()))
                    .warnings(warnings)
                    .metadata(buildRunMetadata(workflow, graphSpec, runtimeContext, true))
                    .build();
        } catch (Exception ex) {
            auditRun(workflow, null, "FAILED");
            errors.add(ex.getMessage());
            return WorkflowAiCodingRunResponse.builder()
                    .status("FAILED")
                    .errors(errors)
                    .warnings(warnings)
                    .metadata(buildRunMetadata(workflow, graphSpec, runtimeContext, false))
                    .build();
        }
    }

    public WorkflowAiCodingVersionsResponse getVersions(String workflowId) {
        WorkflowDefinitionEntity workflow = requireAccessibleWorkflow(workflowId);
        List<WorkflowVersionEntity> versions = versionService.listVersions(workflowId);
        WorkflowReleaseValidationResult releaseValidation = versionService.validateRelease(workflowId);
        WorkflowVersionEntity activeVersion = versionService.resolveActive(workflowId);

        List<String> warnings = new ArrayList<>();
        boolean draftDirty = isDraftDirty(workflow, activeVersion);
        if (draftDirty) {
            warnings.add("Draft definition differs from the currently published version snapshot");
        }
        if (!releaseValidation.valid()) {
            warnings.add("Release validation currently has errors; workflow is not ready to publish");
        } else if (draftDirty) {
            warnings.add("Release validation passed for current draft; call POST /api/workflows/{workflowId}/ai-coding/publish to publish the draft");
        } else if (activeVersion == null) {
            warnings.add("No published version yet; call POST /api/workflows/{workflowId}/ai-coding/publish after the draft is ready");
        } else {
            warnings.add("Workflow AI Coding can publish a validated draft through POST /api/workflows/{workflowId}/ai-coding/publish");
        }

        return WorkflowAiCodingVersionsResponse.builder()
                .workflowId(workflowId)
                .currentStatus(workflow.getStatus())
                .publishedVersion(WorkflowAiCodingVersionsResponse.fromEntity(activeVersion))
                .versions(versions)
                .releaseValidation(releaseValidation)
                .draftDirty(draftDirty)
                .warnings(warnings)
                .build();
    }

    @Transactional
    public WorkflowAiCodingPublishResponse publish(String workflowId, WorkflowAiCodingPublishRequest request) {
        WorkflowDefinitionEntity workflow = requireAccessibleWorkflow(workflowId);
        WorkflowAiCodingPublishRequest safeRequest = request == null
                ? WorkflowAiCodingPublishRequest.builder().build()
                : request;
        Integer rolloutPercent = safeRequest.getRolloutPercent();
        WorkflowVersionService.WorkflowPublishRequest publishRequest =
                new WorkflowVersionService.WorkflowPublishRequest(
                        safeRequest.getVersion(),
                        rolloutPercent == null ? 100 : rolloutPercent,
                        safeRequest.getNote(),
                        safeRequest.getPublishedBy());
        WorkflowVersionEntity published = versionService.publish(workflow.getId(), publishRequest);
        auditPublish(workflow, safeRequest, published);
        return WorkflowAiCodingPublishResponse.fromEntity(published);
    }

    public WorkflowAiCodingRunListResponse listRuns(String workflowId, Integer limit, Integer days) {
        requireAccessibleWorkflow(workflowId);
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        int safeDays = days == null ? 7 : Math.max(1, Math.min(days, 30));
        PlatformPrincipal principal = PlatformAuthContext.get();
        String userId = principal == null || principal.userId() == null
                ? null
                : String.valueOf(principal.userId());

        List<RunOpsService.RunSummary> runs = runOpsService.recent(userId, safeLimit * 3, safeDays).stream()
                .filter(run -> matchesWorkflow(run, workflowId))
                .limit(safeLimit)
                .toList();

        List<String> warnings = new ArrayList<>();
        if (runs.isEmpty()) {
            warnings.add("No recent debug runs found for this workflow in the last " + safeDays + " day(s)");
        }
        return WorkflowAiCodingRunListResponse.builder()
                .workflowId(workflowId)
                .runs(runs)
                .warnings(warnings)
                .build();
    }

    public WorkflowAiCodingRunDetailResponse getRunDetail(String workflowId, String traceId) {
        requireAccessibleWorkflow(workflowId);
        if (!StringUtils.hasText(traceId)) {
            throw new IllegalArgumentException("traceId is required");
        }
        RunOpsService.RunDetail detail = runOpsService.detail(traceId.trim());
        if (!matchesWorkflow(detail.summary(), workflowId)) {
            throw new IllegalArgumentException("trace not found for workflow: " + traceId.trim());
        }
        return WorkflowAiCodingRunDetailResponse.builder()
                .workflowId(workflowId)
                .traceId(traceId.trim())
                .detail(detail)
                .warnings(List.of())
                .build();
    }

    private List<WorkflowAiCodingContextResponse.ModelOption> loadAvailableModels(List<String> warnings) {
        try {
            ModelServiceClient.ModelInstanceListResult result =
                    modelServiceClient.listModelInstances(MODEL_TYPE_LLM, null, null);
            if (result == null || result.getCode() != MODEL_SERVICE_SUCCESS_CODE || result.getData() == null) {
                warnings.add("model service unavailable; availableModels is empty");
                return List.of();
            }
            List<WorkflowAiCodingContextResponse.ModelOption> options = new ArrayList<>();
            for (ModelServiceClient.ModelInstanceData instance : result.getData()) {
                if (instance == null || !StringUtils.hasText(instance.getId())) {
                    continue;
                }
                if (StringUtils.hasText(instance.getStatus())
                        && !MODEL_STATUS_ACTIVE.equalsIgnoreCase(instance.getStatus().trim())) {
                    continue;
                }
                options.add(WorkflowAiCodingContextResponse.ModelOption.builder()
                        .id(instance.getId().trim())
                        .name(firstText(instance.getName(), instance.getId()))
                        .provider(instance.getProvider())
                        .modelName(instance.getModelName())
                        .modelType(instance.getModelType())
                        .status(instance.getStatus())
                        .build());
            }
            if (options.isEmpty()) {
                warnings.add("no ACTIVE LLM model instances found; configure model instances before adding LLM nodes");
            }
            return options;
        } catch (Exception ex) {
            warnings.add("model service unavailable; availableModels is empty");
            return List.of();
        }
    }

    private List<WorkflowAiCodingContextResponse.ToolOption> loadAvailableTools(WorkflowDefinitionEntity workflow,
                                                                                 List<String> warnings) {
        if (workflow == null || workflow.getProjectId() == null) {
            return List.of();
        }
        try {
            List<ToolDefinitionEntity> tools = toolDefinitionService.listByProjectId(workflow.getProjectId());
            if (tools == null || tools.isEmpty()) {
                return List.of();
            }
            List<WorkflowAiCodingContextResponse.ToolOption> options = new ArrayList<>();
            for (ToolDefinitionEntity tool : tools) {
                if (tool == null || !StringUtils.hasText(tool.getName())) {
                    continue;
                }
                if (Boolean.FALSE.equals(tool.getEnabled())) {
                    continue;
                }
                options.add(toToolOption(tool));
                if (options.size() >= MAX_TOOL_OPTIONS) {
                    break;
                }
            }
            if (tools.size() > MAX_TOOL_OPTIONS) {
                warnings.add("availableTools truncated to " + MAX_TOOL_OPTIONS + " entries");
            }
            return options;
        } catch (Exception ex) {
            warnings.add("tool catalog unavailable; availableTools is empty");
            return List.of();
        }
    }

    private WorkflowAiCodingContextResponse.ToolOption toToolOption(ToolDefinitionEntity tool) {
        return WorkflowAiCodingContextResponse.ToolOption.builder()
                .name(tool.getName().trim())
                .kind(firstText(tool.getKind(), ToolDefinitionService.KIND_TOOL))
                .title(firstText(tool.getName(), tool.getQualifiedName()))
                .description(firstText(tool.getDescription(), tool.getAiDescription()))
                .enabled(tool.getEnabled())
                .qualifiedName(tool.getQualifiedName())
                .build();
    }

    private WorkflowDefinitionEntity saveDraft(String workflowId, GraphSpec graphSpec, Map<String, Object> canvas) {
        try {
            WorkflowDefinitionEntity update = new WorkflowDefinitionEntity();
            update.setGraphSpecJson(objectMapper.writeValueAsString(graphSpec));
            update.setCanvasJson(objectMapper.writeValueAsString(canvas));
            return workflowDefinitionService.update(workflowId, update);
        } catch (Exception ex) {
            throw new IllegalArgumentException("failed to save workflow draft: " + ex.getMessage(), ex);
        }
    }

    private GraphReadResult readGraphSpec(WorkflowDefinitionEntity workflow) {
        List<String> warnings = new ArrayList<>();
        if (!StringUtils.hasText(workflow.getGraphSpecJson())) {
            warnings.add("GraphSpec JSON is empty");
            return new GraphReadResult(GraphSpec.builder().build(), warnings);
        }
        try {
            return new GraphReadResult(
                    workflowRuntimeGraphAdapter.readGraphSpec(workflow.getGraphSpecJson()),
                    warnings);
        } catch (Exception ex) {
            throw new IllegalArgumentException("graphSpecJson is invalid: " + ex.getMessage(), ex);
        }
    }

    private CanvasReadResult readCanvas(String canvasJson) {
        List<String> warnings = new ArrayList<>();
        if (!StringUtils.hasText(canvasJson)) {
            return new CanvasReadResult(new LinkedHashMap<>(), warnings);
        }
        try {
            return new CanvasReadResult(objectMapper.readValue(canvasJson, MAP_TYPE), warnings);
        } catch (Exception ex) {
            warnings.add("canvasJson is invalid and was ignored: " + ex.getMessage());
            return new CanvasReadResult(new LinkedHashMap<>(), warnings);
        }
    }

    private Map<String, Object> mergeCanvasPreservingTopLevel(String existingCanvasJson,
                                                              Map<String, Object> patchedCanvas) {
        CanvasReadResult existing = readCanvas(existingCanvasJson);
        if (existing.canvas().isEmpty()) {
            return patchedCanvas == null ? new LinkedHashMap<>() : new LinkedHashMap<>(patchedCanvas);
        }
        Map<String, Object> merged = new LinkedHashMap<>(existing.canvas());
        if (patchedCanvas != null) {
            if (patchedCanvas.containsKey("nodes")) {
                merged.put("nodes", patchedCanvas.get("nodes"));
            }
            if (patchedCanvas.containsKey("edges")) {
                merged.put("edges", patchedCanvas.get("edges"));
            }
            if (patchedCanvas.containsKey("version")) {
                merged.put("version", patchedCanvas.get("version"));
            }
        }
        return merged;
    }

    private boolean resolvePatchDryRun(WorkflowAiCodingPatchRequest request) {
        if (request == null || request.getDryRun() == null) {
            return true;
        }
        return Boolean.TRUE.equals(request.getDryRun());
    }

    private void auditCreate(String workflowId, WorkflowAiCodingCreateRequest request) {
        PlatformPrincipal principal = PlatformAuthContext.get();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workflowId", workflowId);
        if (request.getProjectId() != null) {
            metadata.put("projectId", request.getProjectId());
        }
        if (StringUtils.hasText(request.getProjectCode())) {
            metadata.put("projectCode", request.getProjectCode().trim());
        }
        if (StringUtils.hasText(request.getKeySlug())) {
            metadata.put("keySlug", request.getKeySlug().trim());
        }
        if (principal != null) {
            metadata.put("userId", principal.userId());
            metadata.put("username", principal.username());
        } else {
            metadata.put("authSource", aiCodingAuthService.auditActorLabel(request.getProjectId()));
        }
        guardDecisionLogService.record(
                null,
                "WORKFLOW_AI_CODING",
                "WORKFLOW",
                workflowId,
                "CREATE",
                request.getReason(),
                metadata);
    }

    private void auditPatchSave(String workflowId,
                                WorkflowAiCodingPatchRequest request,
                                WorkflowGraphPatchService.PatchResult patchResult,
                                WorkflowDefinitionEntity saved) {
        PlatformPrincipal principal = PlatformAuthContext.get();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workflowId", workflowId);
        metadata.put("changedNodes", patchResult.getChangedNodes());
        metadata.put("changedEdges", patchResult.getChangedEdges());
        metadata.put("savedUpdatedAt", saved.getUpdatedAt() == null ? null : saved.getUpdatedAt().toString());
        if (principal != null) {
            metadata.put("userId", principal.userId());
            metadata.put("username", principal.username());
        } else {
            metadata.put("authSource", aiCodingAuthService.auditActorLabel(saved.getProjectId()));
        }
        guardDecisionLogService.record(
                null,
                "WORKFLOW_AI_CODING",
                "WORKFLOW",
                workflowId,
                "PATCH_SAVE",
                request == null ? null : request.getReason(),
                metadata);
    }

    private void auditRun(WorkflowDefinitionEntity workflow, String traceId, String status) {
        PlatformPrincipal principal = PlatformAuthContext.get();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workflowId", workflow.getId());
        metadata.put("projectId", workflow.getProjectId());
        if (StringUtils.hasText(traceId)) {
            metadata.put("traceId", traceId.trim());
        }
        if (StringUtils.hasText(status)) {
            metadata.put("runStatus", status.trim());
        }
        if (principal != null) {
            metadata.put("userId", principal.userId());
            metadata.put("username", principal.username());
        } else {
            metadata.put("authSource", aiCodingAuthService.auditActorLabel(workflow.getProjectId()));
        }
        guardDecisionLogService.record(
                StringUtils.hasText(traceId) ? traceId.trim() : null,
                "WORKFLOW_AI_CODING",
                "WORKFLOW",
                workflow.getId(),
                "DEBUG_RUN",
                null,
                metadata);
    }

    private void auditPublish(WorkflowDefinitionEntity workflow,
                              WorkflowAiCodingPublishRequest request,
                              WorkflowVersionEntity published) {
        PlatformPrincipal principal = PlatformAuthContext.get();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workflowId", workflow.getId());
        metadata.put("projectId", workflow.getProjectId());
        metadata.put("projectCode", workflow.getProjectCode());
        if (published != null) {
            metadata.put("versionId", published.getId());
            metadata.put("version", published.getVersion());
            metadata.put("status", published.getStatus());
            metadata.put("rolloutPercent", published.getRolloutPercent());
        }
        if (principal != null) {
            metadata.put("userId", principal.userId());
            metadata.put("username", principal.username());
        } else {
            metadata.put("authSource", aiCodingAuthService.auditActorLabel(workflow.getProjectId()));
        }
        guardDecisionLogService.record(
                null,
                "WORKFLOW_AI_CODING",
                "WORKFLOW",
                workflow.getId(),
                "PUBLISH",
                request == null ? null : request.getNote(),
                metadata);
    }

    private String formatValidationErrors(WorkflowReleaseValidationResult validation) {
        if (validation == null || validation.errors() == null || validation.errors().isEmpty()) {
            return "unknown validation error";
        }
        List<String> messages = new ArrayList<>();
        for (WorkflowReleaseValidationResult.Item item : validation.errors()) {
            messages.add(item.code() + ": " + item.message());
        }
        return String.join("; ", messages);
    }

    private boolean containsSideEffectNode(GraphSpec graphSpec) {
        if (graphSpec == null || graphSpec.getNodes() == null) {
            return false;
        }
        for (GraphSpec.Node node : graphSpec.getNodes()) {
            if (node == null) {
                continue;
            }
            String type = AgentGraphNodeType.normalize(node.getType());
            if (SIDE_EFFECT_NODE_TYPES.contains(type)) {
                return true;
            }
        }
        return false;
    }

    private boolean confirmSideEffects(Map<String, Object> runtimeContext) {
        if (runtimeContext == null || runtimeContext.isEmpty()) {
            return false;
        }
        Object raw = runtimeContext.get("confirmSideEffects");
        return Boolean.TRUE.equals(raw);
    }

    private record GraphReadResult(GraphSpec graphSpec, List<String> warnings) {
    }

    private record CanvasReadResult(Map<String, Object> canvas, List<String> warnings) {
    }

    private WorkflowAiCodingContextResponse.WorkflowInfo toWorkflowInfo(WorkflowDefinitionEntity workflow) {
        return WorkflowAiCodingContextResponse.WorkflowInfo.builder()
                .id(workflow.getId())
                .name(workflow.getName())
                .keySlug(workflow.getKeySlug())
                .projectId(workflow.getProjectId())
                .projectCode(workflow.getProjectCode())
                .workflowType(workflow.getWorkflowType())
                .runtimeType(workflow.getRuntimeType())
                .status(workflow.getStatus())
                .defaultModelInstanceId(workflow.getDefaultModelInstanceId())
                .updatedAt(workflow.getUpdatedAt() == null ? null : workflow.getUpdatedAt().toString())
                .build();
    }

    private WorkflowAiCodingContextResponse.RuntimeHints buildRuntimeHints(WorkflowDefinitionEntity workflow,
                                                                           GraphSpec graphSpec) {
        List<String> notes = new ArrayList<>();
        String runtimeType = firstText(workflow.getRuntimeType(), "LANGGRAPH4J");
        notes.add("GraphSpec is the runtime source of truth; canvas_json is layout only.");
        notes.add("Workflow AI Coding updates draft definition via platform API, not direct DB access.");
        if (containsPageAction(graphSpec)) {
            notes.add("PAGE_ACTION nodes require page bridge / embed session for real execution.");
        }
        return WorkflowAiCodingContextResponse.RuntimeHints.builder()
                .runtimeType(runtimeType)
                .debugSupported("LANGGRAPH4J".equalsIgnoreCase(runtimeType))
                .pageActionRequiresBridge(containsPageAction(graphSpec))
                .notes(notes)
                .build();
    }

    private List<WorkflowAiCodingContextResponse.BindingSummary> buildBindingSummaries(
            List<AgentWorkflowBindingEntity> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        List<WorkflowAiCodingContextResponse.BindingSummary> summaries = new ArrayList<>();
        for (AgentWorkflowBindingEntity binding : bindings) {
            summaries.add(WorkflowAiCodingContextResponse.BindingSummary.builder()
                    .bindingId(binding.getId())
                    .agentId(binding.getAgentId())
                    .bindingType(binding.getBindingType())
                    .pageKey(binding.getPageKey())
                    .routePattern(binding.getRoutePattern())
                    .actionKey(binding.getActionKey())
                    .enabled(binding.getEnabled())
                    .build());
        }
        return summaries;
    }

    private WorkflowAiCodingContextResponse.PageAssistantContext buildPageAssistantContext(
            WorkflowDefinitionEntity workflow) {
        if (!"PAGE_ASSISTANT".equalsIgnoreCase(String.valueOf(workflow.getWorkflowType()))) {
            return null;
        }
        Map<String, Object> extra = readExtraJson(workflow.getExtraJson());
        Map<String, Object> pageAssistantExtra = pageAssistantExtra(extra);
        String pageKey = firstText(text(extra.get("pageKey")), text(pageAssistantExtra.get("pageKey")));
        String routePattern = firstText(text(extra.get("routePattern")), text(pageAssistantExtra.get("routePattern")));
        List<String> actionKeys = toStringList(extra.get("actionKeys"));
        if (actionKeys.isEmpty()) {
            actionKeys = toStringList(pageAssistantExtra.get("actionKeys"));
        }

        List<AgentWorkflowBindingEntity> bindings = bindingService.listByWorkflowId(workflow.getId());
        for (AgentWorkflowBindingEntity binding : bindings) {
            if (!StringUtils.hasText(pageKey) && StringUtils.hasText(binding.getPageKey())) {
                pageKey = binding.getPageKey();
            }
            if (!StringUtils.hasText(routePattern) && StringUtils.hasText(binding.getRoutePattern())) {
                routePattern = binding.getRoutePattern();
            }
            if (StringUtils.hasText(binding.getActionKey()) && !actionKeys.contains(binding.getActionKey())) {
                actionKeys = new ArrayList<>(actionKeys);
                actionKeys.add(binding.getActionKey());
            }
        }

        List<WorkflowAiCodingContextResponse.PageActionCatalogItem> catalog = List.of();
        if (StringUtils.hasText(workflow.getProjectCode()) && StringUtils.hasText(pageKey)) {
            catalog = loadPageActionCatalog(workflow.getProjectCode(), pageKey);
        }

        return WorkflowAiCodingContextResponse.PageAssistantContext.builder()
                .pageKey(pageKey)
                .routePattern(routePattern)
                .actionKeys(actionKeys)
                .pageActionCatalog(catalog)
                .build();
    }

    private List<WorkflowAiCodingContextResponse.PageActionCatalogItem> loadPageActionCatalog(String projectCode,
                                                                                              String pageKey) {
        List<PageActionRegistryEntity> actions = pageActionRegistryMapper.selectList(
                new LambdaQueryWrapper<PageActionRegistryEntity>()
                        .eq(PageActionRegistryEntity::getProjectCode, projectCode)
                        .eq(PageActionRegistryEntity::getPageKey, pageKey)
                        .orderByAsc(PageActionRegistryEntity::getActionKey));
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        List<WorkflowAiCodingContextResponse.PageActionCatalogItem> catalog = new ArrayList<>();
        for (PageActionRegistryEntity action : actions) {
            catalog.add(WorkflowAiCodingContextResponse.PageActionCatalogItem.builder()
                    .actionKey(action.getActionKey())
                    .title(action.getTitle())
                    .status(action.getStatus())
                    .build());
        }
        return catalog;
    }

    private List<String> buildContextWarnings(WorkflowDefinitionEntity workflow,
                                              GraphSpec graphSpec,
                                              WorkflowReleaseValidationResult validation) {
        List<String> warnings = new ArrayList<>();
        if (!validation.valid()) {
            warnings.add("Workflow release validation currently has errors");
        }
        if (graphSpec == null || graphSpec.getNodes() == null || graphSpec.getNodes().isEmpty()) {
            warnings.add("GraphSpec has no nodes");
        }
        if ("PUBLISHED".equalsIgnoreCase(String.valueOf(workflow.getStatus()))) {
            warnings.add("Workflow status is PUBLISHED; AI Coding patch only updates draft definition");
        }
        return warnings;
    }

    private List<String> buildRunDryRunWarnings(boolean hasPageAction,
                                               boolean hasBridge,
                                               GraphSpec graphSpec,
                                               Map<String, Object> runtimeContext) {
        List<String> warnings = new ArrayList<>();
        warnings.add("dryRun=true: no runtime execution performed");
        if (hasPageAction && !hasBridge) {
            warnings.add("PAGE_ACTION nodes would require page bridge runtime context for real execution");
        }
        if (containsSideEffectNode(graphSpec) && !confirmSideEffects(runtimeContext)) {
            warnings.add("Side-effect nodes would require runtimeContext.confirmSideEffects=true to execute");
        }
        return warnings;
    }

    private Map<String, Object> buildRunMetadata(WorkflowDefinitionEntity workflow,
                                                 GraphSpec graphSpec,
                                                 Map<String, Object> runtimeContext,
                                                 boolean executable) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workflowId", workflow.getId());
        metadata.put("workflowKeySlug", workflow.getKeySlug());
        metadata.put("workflowType", workflow.getWorkflowType());
        metadata.put("runtimeType", workflow.getRuntimeType());
        metadata.put("executable", executable);
        metadata.put("pageActionNodeCount", countPageActionNodes(graphSpec));
        metadata.put("sideEffectNodeCount", countSideEffectNodes(graphSpec));
        metadata.put("runtimeContextKeys", runtimeContext.keySet());
        return metadata;
    }

    private GraphRuntimeContext buildRuntimeContext(WorkflowDefinitionEntity workflow,
                                                    Map<String, Object> runtimeContext) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("workflowAiCoding", true);
        extra.putAll(runtimeContext);
        return GraphRuntimeContext.builder()
                .sourceType("WORKFLOW_DRAFT")
                .sourceId(workflow.getId())
                .sourceKeySlug(workflow.getKeySlug())
                .name(workflow.getName())
                .intentType(firstText(workflow.getWorkflowType(), "WORKFLOW"))
                .projectId(workflow.getProjectId())
                .projectCode(workflow.getProjectCode())
                .runtimeType(firstText(workflow.getRuntimeType(), "LANGGRAPH4J"))
                .runtimePlacement("CENTRAL")
                .modelInstanceId(workflow.getDefaultModelInstanceId())
                .canvasJson(workflow.getCanvasJson())
                .extra(extra)
                .build();
    }

    private Map<String, Object> mergeInput(WorkflowAiCodingRunRequest request) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (request != null && request.getInput() != null) {
            input.putAll(request.getInput());
        }
        if (request != null && StringUtils.hasText(request.getMessage()) && !input.containsKey("message")) {
            input.put("message", request.getMessage());
        }
        return input;
    }

    private List<Map<String, Object>> toNodeOutputs(LangGraph4jRuntimeAdapter.WorkflowDebugRunResult debug) {
        if (debug.getSteps() == null || debug.getSteps().isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> outputs = new ArrayList<>();
        for (LangGraph4jRuntimeAdapter.WorkflowDebugStepResult step : debug.getSteps()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeId", step.getNodeId());
            item.put("nodeType", step.getNodeType());
            item.put("nodeName", step.getNodeName());
            item.put("status", step.getStatus());
            item.put("output", step.getOutput());
            item.put("rawOutput", step.getRawOutput());
            item.put("errorCode", step.getErrorCode());
            item.put("errorMessage", step.getErrorMessage());
            outputs.add(item);
        }
        return outputs;
    }

    private boolean containsPageAction(GraphSpec graphSpec) {
        return countPageActionNodes(graphSpec) > 0;
    }

    private int countPageActionNodes(GraphSpec graphSpec) {
        if (graphSpec == null || graphSpec.getNodes() == null) {
            return 0;
        }
        int count = 0;
        for (GraphSpec.Node node : graphSpec.getNodes()) {
            if (node != null && "PAGE_ACTION".equals(AgentGraphNodeType.normalize(node.getType()))) {
                count++;
            }
        }
        return count;
    }

    private int countSideEffectNodes(GraphSpec graphSpec) {
        if (graphSpec == null || graphSpec.getNodes() == null) {
            return 0;
        }
        int count = 0;
        for (GraphSpec.Node node : graphSpec.getNodes()) {
            if (node == null) {
                continue;
            }
            if (SIDE_EFFECT_NODE_TYPES.contains(AgentGraphNodeType.normalize(node.getType()))) {
                count++;
            }
        }
        return count;
    }

    private boolean hasPageBridgeContext(Map<String, Object> runtimeContext) {
        if (runtimeContext == null || runtimeContext.isEmpty()) {
            return false;
        }
        if (hasNonBlankValue(runtimeContext.get("embedSessionId"))) {
            return true;
        }
        if (runtimeContext.get("pageBridge") instanceof Map<?, ?> bridge && !bridge.isEmpty()) {
            return true;
        }
        if (runtimeContext.get("pageContext") instanceof Map<?, ?> pageContext && !pageContext.isEmpty()) {
            return true;
        }
        return hasNonBlankValue(runtimeContext.get("bridgeGlobal"));
    }

    private boolean hasNonBlankValue(Object value) {
        return value != null && StringUtils.hasText(String.valueOf(value));
    }

    private Map<String, Object> readExtraJson(String extraJson) {
        if (!StringUtils.hasText(extraJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(extraJson, MAP_TYPE);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private Map<String, Object> pageAssistantExtra(Map<String, Object> extra) {
        if (extra == null || !(extra.get("pageAssistant") instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        return objectMapper.convertValue(raw, MAP_TYPE);
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            if (item != null && StringUtils.hasText(String.valueOf(item))) {
                values.add(String.valueOf(item).trim());
            }
        }
        return values;
    }

    private String buildPatchSummary(WorkflowAiCodingPatchRequest request,
                                       WorkflowGraphPatchService.PatchResult patchResult) {
        int opCount = request == null || request.getOperations() == null ? 0 : request.getOperations().size();
        String reason = request == null ? null : request.getReason();
        String base = "Applied " + opCount + " operation(s); changed "
                + patchResult.getChangedNodes().size() + " node(s), "
                + patchResult.getChangedEdges().size() + " edge(s)";
        return StringUtils.hasText(reason) ? base + ": " + reason.trim() : base;
    }

    private boolean isDraftDirty(WorkflowDefinitionEntity workflow, WorkflowVersionEntity activeVersion) {
        if (activeVersion == null) {
            return StringUtils.hasText(workflow.getGraphSpecJson()) || StringUtils.hasText(workflow.getCanvasJson());
        }
        return !Objects.equals(normalizeJsonText(workflow.getGraphSpecJson()),
                normalizeJsonText(activeVersion.getGraphSpecSnapshotJson()))
                || !Objects.equals(normalizeJsonText(workflow.getCanvasJson()),
                normalizeJsonText(activeVersion.getCanvasSnapshotJson()));
    }

    private String normalizeJsonText(String json) {
        return json == null ? "" : json.trim();
    }

    private boolean matchesWorkflow(RunOpsService.RunSummary run, String workflowId) {
        if (run == null || !StringUtils.hasText(workflowId)) {
            return false;
        }
        if (workflowId.equals(run.workflowId())) {
            return true;
        }
        if (workflowId.equals(run.sourceId()) && isWorkflowSourceType(run.sourceType())) {
            return true;
        }
        return false;
    }

    private boolean isWorkflowSourceType(String sourceType) {
        return StringUtils.hasText(sourceType) && sourceType.trim().toUpperCase().startsWith("WORKFLOW");
    }

    private WorkflowDefinitionEntity requireAccessibleWorkflow(String workflowId) {
        WorkflowDefinitionEntity workflow = requireWorkflow(workflowId);
        aiCodingAuthService.requireAiCodingKeyForWorkflow(workflow);
        return workflow;
    }

    private WorkflowDefinitionEntity requireWorkflow(String workflowId) {
        if (!StringUtils.hasText(workflowId)) {
            throw new IllegalArgumentException("workflowId is required");
        }
        return workflowDefinitionService.findById(workflowId.trim())
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstText(String first, String fallback) {
        return StringUtils.hasText(first) ? first.trim() : fallback;
    }
}

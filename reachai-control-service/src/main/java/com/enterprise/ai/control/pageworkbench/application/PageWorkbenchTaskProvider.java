package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactApplyResult;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactEnvelope;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskContract;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskRequiredResource;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetView;
import com.enterprise.ai.control.aicoding.domain.AiCodingDeliveryEvidence.ReportedCheck;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskKindProvider;
import com.enterprise.ai.control.aicoding.provider.AiCodingArtifactApplicationUnconfirmedException;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.AcceptancePayload;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ImplementationPayload;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageAnalysisPayload;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PreReleasePayload;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowDraftInput;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowEngineeringDraftView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowEngineeringPayload;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchWorkflowTraceReadinessApplicationService.WorkflowAcceptanceTarget;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PageWorkbenchTaskProvider implements AiCodingTaskKindProvider {

    static final String PAGE_ANALYSIS = "PAGE_READONLY_ANALYSIS";
    static final String WORKFLOW_ENGINEERING = "WORKFLOW_ENGINEERING";
    static final String CODE_IMPLEMENTATION = "CODE_IMPLEMENTATION";
    static final String BROWSER_ACCEPTANCE = "BROWSER_ACCEPTANCE";
    static final String PRE_RELEASE_CHECK = "PRE_RELEASE_CHECK";

    private final String taskKind;
    private final TaskContract contract;
    private final PageCatalogApplicationService pageCatalog;
    private final PageAnalysisApplicationService pageAnalysis;
    private final PageWorkbenchProjectPort capabilityClient;
    private final PageWorkbenchModelPort modelCatalogClient;
    private final PageWorkbenchRuntimePort runtimeClient;
    private final PageWorkbenchBrowserReadinessApplicationService browserReadiness;
    private final PageWorkbenchAgentModelReadinessApplicationService modelReadiness;
    private final PageWorkbenchWorkflowTraceReadinessApplicationService
            workflowTraceReadiness;
    private final PageWorkbenchReleaseReadinessApplicationService releaseReadiness;
    private final ObjectMapper objectMapper;

    PageWorkbenchTaskProvider(
            String taskKind,
            String accessMode,
            String contractKey,
            JsonNode jsonSchema,
            JsonNode example,
            PageCatalogApplicationService pageCatalog,
            PageAnalysisApplicationService pageAnalysis,
            PageWorkbenchProjectPort capabilityClient,
            PageWorkbenchModelPort modelCatalogClient,
            PageWorkbenchRuntimePort runtimeClient,
            PageWorkbenchBrowserReadinessApplicationService browserReadiness,
            PageWorkbenchAgentModelReadinessApplicationService modelReadiness,
            PageWorkbenchWorkflowTraceReadinessApplicationService
                    workflowTraceReadiness,
            PageWorkbenchReleaseReadinessApplicationService releaseReadiness,
            ObjectMapper objectMapper) {
        this.taskKind = taskKind;
        this.contract = new TaskContract(
                "BUSINESS_PAGE_WORKBENCH",
                taskKind,
                accessMode,
                "PAGE",
                contractKey,
                "v1",
                jsonSchema,
                example);
        this.pageCatalog = pageCatalog;
        this.pageAnalysis = pageAnalysis;
        this.capabilityClient = capabilityClient;
        this.modelCatalogClient = modelCatalogClient;
        this.runtimeClient = runtimeClient;
        this.browserReadiness = browserReadiness;
        this.modelReadiness = modelReadiness;
        this.workflowTraceReadiness = workflowTraceReadiness;
        this.releaseReadiness = releaseReadiness;
        this.objectMapper = objectMapper;
    }

    @Override
    public String kind() {
        return taskKind;
    }

    @Override
    public TaskContract contract() {
        return contract;
    }

    @Override
    public JsonNode buildContext(TaskDescriptor task) {
        String pageKey = primaryPageKey(task);
        PageView page = pageCatalog.findPageByKey(task.projectCode(), pageKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "page task target not found: " + pageKey));
        Map<String, Object> source = capabilityClient.getOnboardingProjectById(
                task.projectId());
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema", "reachai.ai-coding.page-task-context.v1");
        root.set("project", safeProject(source, task));
        root.set("page", objectMapper.valueToTree(page));
        root.set(
                "registeredCapabilities",
                objectMapper.valueToTree(defaultList(
                        capabilityClient.listProjectTools(task.projectId()))));
        if (WORKFLOW_ENGINEERING.equals(taskKind)) {
            try {
                root.set(
                        "existingPageWorkflows",
                        objectMapper.valueToTree(defaultList(
                                runtimeClient.pageWorkbenchPublished(
                                        task.projectCode(),
                                        pageKey).getBody())));
            } catch (RuntimeException ex) {
                root.putArray("existingPageWorkflows");
                root.putObject("existingPageWorkflowsWarning")
                        .put("code", "PUBLISHED_WORKFLOW_CATALOG_UNAVAILABLE")
                        .put("message", "当前页面在线 Workflow 目录暂时不可用；不要猜测 replaceWorkflowId。");
            }
        }
        WorkflowAcceptanceTarget workflowTarget = null;
        if (BROWSER_ACCEPTANCE.equals(taskKind)) {
            workflowTarget = workflowTraceReadiness.requirePublishedTarget(
                    task.projectCode(),
                    pageKey,
                    workflowAcceptanceTarget(task));
            if (workflowTarget != null) {
                root.set(
                        "workflowAcceptanceTarget",
                        objectMapper.valueToTree(workflowTarget));
                ReadinessItem modelPreflight = modelReadiness.evaluate(
                        workflowTarget.modelInstanceId());
                root.set(
                        "modelPreflight",
                        objectMapper.valueToTree(modelPreflight));
                if (!"PASS".equalsIgnoreCase(modelPreflight.status())) {
                    throw new IllegalArgumentException(
                            "无法开始真实验收：" + modelPreflight.message());
                }
            }
        }

        ObjectNode scope = root.putObject("scope");
        scope.put("accessMode", contract.accessMode());
        scope.putArray("includedPageKeys").add(pageKey);
        scope.putArray("excludedRules")
                .add("Do not inspect or modify unrelated pages or modules.")
                .add("Do not invent business rules, permissions, APIs or acceptance evidence.")
                .add("Do not expose credentials, activation codes or task tokens.");
        var requiredChecks = scope.putArray("requiredChecks");
        requiredChecks.add(
                "Use concise Simplified Chinese for every human-readable title, description, summary, fact, inference, question, scope, implementation reference and acceptance criterion. Keep technical identifiers, code, paths, API methods and protocol fields unchanged.");
        if (PAGE_ANALYSIS.equals(taskKind)) {
            requiredChecks.add("Read-only analysis only; do not modify repository files.")
                    .add("Return at most three findings.")
                    .add("Separate confirmed facts, technical inference and open questions.");
        } else if (WORKFLOW_ENGINEERING.equals(taskKind)) {
            requiredChecks
                    .add("Read only the current page, its registered actions, directly related code and registered capabilities.")
                    .add("Do not modify the business repository for this task.")
                    .add("Use only action keys and capabilities present in this context; do not invent contracts.")
                    .add("Return an executable PAGE_ASSISTANT GraphSpec proposal and the exact referenced files.")
                    .add("For every structured read branch, add a downstream INTERACTION node with interactionType=PRESENT_OUTPUT. Use list_card for list/page results and output_card/card/detail for one object; set an explicit nodeOutput.<producerNodeId> dataExpression and presentation.mode=card_only or text_and_card.")
                    .add("INTERACTION authoring is restricted to workflowAuthoring.nodeTypes[].enabledVariants. Do not create COLLECT_INPUT, USER_CHOICE, CONFIRM_ACTION, REVIEW_EDIT or any other pause/resume interaction variant while it is absent from enabledVariants.")
                    .add("Read existingPageWorkflows before deciding replacement. Set replaceWorkflowId only when this draft intentionally supersedes that exact Workflow; otherwise omit it so multiple legitimate Workflows can coexist on the same page.")
                    .add("Submitting the artifact may create a DRAFT only; publishing and Agent attachment require explicit ReachAI approval.")
                    .add("Read the workflow-ai-coding Skill from /api/ai-assist/skills/workflow-ai-coding/latest.zip before authoring GraphSpec.")
                    .add("Submit the result through the current task artifact endpoint.");
        } else {
            requiredChecks.add("Run focused tests for changed code.")
                    .add("Use real browser verification when the task requires UI behavior.")
                    .add("Submit the result through the current task artifact endpoint.");
            if (BROWSER_ACCEPTANCE.equals(taskKind)
                    && workflowTarget != null) {
                requiredChecks
                        .add("Return the exact Runtime traceId produced by the accepted browser scenario when available.")
                        .add("When the target Workflow contains INTERACTION/PRESENT_OUTPUT, verify the real SSE contains ui.requested and verify an actual list_card/output_card/card/detail DOM is visible with the business result; report both facts in structuredPresentation.")
                        .add("If the current page exposes an ACTIVE write action, exercise at least one confirmation-gated write through the real assistant, verify the persisted result, and restore the original business state before submitting the artifact.")
                        .add("Record whether state restoration was required, whether it passed, and concrete evidence in stateRestoration. A report with an un-restored mutation cannot pass.");
            }
        }
        root.putObject("instructions")
                .put("objective", task.objective())
                .put("taskKind", taskKind)
                .put("humanLanguage",
                        "Human-readable output uses Simplified Chinese; technical identities remain unchanged.")
                .put("completionBoundary",
                        "Submitting an artifact records AI Coding completion; ReachAI applies and accepts it separately.");
        return root;
    }

    @Override
    public JsonNode materializeContext(
            TaskDescriptor task,
            JsonNode contextSnapshot,
            String publicBaseUrl) {
        if (!WORKFLOW_ENGINEERING.equals(taskKind)) {
            return contextSnapshot;
        }
        ObjectNode root = contextSnapshot != null && contextSnapshot.isObject()
                ? ((ObjectNode) contextSnapshot).deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode authoring = root.putObject("workflowAuthoring");
        String platformRoot = publicBaseUrl == null
                ? ""
                : publicBaseUrl.replaceAll("/+$", "");
        authoring.put(
                "skillPackageUrl",
                platformRoot
                        + "/api/ai-assist/skills/workflow-ai-coding/latest.zip");
        var warnings = authoring.putArray("warnings");
        try {
            List<Map<String, Object>> models = activeLlmModels(
                    modelCatalogClient.list(null, "LLM", null));
            authoring.set("availableModels", objectMapper.valueToTree(models));
            if (models.isEmpty()) {
                warnings.addObject()
                        .put("code", "NO_ACTIVE_LLM")
                        .put("message", "当前没有可用于 Workflow 自然语言分类或参数提取的 ACTIVE LLM 模型实例。");
            }
        } catch (RuntimeException ex) {
            authoring.putArray("availableModels");
            warnings.addObject()
                    .put("code", "MODEL_CATALOG_UNAVAILABLE")
                    .put("message", "模型目录暂时不可用；不要猜测 modelInstanceId。");
        }
        try {
            List<Map<String, Object>> nodeTypes = mapList(
                    runtimeClient.pageWorkbenchWorkflowNodeTypes());
            authoring.set("nodeTypes", objectMapper.valueToTree(nodeTypes));
            if (nodeTypes.isEmpty()) {
                warnings.addObject()
                        .put("code", "NODE_CATALOG_UNAVAILABLE")
                        .put("message", "Workflow 节点目录为空；不要猜测节点类型或可发布状态。");
            }
        } catch (RuntimeException ex) {
            authoring.putArray("nodeTypes");
            warnings.addObject()
                    .put("code", "NODE_CATALOG_UNAVAILABLE")
                    .put("message", "Workflow 节点目录暂时不可用；不要猜测节点类型或可发布状态。");
        }
        authoring.putObject("structuredPresentationContract")
                .put("interactionType", "PRESENT_OUTPUT")
                .put("listComponent", "list_card")
                .put("detailComponent", "output_card")
                .put("dataExpression", "nodeOutput.<producerNodeId>")
                .put("presentationMode", "card_only")
                .put("browserEvidence", "ui.requested + rendered card DOM");
        return root;
    }

    @Override
    public List<TaskRequiredResource> requiredResources(
            TaskDescriptor task,
            String publicBaseUrl) {
        if (!WORKFLOW_ENGINEERING.equals(taskKind)) {
            return List.of();
        }
        String platformRoot = publicBaseUrl == null
                ? ""
                : publicBaseUrl.replaceAll("/+$", "");
        return List.of(new TaskRequiredResource(
                "workflow-ai-coding-skill",
                "ReachAI Workflow AI Coding Skill",
                "SKILL_ZIP",
                platformRoot
                        + "/api/ai-assist/skills/workflow-ai-coding/latest.zip",
                "workflow-ai-coding/SKILL.md",
                "包含 GraphSpec、PAGE_ASSISTANT、Workflow AI Coding API、验证和安全边界。",
                true));
    }

    @Override
    public List<ReadinessItem> readiness(TaskDescriptor task) {
        if (BROWSER_ACCEPTANCE.equals(taskKind)) {
            return browserReadiness(
                    task,
                    null);
        }
        if (PRE_RELEASE_CHECK.equals(taskKind)) {
            return List.of(releaseReadiness.waitingForArtifact());
        }
        return List.of();
    }

    @Override
    public List<ReadinessItem> readiness(
            TaskDescriptor task,
            JsonNode applicationResult) {
        if (BROWSER_ACCEPTANCE.equals(taskKind)) {
            JsonNode reportNode = applicationResult == null
                    ? null
                    : applicationResult.get("browserAcceptance");
            String reportedTraceId = reportNode == null
                    ? null
                    : reportNode.path("traceId").asText(null);
            return browserReadiness(task, reportedTraceId);
        }
        if (!PRE_RELEASE_CHECK.equals(taskKind)) {
            return readiness(task);
        }
        JsonNode reportNode = applicationResult == null
                ? null
                : applicationResult.get("preRelease");
        if (reportNode == null || reportNode.isNull()) {
            return readiness(task);
        }
        PreReleasePayload report = parse(
                reportNode,
                PreReleasePayload.class,
                "pre-release");
        return List.of(releaseReadiness.evaluate(
                task.projectCode(),
                primaryPageKey(task),
                report.workflowId(),
                report.workflowVersion()));
    }

    @Override
    public List<String> acceptanceReadinessKeys() {
        return switch (taskKind) {
            case BROWSER_ACCEPTANCE -> List.of(
                    PageWorkbenchAgentModelReadinessApplicationService.KEY,
                    PageWorkbenchBrowserReadinessApplicationService.KEY,
                    PageWorkbenchWorkflowTraceReadinessApplicationService.KEY);
            case PRE_RELEASE_CHECK -> List.of(
                    PageWorkbenchReleaseReadinessApplicationService.KEY);
            default -> List.of();
        };
    }

    @Override
    public ArtifactApplyResult applyArtifact(
            TaskDescriptor task,
            ArtifactEnvelope artifact) {
        return switch (taskKind) {
            case PAGE_ANALYSIS -> applyAnalysis(task, artifact.content());
            case WORKFLOW_ENGINEERING ->
                    applyWorkflowEngineering(task, artifact.content());
            case CODE_IMPLEMENTATION -> applyImplementation(artifact.content());
            case BROWSER_ACCEPTANCE -> applyBrowserAcceptance(artifact.content());
            case PRE_RELEASE_CHECK -> applyPreRelease(artifact.content());
            default -> throw new IllegalStateException(
                    "unsupported page task provider kind: " + taskKind);
        };
    }

    private ArtifactApplyResult applyWorkflowEngineering(
            TaskDescriptor task,
            JsonNode content) {
        WorkflowEngineeringPayload report = parse(
                content,
                WorkflowEngineeringPayload.class,
                "workflow-engineering");
        String targetPageKey = primaryPageKey(task);
        if (report == null
                || !targetPageKey.equals(
                requireText(report.pageKey(), "pageKey"))) {
            throw new IllegalArgumentException(
                    "workflow-engineering pageKey must equal the primary PAGE target");
        }
        if (!StringUtils.hasText(report.summary())) {
            throw new IllegalArgumentException(
                    "workflow-engineering summary is required");
        }
        WorkflowDraftInput workflow = report.workflow();
        if (workflow == null
                || !StringUtils.hasText(workflow.name())
                || !StringUtils.hasText(workflow.keySlug())
                || workflow.graphSpec() == null
                || workflow.graphSpec().isNull()) {
            throw new IllegalArgumentException(
                    "workflow-engineering requires workflow name, keySlug and graphSpec");
        }
        List<String> actionKeys = requiredTextList(
                report.selectedActionKeys(),
                "selectedActionKeys");
        requiredTextList(report.referencedFiles(), "referencedFiles");
        requiredTextList(report.acceptanceCriteria(), "acceptanceCriteria");
        String replaceWorkflowId = textOrNull(report.replaceWorkflowId());
        if (replaceWorkflowId != null) {
            List<PageWorkbenchContract.PublishedWorkflowView> existing =
                    defaultList(runtimeClient.pageWorkbenchPublished(
                            task.projectCode(), targetPageKey).getBody());
            boolean known = existing.stream().anyMatch(workflowView ->
                    replaceWorkflowId.equals(workflowView.workflowId()));
            if (!known) {
                throw new IllegalArgumentException(
                        "replaceWorkflowId must reference a currently attached Workflow for the target page");
            }
        }
        List<PageWorkbenchContract.ActionView> selectedActions = new ArrayList<>();
        for (String actionKey : actionKeys) {
            PageWorkbenchContract.ActionView action = pageCatalog
                    .findAction(
                            task.projectCode(),
                            targetPageKey,
                            actionKey)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "selected page action is not registered: "
                                    + actionKey));
            if (!"ACTIVE".equalsIgnoreCase(action.status())) {
                throw new IllegalArgumentException(
                        "selected page action is not active: " + actionKey);
            }
            selectedActions.add(action);
        }
        validateStructuredPresentation(workflow.graphSpec(), selectedActions);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("projectId", task.projectId());
        request.put("projectCode", task.projectCode());
        request.put("taskId", task.taskId());
        request.put("pageKey", targetPageKey);
        request.put("summary", report.summary().trim());
        request.put("workflow", workflow);
        request.put("selectedActionKeys", actionKeys);
        request.put("referencedFiles", report.referencedFiles());
        request.put("acceptanceCriteria", report.acceptanceCriteria());
        request.put("replaceWorkflowId", replaceWorkflowId);
        request.put(
                "remainingQuestions",
                defaultList(report.remainingQuestions()));
        ResponseEntity<WorkflowEngineeringDraftView> response = runtimeClient
                .createPageWorkbenchWorkflowDraft(
                        task.projectCode(),
                        request);
        WorkflowEngineeringDraftView draft = response == null ? null : response.getBody();
        if (response == null || !response.getStatusCode().is2xxSuccessful()
                || draft == null || draft.workflow() == null
                || !"reachai.page-workbench.workflow-engineering-draft.v1".equals(draft.schema())
                || !task.taskId().equals(draft.taskId())
                || !targetPageKey.equals(draft.pageKey())
                || !StringUtils.hasText(draft.workflow().id())
                || draft.workflow().updatedAt() == null) {
            throw new AiCodingArtifactApplicationUnconfirmedException(
                    "Runtime did not confirm the Workflow draft for this task; retry the same Artifact");
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.set(
                "workflowEngineering",
                objectMapper.valueToTree(draft));
        String message = draft.validation() != null
                && draft.validation().valid()
                ? "Workflow 草稿已创建并通过当前发布校验，等待 ReachAI 审阅"
                : "Workflow 草稿已创建；当前校验问题已保留，请在 Studio 修正后再发布";
        return ArtifactApplyResult.acceptanceRequired(message, result);
    }

    /**
     * A read-capable page Workflow is not complete when it only leaves structured data in Runtime
     * state and relies on Supervisor Markdown. Every read producer must have a downstream,
     * display-only PRESENT_OUTPUT card whose data source is explicit and graph-verifiable.
     */
    private void validateStructuredPresentation(
            JsonNode graphSpec,
            List<PageWorkbenchContract.ActionView> selectedActions) {
        if (graphSpec == null || !graphSpec.isObject()) {
            return;
        }
        JsonNode rawNodes = graphSpec.path("nodes");
        if (!rawNodes.isArray()) {
            throw new IllegalArgumentException(
                    "workflow graphSpec.nodes must be an array");
        }

        Map<String, JsonNode> nodesById = new LinkedHashMap<>();
        Map<String, String> outputAliasOwner = new LinkedHashMap<>();
        Set<String> duplicateAliases = new HashSet<>();
        for (JsonNode node : rawNodes) {
            String nodeId = jsonText(node, "id");
            if (!StringUtils.hasText(nodeId)) {
                continue;
            }
            nodesById.put(nodeId, node);
            String outputAlias = configText(node, "outputAlias");
            if (StringUtils.hasText(outputAlias)) {
                String existing = outputAliasOwner.putIfAbsent(
                        outputAlias, nodeId);
                if (existing != null && !existing.equals(nodeId)) {
                    duplicateAliases.add(outputAlias);
                }
            }
        }

        Map<String, List<String>> outgoing = graphOutgoing(graphSpec.path("edges"));
        Set<String> validPresentations = new LinkedHashSet<>();
        List<String> issues = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : nodesById.entrySet()) {
            JsonNode node = entry.getValue();
            if (!"INTERACTION".equalsIgnoreCase(jsonText(node, "type"))
                    || !"PRESENT_OUTPUT".equalsIgnoreCase(configText(
                    node, "interactionType"))) {
                continue;
            }
            String nodeId = entry.getKey();
            String component = normalizeToken(configText(node, "component"));
            if (!Set.of("list_card", "output_card", "card", "detail")
                    .contains(component)) {
                issues.add(nodeId + " 必须使用 list_card/output_card/card/detail 卡片组件");
                continue;
            }
            if (configBoolean(node, "behavior", "blocking")) {
                issues.add(nodeId + " 的 behavior.blocking 必须为 false");
                continue;
            }
            String mode = normalizeToken(configNestedText(
                    node, "presentation", "mode"));
            if (!Set.of("card_only", "text_and_card").contains(mode)) {
                issues.add(nodeId + " 必须声明 presentation.mode=card_only 或 text_and_card");
                continue;
            }
            String expression = configText(node, "dataExpression");
            String producerId = presentationProducer(
                    expression,
                    nodesById,
                    outputAliasOwner,
                    duplicateAliases);
            if (!StringUtils.hasText(producerId)) {
                issues.add(nodeId + " 必须通过 nodeOutput.<producerNodeId> 或唯一输出别名声明 dataExpression");
                continue;
            }
            if (!isReachable(producerId, nodeId, outgoing)) {
                issues.add(nodeId + " 的 dataExpression 来源节点 " + producerId
                        + " 不在该展示节点的上游路径中");
                continue;
            }
            validPresentations.add(nodeId);
        }

        Map<String, PageWorkbenchContract.ActionView> selectedByKey =
                new LinkedHashMap<>();
        for (PageWorkbenchContract.ActionView action : defaultList(
                selectedActions)) {
            if (action != null && StringUtils.hasText(action.actionKey())) {
                selectedByKey.put(action.actionKey(), action);
            }
        }
        List<String> uncoveredReadNodes = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : nodesById.entrySet()) {
            JsonNode node = entry.getValue();
            String type = jsonText(node, "type");
            boolean structuredRead = "TOOL".equalsIgnoreCase(type);
            if ("PAGE_ACTION".equalsIgnoreCase(type)) {
                String actionKey = configText(node, "actionKey");
                PageWorkbenchContract.ActionView action = selectedByKey.get(
                        actionKey);
                structuredRead = action != null
                        && "READ".equalsIgnoreCase(action.riskLevel())
                        && !action.confirmRequired();
            }
            if (structuredRead && validPresentations.stream().noneMatch(
                    presentationId -> isReachable(
                            entry.getKey(), presentationId, outgoing))) {
                uncoveredReadNodes.add(entry.getKey());
            }
        }
        if (!uncoveredReadNodes.isEmpty()) {
            issues.add("以下结构化读取节点缺少下游 PRESENT_OUTPUT 卡片："
                    + String.join(", ", uncoveredReadNodes));
        }
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException(
                    "workflow structured presentation contract failed: "
                            + String.join("; ", issues));
        }
    }

    private Map<String, List<String>> graphOutgoing(JsonNode rawEdges) {
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        if (!rawEdges.isArray()) {
            return outgoing;
        }
        for (JsonNode edge : rawEdges) {
            String from = jsonText(edge, "from");
            String to = jsonText(edge, "to");
            if (StringUtils.hasText(from) && StringUtils.hasText(to)) {
                outgoing.computeIfAbsent(from, ignored -> new ArrayList<>())
                        .add(to);
            }
        }
        return outgoing;
    }

    private boolean isReachable(
            String from,
            String target,
            Map<String, List<String>> outgoing) {
        if (!StringUtils.hasText(from) || !StringUtils.hasText(target)
                || from.equals(target)) {
            return false;
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(from);
        visited.add(from);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : outgoing.getOrDefault(current, List.of())) {
                if (target.equals(next)) {
                    return true;
                }
                if (visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return false;
    }

    private String presentationProducer(
            String expression,
            Map<String, JsonNode> nodesById,
            Map<String, String> outputAliasOwner,
            Set<String> duplicateAliases) {
        if (!StringUtils.hasText(expression)) {
            return null;
        }
        String normalized = expression.trim();
        if (normalized.startsWith("nodeOutput.")) {
            String remainder = normalized.substring("nodeOutput.".length());
            String nodeId = remainder.contains(".")
                    ? remainder.substring(0, remainder.indexOf('.'))
                    : remainder;
            return nodesById.containsKey(nodeId) ? nodeId : null;
        }
        if (duplicateAliases.contains(normalized)) {
            return null;
        }
        return outputAliasOwner.get(normalized);
    }

    private String configText(JsonNode node, String field) {
        JsonNode config = node.path("config");
        JsonNode value = config.path(field);
        if ((!value.isValueNode() || value.isNull())
                && config.path("interactionConfig").isObject()) {
            value = config.path("interactionConfig").path(field);
        }
        return value.isValueNode() && !value.isNull()
                ? value.asText(null)
                : null;
    }

    private String configNestedText(
            JsonNode node,
            String objectField,
            String field) {
        JsonNode config = node.path("config");
        JsonNode nested = config.path(objectField);
        if (!nested.isObject() && config.path("interactionConfig").isObject()) {
            nested = config.path("interactionConfig").path(objectField);
        }
        JsonNode value = nested.path(field);
        return value.isValueNode() && !value.isNull()
                ? value.asText(null)
                : null;
    }

    private boolean configBoolean(
            JsonNode node,
            String objectField,
            String field) {
        JsonNode config = node.path("config");
        JsonNode nested = config.path(objectField);
        if (!nested.isObject() && config.path("interactionConfig").isObject()) {
            nested = config.path("interactionConfig").path(objectField);
        }
        return nested.path(field).asBoolean(false);
    }

    private static String jsonText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isValueNode() && !value.isNull()
                ? value.asText(null)
                : null;
    }

    private static String normalizeToken(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase().replace('-', '_').replace(' ', '_');
    }

    private List<Map<String, Object>> activeLlmModels(
            ResponseEntity<Map<String, Object>> response) {
        Map<String, Object> body = response == null ? null : response.getBody();
        Object data = body == null ? null : body.get("data");
        if (!(data instanceof Collection<?> collection)) {
            return List.of();
        }
        List<Map<String, Object>> models = new ArrayList<>();
        for (Object raw : collection) {
            if (!(raw instanceof Map<?, ?> source)
                    || !"ACTIVE".equalsIgnoreCase(text(source.get("status")))) {
                continue;
            }
            Map<String, Object> model = new LinkedHashMap<>();
            copyText(source, model, "id");
            copyText(source, model, "name");
            copyText(source, model, "provider");
            copyText(source, model, "modelName");
            copyText(source, model, "modelType");
            copyText(source, model, "status");
            if (StringUtils.hasText(text(model.get("id")))) {
                models.add(Map.copyOf(model));
            }
        }
        return List.copyOf(models);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(ResponseEntity<Object> response) {
        Object body = response == null ? null : response.getBody();
        if (!(body instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) new LinkedHashMap<>(
                        (Map<String, Object>) item))
                .toList();
    }

    private void copyText(
            Map<?, ?> source,
            Map<String, Object> target,
            String key) {
        String value = text(source.get(key));
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private ArtifactApplyResult applyAnalysis(
            TaskDescriptor task,
            JsonNode content) {
        PageAnalysisPayload report = parse(
                content,
                PageAnalysisPayload.class,
                "page-analysis");
        String targetPageKey = primaryPageKey(task);
        if (report == null
                || !targetPageKey.equals(requireText(report.pageKey(), "pageKey"))) {
            throw new IllegalArgumentException(
                    "page-analysis pageKey must equal the primary PAGE target");
        }
        int applied = pageAnalysis.applyAnalysis(
                task.projectId(),
                task.projectCode(),
                task.taskId(),
                targetPageKey,
                report.findings());
        ObjectNode result = objectMapper.createObjectNode();
        result.put("pageKey", targetPageKey);
        result.put("appliedFindings", applied);
        return ArtifactApplyResult.complete(
                "页面分析报告已校验并写入，共 " + applied + " 条结果",
                result);
    }

    private ArtifactApplyResult applyImplementation(JsonNode content) {
        ImplementationPayload report = parse(
                content,
                ImplementationPayload.class,
                "code-implementation");
        if (report == null || !StringUtils.hasText(report.summary())) {
            throw new IllegalArgumentException(
                    "code-implementation summary is required");
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("implementation", content);
        boolean browserPassed = report.browserVerification() != null
                && report.browserVerification().passed();
        result.put("browserVerified", browserPassed);
        if (!allChecksPass(report.tests()) || !browserPassed) {
            return ArtifactApplyResult.rejected(
                    !browserPassed
                            ? "代码实施报告未包含通过的真实浏览器自检；请继续执行并使用新的 artifactKey 回传"
                            : "代码实施报告包含未通过或未执行的测试；请修正后使用新的 artifactKey 回传",
                    result);
        }
        return ArtifactApplyResult.acceptanceRequired(
                "代码实施报告、测试和浏览器自检已回传，等待 ReachAI 验收",
                result);
    }

    private ArtifactApplyResult applyBrowserAcceptance(JsonNode content) {
        AcceptancePayload report = parse(
                content,
                AcceptancePayload.class,
                "browser-acceptance");
        if (report == null || !StringUtils.hasText(report.summary())) {
            throw new IllegalArgumentException(
                    "browser-acceptance summary is required");
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("browserAcceptance", content);
        boolean passed = report.passed()
                && report.browserVerification() != null
                && report.browserVerification().passed()
                && structuredPresentationPassed(
                report.structuredPresentation())
                && report.stateRestoration() != null
                && report.stateRestoration().passed()
                && allChecksPass(report.checks());
        result.put("reportedPassed", passed);
        if (!passed) {
            return ArtifactApplyResult.failed(
                    "浏览器验收未通过，真实材料已保留，当前任务已结束",
                    result);
        }
        return ArtifactApplyResult.acceptanceRequired(
                "浏览器验收材料已回传且报告通过，等待用户在 ReachAI 确认",
                result);
    }

    private static boolean structuredPresentationPassed(
            PageWorkbenchContract.StructuredPresentationVerification report) {
        if (report == null || !report.passed()
                || !StringUtils.hasText(report.evidence())) {
            return false;
        }
        return !report.required()
                || (report.uiRequestedObserved()
                && report.cardDomObserved()
                && StringUtils.hasText(report.component()));
    }

    private ArtifactApplyResult applyPreRelease(JsonNode content) {
        PreReleasePayload report = parse(
                content,
                PreReleasePayload.class,
                "pre-release");
        if (report == null || !StringUtils.hasText(report.summary())) {
            throw new IllegalArgumentException(
                    "pre-release summary is required");
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("preRelease", content);
        boolean passed = report.passed()
                && allChecksPass(report.checks())
                && StringUtils.hasText(report.workflowId())
                && StringUtils.hasText(report.workflowVersion());
        result.put("reportedPassed", passed);
        if (!passed) {
            return ArtifactApplyResult.failed(
                    "发布前检查未通过或缺少目标 Workflow 版本，材料已保留，当前任务已结束",
                    result);
        }
        return ArtifactApplyResult.acceptanceRequired(
                "发布前检查材料已回传且报告通过，等待 ReachAI 确认",
                result);
    }

    private static boolean allChecksPass(List<ReportedCheck> checks) {
        return checks != null
                && !checks.isEmpty()
                && checks.stream().allMatch(check ->
                        check != null && "PASS".equals(check.status()));
    }

    private <T> T parse(JsonNode content, Class<T> type, String label) {
        try {
            return objectMapper.treeToValue(content, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(
                    label + " artifact does not match "
                            + contract.resultContractKey() + "/v1",
                    ex);
        }
    }

    private String primaryPageKey(TaskDescriptor task) {
        return task.targets().stream()
                .filter(target -> "PRIMARY".equals(target.targetRole()))
                .filter(target -> "PAGE".equals(target.targetType()))
                .map(TaskTargetView::targetKey)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "page task requires one primary PAGE target"));
    }

    private List<ReadinessItem> browserReadiness(
            TaskDescriptor task,
            String reportedTraceId) {
        String pageKey = primaryPageKey(task);
        LocalDateTime observedAfter = observedAfter(task);
        WorkflowAcceptanceTarget target = workflowAcceptanceTarget(task);
        if (target != null && !StringUtils.hasText(target.modelInstanceId())) {
            try {
                target = workflowTraceReadiness.requirePublishedTarget(
                        task.projectCode(),
                        pageKey,
                        target);
            } catch (RuntimeException ignored) {
                // The existing browser/trace readiness items retain the
                // runtime outage or version-mismatch evidence. Model
                // readiness remains non-PASS until the exact target can be
                // resolved again.
            }
        }
        return List.of(
                target == null
                        ? modelReadiness.pageOnly()
                        : modelReadiness.evaluate(target.modelInstanceId()),
                browserReadiness.evaluate(
                        task.projectCode(),
                        pageKey,
                        observedAfter),
                workflowTraceReadiness.evaluate(
                        task.projectCode(),
                        pageKey,
                        observedAfter,
                        target,
                        reportedTraceId));
    }

    private WorkflowAcceptanceTarget workflowAcceptanceTarget(
            TaskDescriptor task) {
        List<TaskTargetView> workflowTargets = task.targets().stream()
                .filter(target -> "RELATED".equals(target.targetRole()))
                .filter(target -> "WORKFLOW".equals(target.targetType()))
                .toList();
        if (workflowTargets.size() > 1) {
            throw new IllegalArgumentException(
                    "browser acceptance task supports at most one related WORKFLOW target");
        }
        if (workflowTargets.isEmpty()) {
            return null;
        }
        TaskTargetView target = workflowTargets.get(0);
        JsonNode snapshot = target.snapshot();
        Long workflowVersionId = snapshot != null
                && snapshot.path("workflowVersionId").canConvertToLong()
                ? snapshot.path("workflowVersionId").longValue()
                : null;
        return new WorkflowAcceptanceTarget(
                target.targetKey(),
                workflowVersionId,
                snapshot == null
                        ? null
                        : snapshot.path("workflowVersion").asText(null),
                snapshot == null
                        ? null
                        : snapshot.path("workflowName").asText(null),
                snapshot == null
                        ? null
                        : snapshot.path("modelInstanceId").asText(null));
    }

    private static LocalDateTime observedAfter(TaskDescriptor task) {
        return task.startedAt() == null
                ? task.createdAt()
                : task.startedAt();
    }

    private ObjectNode safeProject(
            Map<String, Object> source,
            TaskDescriptor task) {
        Map<String, Object> project = source == null ? Map.of() : source;
        ObjectNode safe = objectMapper.createObjectNode();
        safe.put("id", task.projectId());
        safe.put("projectCode", task.projectCode());
        copyText(project, safe, "name");
        copyText(project, safe, "projectKind");
        copyText(project, safe, "environment");
        copyText(project, safe, "baseUrl");
        copyText(project, safe, "contextPath");
        copyText(project, safe, "repositoryUrl");
        copyText(project, safe, "repositoryRoot");
        return safe;
    }

    private static void copyText(
            Map<String, Object> source,
            ObjectNode target,
            String field) {
        Object value = source.get(field);
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            target.put(field, String.valueOf(value));
        }
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String textOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static <T> List<T> defaultList(List<T> value) {
        return value == null ? List.of() : value;
    }

    private static List<String> requiredTextList(
            List<String> values,
            String field) {
        List<String> normalized = defaultList(values).stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " requires at least one item");
        }
        return normalized;
    }
}

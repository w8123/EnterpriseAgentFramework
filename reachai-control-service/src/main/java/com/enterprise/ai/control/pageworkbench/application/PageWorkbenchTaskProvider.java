package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactApplyResult;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactEnvelope;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskContract;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetView;
import com.enterprise.ai.control.aicoding.domain.AiCodingDeliveryEvidence.ReportedCheck;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskKindProvider;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
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
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final CapabilityProjectOnboardingClient capabilityClient;
    private final RuntimeProxyClient runtimeClient;
    private final PageWorkbenchBrowserReadinessApplicationService browserReadiness;
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
            CapabilityProjectOnboardingClient capabilityClient,
            RuntimeProxyClient runtimeClient,
            PageWorkbenchBrowserReadinessApplicationService browserReadiness,
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
        this.runtimeClient = runtimeClient;
        this.browserReadiness = browserReadiness;
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
                    .add("Submitting the artifact may create a DRAFT only; publishing and Agent attachment require explicit ReachAI approval.")
                    .add("Read the workflow-ai-coding Skill from /api/ai-assist/skills/workflow-ai-coding/latest.zip before authoring GraphSpec.")
                    .add("Submit the result through the current task artifact endpoint.");
        } else {
            requiredChecks.add("Run focused tests for changed code.")
                    .add("Use real browser verification when the task requires UI behavior.")
                    .add("Submit the result through the current task artifact endpoint.");
            if (BROWSER_ACCEPTANCE.equals(taskKind)
                    && workflowTarget != null) {
                requiredChecks.add(
                        "Return the exact Runtime traceId produced by the accepted browser scenario when available.");
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
        }

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
        request.put(
                "remainingQuestions",
                defaultList(report.remainingQuestions()));
        WorkflowEngineeringDraftView draft = runtimeClient
                .createPageWorkbenchWorkflowDraft(
                        task.projectCode(),
                        request)
                .getBody();
        if (draft == null || draft.workflow() == null) {
            throw new IllegalStateException(
                    "Runtime did not return the created Workflow draft");
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
        return List.of(
                browserReadiness.evaluate(
                        task.projectCode(),
                        pageKey,
                        observedAfter),
                workflowTraceReadiness.evaluate(
                        task.projectCode(),
                        pageKey,
                        observedAfter,
                        workflowAcceptanceTarget(task),
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
                        : snapshot.path("workflowName").asText(null));
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

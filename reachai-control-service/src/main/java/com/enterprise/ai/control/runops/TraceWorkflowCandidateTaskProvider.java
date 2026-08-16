package com.enterprise.ai.control.runops;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactApplyResult;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactEnvelope;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskContract;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskRequiredResource;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.VerificationGuideItem;
import com.enterprise.ai.control.aicoding.provider.AiCodingContractResourceLoader;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskKindProvider;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI Coding provider that materializes an eligible RunOps trace as a validated
 * DRAFT Workflow. It intentionally has no publish or Agent attachment action.
 */
@Component
public class TraceWorkflowCandidateTaskProvider
        implements AiCodingTaskKindProvider {

    public static final String TASK_KIND = "TRACE_WORKFLOW_CANDIDATE";
    public static final String CONTRACT_KEY =
            "reachai.trace-workflow-candidate-report";
    public static final String CONTRACT_VERSION = "v1";

    private static final Set<String> SAFE_CANDIDATE_NODE_TYPES = Set.of(
            "USER_INPUT",
            "LLM",
            "TOOL",
            "CAPABILITY",
            "IF_ELSE",
            "VARIABLE_ASSIGN",
            "TEMPLATE",
            "ANSWER",
            "INTENT_CLASSIFIER",
            "VARIABLE_AGGREGATOR",
            "PARAMETER_EXTRACT");

    private final TraceWorkflowCandidateEligibility eligibilityService;
    private final RuntimeProxyClient runtimeClient;
    private final ObjectMapper objectMapper;
    private final TaskContract contract;

    public TraceWorkflowCandidateTaskProvider(
            TraceWorkflowCandidateEligibility eligibilityService,
            RuntimeProxyClient runtimeClient,
            ObjectMapper objectMapper,
            AiCodingContractResourceLoader resources) {
        this.eligibilityService = eligibilityService;
        this.runtimeClient = runtimeClient;
        this.objectMapper = objectMapper;
        this.contract = new TaskContract(
                "RUNOPS_TRACE_TO_WORKFLOW",
                TASK_KIND,
                "READ_WRITE",
                TraceWorkflowCandidateEligibility.PRIMARY_TARGET_TYPE,
                CONTRACT_KEY,
                CONTRACT_VERSION,
                resources.load("trace-workflow-candidate-report-v1.schema.json"),
                resources.load("trace-workflow-candidate-report-v1.example.json"));
    }

    @Override
    public String kind() {
        return TASK_KIND;
    }

    @Override
    public TaskContract contract() {
        return contract;
    }

    @Override
    public JsonNode buildContext(TaskDescriptor task) {
        return eligibilityService.buildTaskContext(primaryTarget(task));
    }

    @Override
    public List<ReadinessItem> readiness(TaskDescriptor task) {
        TraceWorkflowCandidateEligibility.EligibilityView eligibility =
                eligibilityService.evaluate(primaryTarget(task).targetKey());
        return List.of(new ReadinessItem(
                "TRACE_ELIGIBLE",
                "源轨迹候选资格",
                eligibility.eligible() ? "PASS" : "BLOCKED",
                eligibility.eligible()
                        ? "源轨迹仍满足首批只读候选规则"
                        : String.join("；", eligibility.blockers()),
                objectMapper.valueToTree(eligibility)));
    }

    @Override
    public List<ReadinessItem> readiness(
            TaskDescriptor task,
            JsonNode applicationResult) {
        if (applicationResult == null
                || !applicationResult.path("workflowCandidate").isObject()) {
            return readiness(task);
        }
        JsonNode candidate = applicationResult.path("workflowCandidate");
        String workflowId = candidate.path("workflowId").asText(null);
        String expectedGraphSpecDigest = candidate.path("graphSpecDigest")
                .asText(null);
        String expectedWorkflowUpdatedAt = candidate.path("workflowUpdatedAt")
                .asText(null);
        CandidateDraftState draftState = candidateDraftState(
                workflowId,
                expectedGraphSpecDigest,
                expectedWorkflowUpdatedAt);
        ReadinessItem replayReadiness = candidateReplayReadiness(
                workflowId,
                expectedGraphSpecDigest,
                expectedWorkflowUpdatedAt);
        return List.of(
                readiness(task).get(0),
                new ReadinessItem(
                        "DRAFT_CREATED",
                        "Workflow 草稿已创建",
                        draftState.currentDraft() ? "PASS" : "BLOCKED",
                        draftState.message(),
                        draftState.evidence()),
                new ReadinessItem(
                        "RELEASE_VALIDATION",
                        "Runtime 发布校验",
                        draftState.valid() ? "PASS" : "BLOCKED",
                        draftState.valid()
                                ? "候选 GraphSpec 已通过当前 Runtime 发布校验"
                                : "当前候选未通过 Runtime 发布校验，或已偏离任务应用版本",
                        draftState.validation()),
                replayReadiness);
    }

    private CandidateDraftState candidateDraftState(
            String workflowId,
            String expectedGraphSpecDigest,
            String expectedWorkflowUpdatedAt) {
        ObjectNode evidence = objectMapper.createObjectNode();
        putText(evidence, "workflowId", workflowId);
        putText(evidence, "expectedGraphSpecDigest", expectedGraphSpecDigest);
        putText(evidence, "expectedWorkflowUpdatedAt", expectedWorkflowUpdatedAt);
        if (!StringUtils.hasText(workflowId)
                || !StringUtils.hasText(expectedGraphSpecDigest)
                || !StringUtils.hasText(expectedWorkflowUpdatedAt)) {
            return new CandidateDraftState(
                    false,
                    false,
                    "候选应用结果缺少 Workflow 身份、GraphSpec 指纹或修订号",
                    evidence,
                    objectMapper.createObjectNode());
        }
        try {
            Map<String, Object> current = responseMap(
                    runtimeClient.getWorkflow(workflowId),
                    "current Workflow candidate");
            String status = text(current.get("status"));
            String graphSpecJson = text(current.get("graphSpecJson"));
            String actualWorkflowUpdatedAt = text(current.get("updatedAt"));
            String actualDigest = StringUtils.hasText(graphSpecJson)
                    ? sha256(graphSpecJson)
                    : null;
            putText(evidence, "status", status);
            putText(evidence, "actualGraphSpecDigest", actualDigest);
            putText(evidence, "actualWorkflowUpdatedAt", actualWorkflowUpdatedAt);
            boolean sameGraph = expectedGraphSpecDigest.equalsIgnoreCase(
                    firstText(actualDigest, ""));
            boolean sameRevision = expectedWorkflowUpdatedAt.equals(
                    actualWorkflowUpdatedAt);
            boolean currentDraft = "DRAFT".equalsIgnoreCase(status)
                    && sameGraph
                    && sameRevision;
            Map<String, Object> validation = currentDraft
                    ? responseMap(
                            runtimeClient.validateWorkflowAiCoding(
                                    workflowId,
                                    Map.of("mode", "CURRENT")),
                            "current Workflow candidate validation")
                    : Map.of();
            boolean valid = currentDraft
                    && booleanValue(validation.get("valid"));
            String message;
            if (!"DRAFT".equalsIgnoreCase(status)) {
                message = "候选已不处于 DRAFT；任务不接受提前发布或状态漂移";
            } else if (!sameGraph) {
                message = "候选 GraphSpec 已偏离本任务应用的版本";
            } else if (!sameRevision) {
                message = "候选 Workflow 修订号已变化；默认模型或其他执行配置可能已漂移";
            } else {
                message = "候选仍处于 DRAFT，且 Workflow 修订号与 GraphSpec 均和任务应用版本一致";
            }
            return new CandidateDraftState(
                    currentDraft,
                    valid,
                    message,
                    evidence,
                    objectMapper.valueToTree(validation));
        } catch (RuntimeException ex) {
            evidence.put("dependencyError", ex.getClass().getSimpleName());
            return new CandidateDraftState(
                    false,
                    false,
                    "无法实时确认候选草稿状态",
                    evidence,
                    objectMapper.createObjectNode());
        }
    }

    @Override
    public List<String> acceptanceReadinessKeys() {
        return List.of(
                "TRACE_ELIGIBLE",
                "DRAFT_CREATED",
                "RELEASE_VALIDATION",
                "CANDIDATE_REPLAY");
    }

    @Override
    public List<TaskRequiredResource> requiredResources(
            TaskDescriptor task,
            String publicBaseUrl) {
        String root = publicBaseUrl == null
                ? ""
                : publicBaseUrl.replaceAll("/+$", "");
        return List.of(new TaskRequiredResource(
                "workflow-ai-coding-skill",
                "ReachAI Workflow AI Coding Skill",
                "SKILL_ZIP",
                root + "/api/ai-assist/skills/workflow-ai-coding/latest.zip",
                "workflow-ai-coding/SKILL.md",
                "GraphSpec、Workflow AI Coding API、校验和发布安全边界。",
                true));
    }

    @Override
    public List<VerificationGuideItem> verificationGuide(
            TaskDescriptor task,
            String taskRoot) {
        return List.of(new VerificationGuideItem(
                "RELEASE_VALIDATION",
                "OBSERVATION",
                "Runtime 发布校验",
                "Artifact 应用前平台先预校验 GraphSpec，创建 DRAFT 后再次校验。",
                "POST",
                null,
                List.of("提交符合契约的候选 Artifact"),
                List.of("RELEASE_VALIDATION"),
                "valid=true；errors 为空；Workflow status=DRAFT",
                "Runtime /api/workflows/runtime-validation 与 Workflow AI Coding validate"));
    }

    @Override
    public ArtifactApplyResult applyArtifact(
            TaskDescriptor task,
            ArtifactEnvelope artifact) {
        TaskTargetView primary = primaryTarget(task);
        String sourceTraceId = requireText(
                artifact.content().path("sourceTraceId").asText(null),
                "sourceTraceId");
        if (!primary.targetKey().equals(sourceTraceId)) {
            throw new IllegalArgumentException(
                    "sourceTraceId must equal the primary RUNTIME_RUN target");
        }
        TraceWorkflowCandidateEligibility.EligibilityView eligibility =
                eligibilityService.evaluate(sourceTraceId);
        if (!eligibility.eligible()) {
            throw new IllegalArgumentException(
                    "selected trace is no longer eligible: "
                            + String.join("; ", eligibility.blockers()));
        }

        JsonNode workflow = artifact.content().path("workflow");
        JsonNode graphSpec = workflow.path("graphSpec");
        Map<String, Object> currentTrace = responseMap(
                runtimeClient.runOpsDetail(sourceTraceId),
                "current source trace detail");
        JsonNode sourceVersion = loadExactSourceVersion(eligibility);
        assertReadOnlyLineage(
                graphSpec,
                sourceVersion,
                objectMapper.valueToTree(currentTrace.get("executionPath")));
        Map<String, Object> preflightRequest = new LinkedHashMap<>();
        preflightRequest.put("graphSpecJson", writeJson(graphSpec));
        preflightRequest.put("executionEngine", "GRAPH_SPEC");
        String defaultModelInstanceId = text(
                workflow.get("defaultModelInstanceId"));
        if (StringUtils.hasText(defaultModelInstanceId)) {
            preflightRequest.put("defaultModelInstanceId", defaultModelInstanceId);
        }
        Map<String, Object> preflight = responseMap(
                runtimeClient.validateWorkflowRuntime(preflightRequest),
                "Runtime validation preflight");
        if (!booleanValue(preflight.get("valid"))) {
            ObjectNode rejected = objectMapper.createObjectNode();
            rejected.set("preflightValidation", objectMapper.valueToTree(preflight));
            return ArtifactApplyResult.rejected(
                    "候选 GraphSpec 未通过 Runtime 预校验，请修正后使用新的 artifactKey 回传",
                    rejected);
        }

        Map<String, Object> createRequest = createRequest(
                task,
                sourceTraceId,
                eligibility,
                artifact.content(),
                workflow);
        Map<String, Object> context = responseMap(
                runtimeClient.createTraceWorkflowCandidateDraft(createRequest),
                "created Workflow candidate");
        Map<String, Object> workflowView = map(context.get("workflow"));
        String workflowId = requireText(
                text(workflowView.get("id")),
                "Runtime workflow id");
        Map<String, Object> validation = responseMap(
                runtimeClient.validateWorkflowAiCoding(
                        workflowId,
                        Map.of("mode", "CURRENT")),
                "created Workflow validation");
        if (!booleanValue(validation.get("valid"))) {
            throw new IllegalStateException(
                    "Runtime created a DRAFT that failed release validation");
        }
        Map<String, Object> savedWorkflow = responseMap(
                runtimeClient.getWorkflow(workflowId),
                "saved Workflow candidate");
        String savedGraphSpecJson = requireText(
                text(savedWorkflow.get("graphSpecJson")),
                "saved Workflow graphSpecJson");
        String savedWorkflowUpdatedAt = requireText(
                text(savedWorkflow.get("updatedAt")),
                "saved Workflow updatedAt");

        ObjectNode candidate = objectMapper.createObjectNode();
        candidate.put("schema", "reachai.runops.workflow-candidate-draft.v1");
        candidate.put("sourceTraceId", sourceTraceId);
        candidate.put("sourceWorkflowId", eligibility.sourceWorkflowId());
        candidate.put("sourceWorkflowVersionId", eligibility.sourceWorkflowVersionId());
        candidate.put("workflowId", workflowId);
        putText(candidate, "keySlug", text(workflowView.get("keySlug")));
        putText(candidate, "name", text(workflowView.get("name")));
        putText(candidate, "status", text(workflowView.get("status")));
        candidate.set("validation", objectMapper.valueToTree(validation));
        candidate.put("graphSpecDigest", sha256(savedGraphSpecJson));
        candidate.put("workflowUpdatedAt", savedWorkflowUpdatedAt);
        candidate.set("acceptanceCriteria",
                artifact.content().path("acceptanceCriteria"));
        candidate.put("publishRequired", true);
        candidate.put("published", false);

        ObjectNode result = objectMapper.createObjectNode();
        result.set("workflowCandidate", candidate);
        return ArtifactApplyResult.acceptanceRequired(
                "Workflow 候选草稿已创建并通过 Runtime 校验；仍需重放评测、人工审阅和显式发布",
                result);
    }

    private JsonNode loadExactSourceVersion(
            TraceWorkflowCandidateEligibility.EligibilityView eligibility) {
        ResponseEntity<Object> response = runtimeClient.listWorkflowVersions(
                eligibility.sourceWorkflowId());
        Object body = response == null ? null : response.getBody();
        if (!(body instanceof List<?> versions)) {
            throw new IllegalArgumentException(
                    "Runtime did not return source Workflow versions");
        }
        Object sourceVersion = versions.stream()
                .filter(Map.class::isInstance)
                .map(this::map)
                .filter(version -> eligibility.sourceWorkflowVersionId()
                        .equals(longValue(version.get("id"))))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "exact source Workflow version is no longer available"));
        return objectMapper.valueToTree(sourceVersion);
    }

    private Map<String, Object> createRequest(
            TaskDescriptor task,
            String sourceTraceId,
            TraceWorkflowCandidateEligibility.EligibilityView eligibility,
            JsonNode content,
            JsonNode workflow) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("taskId", task.taskId());
        request.put("sourceTraceId", sourceTraceId);
        request.put("sourceWorkflowId", eligibility.sourceWorkflowId());
        request.put("sourceWorkflowVersionId",
                eligibility.sourceWorkflowVersionId());
        request.put("sourceWorkflowVersion",
                eligibility.sourceWorkflowVersion());
        request.put("name", requireText(workflow.path("name").asText(null),
                "workflow.name"));
        request.put("keySlug", taskScopedKeySlug(
                workflow.path("keySlug").asText(null), task.taskId()));
        request.put("projectId", task.projectId());
        request.put("projectCode", task.projectCode());
        request.put("description", text(workflow.get("description")));
        request.put("workflowKind", firstText(
                text(workflow.get("workflowKind")), "GENERAL"));
        request.put("executionEngine", "GRAPH_SPEC");
        if (StringUtils.hasText(text(workflow.get("defaultModelInstanceId")))) {
            request.put("defaultModelInstanceId",
                    text(workflow.get("defaultModelInstanceId")));
        }
        request.put("graphSpec", workflow.get("graphSpec"));
        if (workflow.has("canvas") && !workflow.get("canvas").isNull()) {
            request.put("canvas", workflow.get("canvas"));
        }
        return request;
    }

    private void assertReadOnlyLineage(
            JsonNode candidateGraph,
            JsonNode sourceVersion,
            JsonNode executionPath) {
        JsonNode sourceGraph = sourceGraph(sourceVersion);
        Set<String> executedNodeIds = new LinkedHashSet<>();
        for (JsonNode item : executionPath) {
            if ("WORKFLOW_NODE".equalsIgnoreCase(
                    item.path("spanType").asText())
                    && "SUCCESS".equalsIgnoreCase(
                    item.path("status").asText())
                    && StringUtils.hasText(item.path("nodeId").asText(null))) {
                executedNodeIds.add(item.path("nodeId").asText());
            }
        }
        Set<String> sourceToolReferences = toolReferences(
                sourceGraph, executedNodeIds);
        Set<String> candidateToolReferences = toolReferences(
                candidateGraph, Set.of());
        if (!sourceToolReferences.isEmpty()
                && candidateToolReferences.isEmpty()) {
            throw new IllegalArgumentException(
                    "candidate GraphSpec removed every tool from the observed causal path");
        }
        Set<String> newReferences = new LinkedHashSet<>(candidateToolReferences);
        newReferences.removeAll(sourceToolReferences);
        if (!newReferences.isEmpty()) {
            throw new IllegalArgumentException(
                    "candidate GraphSpec introduces tools not present in the exact source version: "
                            + String.join(", ", newReferences));
        }
        Map<String, Integer> sourceToolCounts = toolReferenceCounts(
                sourceGraph, executedNodeIds);
        Map<String, Integer> candidateToolCounts = toolReferenceCounts(
                candidateGraph, Set.of());
        for (Map.Entry<String, Integer> entry : candidateToolCounts.entrySet()) {
            int sourceCount = sourceToolCounts.getOrDefault(
                    entry.getKey(), 0);
            if (entry.getValue() > sourceCount) {
                throw new IllegalArgumentException(
                        "candidate GraphSpec amplifies tool " + entry.getKey()
                                + " beyond the observed causal path");
            }
        }
        Map<String, Integer> sourceTypeCounts = nodeTypeCounts(
                sourceGraph, executedNodeIds, true);
        Map<String, Integer> candidateTypeCounts = nodeTypeCounts(
                candidateGraph, Set.of(), false);
        for (Map.Entry<String, Integer> entry : candidateTypeCounts.entrySet()) {
            String type = entry.getKey();
            if (!SAFE_CANDIDATE_NODE_TYPES.contains(type)) {
                throw new IllegalArgumentException(
                        "read-only trace candidate cannot contain node type "
                                + type);
            }
            int sourceCount = sourceTypeCounts.getOrDefault(type, 0);
            if (entry.getValue() > sourceCount) {
                throw new IllegalArgumentException(
                        "candidate GraphSpec adds or amplifies node type " + type
                                + " beyond the observed causal path");
            }
        }
    }

    private Map<String, Integer> nodeTypeCounts(
            JsonNode graph,
            Set<String> includedNodeIds,
            boolean requireEveryIncludedNode) {
        Map<String, Integer> result = new LinkedHashMap<>();
        Set<String> foundNodeIds = new LinkedHashSet<>();
        for (JsonNode node : graph.path("nodes")) {
            String nodeId = node.path("id").asText(null);
            if (!includedNodeIds.isEmpty()
                    && !includedNodeIds.contains(nodeId)) {
                continue;
            }
            if (StringUtils.hasText(nodeId)) foundNodeIds.add(nodeId);
            String type = node.path("type").asText("")
                    .trim().toUpperCase(Locale.ROOT);
            if (!StringUtils.hasText(type)) {
                throw new IllegalArgumentException(
                        "GraphSpec node has no type: "
                                + firstText(nodeId, "unknown"));
            }
            result.merge(type, 1, Integer::sum);
        }
        if (requireEveryIncludedNode
                && !foundNodeIds.containsAll(includedNodeIds)) {
            Set<String> missing = new LinkedHashSet<>(includedNodeIds);
            missing.removeAll(foundNodeIds);
            throw new IllegalArgumentException(
                    "exact source Workflow version does not contain observed nodes: "
                            + String.join(", ", missing));
        }
        return result;
    }

    private JsonNode sourceGraph(JsonNode sourceVersion) {
        JsonNode direct = sourceVersion.path("graphSpecSnapshotJson");
        if (direct.isTextual()) {
            try {
                return objectMapper.readTree(direct.asText());
            } catch (Exception ex) {
                throw new IllegalArgumentException(
                        "source Workflow GraphSpec snapshot is invalid", ex);
            }
        }
        JsonNode snapshotJson = sourceVersion.path("snapshotJson");
        if (snapshotJson.isTextual()) {
            try {
                JsonNode snapshot = objectMapper.readTree(snapshotJson.asText());
                JsonNode graph = snapshot.path("graphSpec");
                return graph.isTextual()
                        ? objectMapper.readTree(graph.asText())
                        : graph;
            } catch (Exception ex) {
                throw new IllegalArgumentException(
                        "source Workflow version snapshot is invalid", ex);
            }
        }
        throw new IllegalArgumentException(
                "source Workflow version has no GraphSpec snapshot");
    }

    private Set<String> toolReferences(
            JsonNode graph,
            Set<String> includedNodeIds) {
        return new LinkedHashSet<>(
                toolReferenceCounts(graph, includedNodeIds).keySet());
    }

    private Map<String, Integer> toolReferenceCounts(
            JsonNode graph,
            Set<String> includedNodeIds) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (JsonNode node : graph.path("nodes")) {
            if (!includedNodeIds.isEmpty()
                    && !includedNodeIds.contains(node.path("id").asText())) {
                continue;
            }
            String type = node.path("type").asText("")
                    .trim().toUpperCase(Locale.ROOT);
            if (!("TOOL".equals(type) || "CAPABILITY".equals(type))) {
                continue;
            }
            String reference = firstText(
                    node.path("ref").path("qualifiedName").asText(null),
                    node.path("ref").path("name").asText(null),
                    node.path("config").path("qualifiedName").asText(null),
                    node.path("config").path("toolName").asText(null),
                    node.path("config").path("ref").asText(null));
            if (!StringUtils.hasText(reference)) {
                throw new IllegalArgumentException(
                        "tool-like node has no stable reference: "
                                + node.path("id").asText("unknown"));
            }
            result.merge(reference, 1, Integer::sum);
        }
        return result;
    }

    private ReadinessItem candidateReplayReadiness(
            String workflowId,
            String expectedGraphSpecDigest,
            String expectedWorkflowUpdatedAt) {
        if (!StringUtils.hasText(workflowId)
                || !StringUtils.hasText(expectedGraphSpecDigest)
                || !StringUtils.hasText(expectedWorkflowUpdatedAt)) {
            return new ReadinessItem(
                    "CANDIDATE_REPLAY",
                    "候选调试 / 重放",
                    "PENDING",
                    "候选尚未创建，无法检查运行证据",
                    objectMapper.createObjectNode());
        }
        Map<String, Object> body = responseMap(
                runtimeClient.workflowAiCodingRuns(workflowId, 20, 30),
                "Workflow candidate runs");
        Object runs = body.get("runs");
        List<Map<String, Object>> rows = runs instanceof List<?> list
                ? list.stream()
                .filter(Map.class::isInstance)
                .map(this::map)
                .toList()
                : List.of();
        Map<String, Object> successful = rows.stream()
                .filter(run -> "COMPLETED".equalsIgnoreCase(
                        text(run.get("status"))))
                .filter(run -> runStartedAtOrAfterRevision(
                        text(run.get("startedAt")),
                        expectedWorkflowUpdatedAt))
                .filter(run -> runMatchesCandidateGraph(
                        workflowId,
                        text(run.get("traceId")),
                        expectedGraphSpecDigest))
                .findFirst()
                .orElse(null);
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("workflowId", workflowId);
        evidence.put("observedRunCount", rows.size());
        putText(evidence, "expectedGraphSpecDigest", expectedGraphSpecDigest);
        putText(evidence, "expectedWorkflowUpdatedAt", expectedWorkflowUpdatedAt);
        if (successful != null) {
            putText(evidence, "traceId", text(successful.get("traceId")));
            return new ReadinessItem(
                    "CANDIDATE_REPLAY",
                    "候选调试 / 重放",
                    "PASS",
                    "平台观测到候选自身的成功 Workflow Trace",
                    evidence);
        }
        return new ReadinessItem(
                "CANDIDATE_REPLAY",
                "候选调试 / 重放",
                "PENDING",
                "请先通过 Workflow AI Coding run 使用代表性输入运行候选，再刷新平台验证",
                evidence);
    }

    private boolean runStartedAtOrAfterRevision(
            String runStartedAt,
            String workflowUpdatedAt) {
        if (!StringUtils.hasText(runStartedAt)
                || !StringUtils.hasText(workflowUpdatedAt)) {
            return false;
        }
        try {
            return !LocalDateTime.parse(runStartedAt)
                    .isBefore(LocalDateTime.parse(workflowUpdatedAt));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean runMatchesCandidateGraph(
            String workflowId,
            String traceId,
            String expectedGraphSpecDigest) {
        if (!StringUtils.hasText(traceId)
                || !StringUtils.hasText(expectedGraphSpecDigest)) {
            return false;
        }
        try {
            Map<String, Object> response = responseMap(
                    runtimeClient.workflowAiCodingRunDetail(
                            workflowId, traceId),
                    "Workflow candidate run detail");
            Map<String, Object> detail = map(response.get("detail"));
            Map<String, Object> snapshot = map(detail.get("snapshot"));
            Map<String, Object> snapshotFacts = map(snapshot.get("snapshot"));
            return expectedGraphSpecDigest.equalsIgnoreCase(
                    text(snapshotFacts.get("graphSpecDigest")));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private TaskTargetView primaryTarget(TaskDescriptor task) {
        return task.targets().stream()
                .filter(target -> "PRIMARY".equals(target.targetRole()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "primary RUNTIME_RUN target is required"));
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "workflow.graphSpec is not valid JSON", ex);
        }
    }

    private Map<String, Object> responseMap(
            ResponseEntity<?> response,
            String label) {
        Map<String, Object> body = response == null
                ? Map.of()
                : map(response.getBody());
        if (body.isEmpty()) {
            throw new IllegalStateException("Runtime did not return " + label);
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        return new LinkedHashMap<>((Map<String, Object>) raw);
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String taskScopedKeySlug(String requested, String taskId) {
        String base = firstText(requested, "trace-workflow-candidate")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^[-_]+|[-_]+$", "");
        if (base.length() < 2) base = "trace-workflow-candidate";
        String suffix = taskId.replaceFirst("^ait_", "");
        if (suffix.length() > 12) suffix = suffix.substring(0, 12);
        int maxBaseLength = 128 - suffix.length() - 1;
        if (base.length() > maxBaseLength) {
            base = base.substring(0, maxBaseLength)
                    .replaceAll("[-_]+$", "");
        }
        return base + "-" + suffix;
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }

    private static String text(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText(null);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void putText(ObjectNode target, String key, String value) {
        if (StringUtils.hasText(value)) target.put(key, value.trim());
    }

    private record CandidateDraftState(
            boolean currentDraft,
            boolean valid,
            String message,
            JsonNode evidence,
            JsonNode validation) {
    }
}

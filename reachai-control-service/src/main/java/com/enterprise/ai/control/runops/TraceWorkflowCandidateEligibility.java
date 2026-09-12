package com.enterprise.ai.control.runops;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetView;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.aicoding.provider.AiCodingArtifactApplicationUnconfirmedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed eligibility boundary for mining one Agent run into a Workflow
 * candidate. Runtime remains the owner of execution facts; Control only reads
 * the public RunOps projection and records why a selected trace is, or is not,
 * safe for the first read-only pilot.
 */
@Service
@RequiredArgsConstructor
public class TraceWorkflowCandidateEligibility {

    public static final String ELIGIBILITY_SCHEMA =
            "reachai.runops.trace-workflow-candidate-eligibility.v1";
    public static final String PRIMARY_TARGET_TYPE = "RUNTIME_RUN";

    private static final Set<String> UNSAFE_RISK_LEVELS = Set.of(
            "PAGE_ACTION", "WRITE", "IRREVERSIBLE");

    private final RuntimeProxyClient runtimeClient;
    private final ObjectMapper objectMapper;

    public EligibilityView evaluate(String traceId) {
        String requiredTraceId = requireText(traceId, "traceId");
        ResponseEntity<Map<String, Object>> response =
                runtimeClient.runOpsDetail(requiredTraceId);
        Map<String, Object> detail = response == null ? null : response.getBody();
        if (response == null || !response.getStatusCode().is2xxSuccessful() || detail == null || detail.isEmpty()) {
            throw new AiCodingArtifactApplicationUnconfirmedException(
                    "Runtime did not return the selected trace");
        }
        return evaluate(detail, requiredTraceId);
    }

    EligibilityView evaluate(Map<String, Object> detail, String expectedTraceId) {
        Map<String, Object> summary = map(detail.get("summary"));
        String traceId = text(summary.get("traceId"));
        if (!expectedTraceId.equals(traceId)) {
            throw new AiCodingArtifactApplicationUnconfirmedException(
                    "Runtime did not confirm the selected trace identity");
        }

        List<String> blockers = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        requireEquals(summary, "status", "COMPLETED",
                "仅已完成运行可生成候选", blockers);
        requireEquals(summary, "runType", "AGENT",
                "首批候选仅支持 Agent 运行", blockers);
        requireEquals(summary, "runtimeType", "AGENTSCOPE",
                "候选必须来自 AgentScope 主运行链路", blockers);
        requireCount(summary, "planCount", 1,
                "候选必须恰好包含一次规划", blockers);
        requireCount(summary, "replanCount", 0,
                "发生过重规划的轨迹暂不支持候选化", blockers);
        requireCount(summary, "workflowCallCount", 1,
                "候选必须恰好调用一个 Workflow-as-Tool", blockers);
        requireCount(summary, "approvalCount", 0,
                "发生过人工审批的轨迹暂不支持候选化", blockers);
        requireCount(summary, "guardDenyCount", 0,
                "存在治理拒绝的轨迹不能生成候选", blockers);
        if (StringUtils.hasText(text(summary.get("suspensionReason")))) {
            blockers.add("曾暂停等待交互或审批的轨迹暂不支持候选化");
        }
        String projectCode = text(summary.get("projectCode"));
        if (!StringUtils.hasText(projectCode)) {
            blockers.add("轨迹缺少 projectCode，无法建立项目治理范围");
        }

        List<Map<String, Object>> toolCalls = maps(detail.get("toolCalls"));
        if (toolCalls.isEmpty()) {
            blockers.add("轨迹没有结构化工具调用证据");
        } else if (toolCalls.stream().anyMatch(call -> !booleanValue(call.get("success")))) {
            blockers.add("轨迹包含失败的工具调用");
        } else {
            evidence.add("所有结构化工具调用均成功");
        }

        List<Map<String, Object>> guards = maps(detail.get("guardDecisions"));
        List<Map<String, Object>> workflowGuards = guards.stream()
                .filter(this::isWorkflowToolGuard)
                .toList();
        if (workflowGuards.size() != 1) {
            blockers.add("必须存在且仅存在一个 Workflow-as-Tool 治理决策");
        } else {
            Map<String, Object> guard = workflowGuards.get(0);
            if (!"ALLOW".equalsIgnoreCase(text(guard.get("decision")))) {
                blockers.add("Workflow-as-Tool 治理决策不是 ALLOW");
            }
            Map<String, Object> metadata = map(guard.get("metadata"));
            if (!Boolean.TRUE.equals(strictBoolean(metadata.get("readOnly")))) {
                blockers.add("治理证据未显式证明 Workflow-as-Tool 为只读");
            }
            String riskLevel = text(metadata.get("riskLevel"));
            if (!StringUtils.hasText(riskLevel)) {
                blockers.add("治理证据缺少 riskLevel");
            } else if (UNSAFE_RISK_LEVELS.contains(
                    riskLevel.trim().toUpperCase(Locale.ROOT))) {
                blockers.add("治理风险等级 " + riskLevel + " 不属于只读候选范围");
            }
            if (Boolean.TRUE.equals(strictBoolean(metadata.get("readOnly")))
                    && StringUtils.hasText(riskLevel)
                    && !UNSAFE_RISK_LEVELS.contains(
                    riskLevel.trim().toUpperCase(Locale.ROOT))) {
                evidence.add("Guard 明确记录 readOnly=true，riskLevel=" + riskLevel);
            }
        }
        if (guards.stream().anyMatch(guard ->
                Set.of("DENY", "BLOCK", "WAITING_APPROVAL").contains(
                        upper(guard.get("decision"))))) {
            blockers.add("轨迹包含拒绝、阻断或等待审批的治理决策");
        }

        List<Map<String, Object>> workflowSpans = maps(detail.get("spans"))
                .stream()
                .filter(span -> "WORKFLOW_TOOL".equalsIgnoreCase(
                        text(span.get("spanType"))))
                .toList();
        WorkflowSource source = workflowSource(workflowSpans, blockers);
        if (source != null) {
            evidence.add("精确定位源 Workflow 版本 "
                    + source.workflowId() + "/#" + source.workflowVersionId());
        }

        List<Map<String, Object>> executionPath = maps(detail.get("executionPath"));
        long workflowNodeCount = executionPath.stream()
                .filter(item -> "WORKFLOW_NODE".equalsIgnoreCase(
                        text(item.get("spanType"))))
                .count();
        if (workflowNodeCount == 0) {
            blockers.add("轨迹没有实际执行的 Workflow 节点路径");
        } else if (executionPath.stream().anyMatch(item ->
                !"SUCCESS".equalsIgnoreCase(text(item.get("status"))))) {
            blockers.add("执行路径包含非 SUCCESS 阶段");
        } else {
            evidence.add("记录了 " + workflowNodeCount + " 个成功的 Workflow 节点路径");
        }

        ObjectNode facts = objectMapper.createObjectNode();
        copyText(summary, facts, "traceId");
        copyText(summary, facts, "projectCode");
        copyText(summary, facts, "agentId");
        copyText(summary, facts, "agentKeySlug");
        copyText(summary, facts, "entryType");
        copyText(summary, facts, "runtimeType");
        copyNumber(summary, facts, "agentConfigVersionId");
        copyNumber(summary, facts, "agentConfigVersion");
        copyNumber(summary, facts, "workflowCallCount");
        copyNumber(summary, facts, "toolCallCount");
        copyNumber(summary, facts, "planCount");
        copyNumber(summary, facts, "replanCount");
        if (source != null) {
            facts.put("sourceWorkflowId", source.workflowId());
            facts.put("sourceWorkflowVersionId", source.workflowVersionId());
            putText(facts, "sourceWorkflowVersion", source.workflowVersion());
            putText(facts, "sourceWorkflowToolName", source.toolName());
        }
        facts.put("workflowNodeCount", workflowNodeCount);

        return new EligibilityView(
                ELIGIBILITY_SCHEMA,
                traceId,
                projectCode,
                blockers.isEmpty(),
                List.copyOf(new LinkedHashSet<>(blockers)),
                List.copyOf(evidence),
                source == null ? null : source.workflowId(),
                source == null ? null : source.workflowVersionId(),
                source == null ? null : source.workflowVersion(),
                facts);
    }

    public JsonNode buildTaskContext(TaskTargetView primaryTarget) {
        if (primaryTarget == null
                || !PRIMARY_TARGET_TYPE.equals(primaryTarget.targetType())) {
            throw new IllegalArgumentException(
                    "primary RUNTIME_RUN target is required");
        }
        EligibilityView eligibility = evaluate(primaryTarget.targetKey());
        if (!eligibility.eligible()) {
            throw new IllegalArgumentException(
                    "selected trace is not eligible: "
                            + String.join("; ", eligibility.blockers()));
        }

        Map<String, Object> detail = body(runtimeClient.runOpsDetail(
                eligibility.traceId()), "selected trace");
        List<Map<String, Object>> versions = responseList(
                runtimeClient.listWorkflowVersions(
                        eligibility.sourceWorkflowId()),
                "source Workflow versions");
        Map<String, Object> sourceVersion = versions.stream()
                .filter(version -> eligibility.sourceWorkflowVersionId()
                        .equals(longValue(version.get("id"))))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "source Workflow version snapshot is no longer available"));

        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema", "reachai.ai-coding.trace-workflow-candidate-context.v1");
        root.set("eligibility", objectMapper.valueToTree(eligibility));

        ObjectNode source = root.putObject("source");
        source.set("runSummary", objectMapper.valueToTree(detail.get("summary")));
        source.set("executionPath", objectMapper.valueToTree(detail.get("executionPath")));
        source.set("toolCalls", objectMapper.valueToTree(detail.get("toolCalls")));
        source.set("guardDecisions", objectMapper.valueToTree(detail.get("guardDecisions")));
        source.set("sourceWorkflowVersion", objectMapper.valueToTree(sourceVersion));

        ObjectNode scope = root.putObject("scope");
        scope.put("accessMode", "READ_WRITE");
        scope.putArray("includedRules")
                .add("Use only the selected successful read-only run and its exact published Workflow version as evidence.")
                .add("Generalize the observed causal path into one reusable GraphSpec while preserving registered capability identities.")
                .add("Return a DRAFT Workflow candidate; Runtime validation is authoritative.");
        scope.putArray("excludedRules")
                .add("Do not publish, attach to an Agent, change production routing, or claim browser/E2E acceptance.")
                .add("Do not add PAGE_ACTION, write, irreversible, approval, authentication, authorization, credential, or identity behavior.")
                .add("Do not invent tools, parameters, model ids, outcomes, or hidden trace data.");
        scope.putArray("requiredChecks")
                .add("sourceTraceId must equal the primary RUNTIME_RUN target.")
                .add("GraphSpec must pass Runtime release validation before a DRAFT is created.")
                .add("The result must retain source trace and Workflow version lineage in extra metadata.")
                .add("Publish and human acceptance remain separate explicit operations.");

        ObjectNode instructions = root.putObject("instructions");
        instructions.put("objective",
                "将用户选择的成功只读 Agent 轨迹整理为可审阅、可重放、但未发布的 Workflow 候选。") ;
        instructions.put("language",
                "Human-readable names, descriptions, summary and acceptance criteria use concise Simplified Chinese; technical identifiers remain unchanged.");
        return root;
    }

    private WorkflowSource workflowSource(
            List<Map<String, Object>> spans,
            List<String> blockers) {
        if (spans.size() != 1) {
            blockers.add("必须存在且仅存在一个 WORKFLOW_TOOL span");
            return null;
        }
        Map<String, Object> span = spans.get(0);
        Map<String, Object> metadata = map(span.get("metadata"));
        String workflowId = text(metadata.get("workflowId"));
        Long versionId = longValue(metadata.get("workflowVersionId"));
        String version = text(metadata.get("workflowVersion"));
        if (!StringUtils.hasText(workflowId)
                || versionId == null
                || !StringUtils.hasText(version)) {
            blockers.add("WORKFLOW_TOOL span 缺少精确的 Workflow 版本身份");
            return null;
        }
        if (!"SUCCESS".equalsIgnoreCase(text(span.get("status")))) {
            blockers.add("WORKFLOW_TOOL span 未成功完成");
        }
        return new WorkflowSource(
                workflowId,
                versionId,
                version,
                text(span.get("toolName")));
    }

    private boolean isWorkflowToolGuard(Map<String, Object> guard) {
        return "SUPERVISOR_TOOL_POLICY".equalsIgnoreCase(
                text(guard.get("decisionType")))
                && "WORKFLOW_TOOL".equalsIgnoreCase(
                text(guard.get("targetKind")));
    }

    private void requireEquals(
            Map<String, Object> summary,
            String key,
            String expected,
            String message,
            List<String> blockers) {
        if (!expected.equalsIgnoreCase(text(summary.get(key)))) {
            blockers.add(message);
        }
    }

    private void requireCount(
            Map<String, Object> summary,
            String key,
            int expected,
            String message,
            List<String> blockers) {
        if (intValue(summary.get(key), -1) != expected) {
            blockers.add(message);
        }
    }

    private Map<String, Object> body(
            ResponseEntity<Map<String, Object>> response,
            String label) {
        Map<String, Object> body = response == null ? null : response.getBody();
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("Runtime did not return " + label);
        }
        return body;
    }

    private List<Map<String, Object>> responseList(
            ResponseEntity<Object> response,
            String label) {
        Object body = response == null ? null : response.getBody();
        List<Map<String, Object>> result = maps(body);
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Runtime did not return " + label);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        return (Map<String, Object>) new LinkedHashMap<>(
                (Map<String, Object>) raw);
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream()
                .filter(Map.class::isInstance)
                .map(this::map)
                .toList();
    }

    private static boolean booleanValue(Object value) {
        return Boolean.TRUE.equals(strictBoolean(value));
    }

    private static Boolean strictBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text.trim())) return true;
            if ("false".equalsIgnoreCase(text.trim())) return false;
        }
        return null;
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String upper(Object value) {
        String text = text(value);
        return text == null ? "" : text.trim().toUpperCase(Locale.ROOT);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void copyText(
            Map<String, Object> source,
            ObjectNode target,
            String key) {
        putText(target, key, text(source.get(key)));
    }

    private static void putText(ObjectNode target, String key, String value) {
        if (StringUtils.hasText(value)) target.put(key, value.trim());
    }

    private static void copyNumber(
            Map<String, Object> source,
            ObjectNode target,
            String key) {
        Object value = source.get(key);
        if (value instanceof Number number) target.put(key, number.longValue());
    }

    private record WorkflowSource(
            String workflowId,
            Long workflowVersionId,
            String workflowVersion,
            String toolName) {
    }

    public record EligibilityView(
            String schema,
            String traceId,
            String projectCode,
            boolean eligible,
            List<String> blockers,
            List<String> evidence,
            String sourceWorkflowId,
            Long sourceWorkflowVersionId,
            String sourceWorkflowVersion,
            JsonNode facts) {
    }
}

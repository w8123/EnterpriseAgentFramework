package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeWriteClient;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeSubWorkflowPort;
import com.enterprise.ai.runtime.execution.context.WorkflowVariableNamespaces;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import com.enterprise.ai.runtime.execution.policy.RuntimeEvalExecutionContext;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SUB_WORKFLOW and KNOWLEDGE_WRITE: nodes that reach another workflow or another service. */
final class RuntimeIntegrationNodeHandlers {

    static final int MAX_SUB_WORKFLOW_DEPTH = 3;
    static final String SUB_WORKFLOW_STACK_KEY = "__subWorkflowStack";
    private static final String TRUSTED_IDENTITY_CONTEXT_KEY = RuntimeTrustedExecutionContexts.IDENTITY_CONTEXT_KEY;
    private static final String TRUSTED_EVAL_CONTEXT_KEY = RuntimeTrustedExecutionContexts.EVAL_CONTEXT_KEY;

    /** Runs a child graph inside the current engine without emitting parent events. */
    @FunctionalInterface
    interface ChildGraphRunner {
        RuntimeGraphSpecExecutionResult run(String graphSpecJson,
                                            Map<String, Object> request,
                                            RuntimeGraphSpecExecutionCancellation cancellation,
                                            WorkflowExecutionIdentity identity,
                                            RuntimeEvalExecutionContext evalContext);
    }

    private final RuntimeNodeValueResolver nodeValueResolver;
    private final ObjectMapper objectMapper;
    private final ChildGraphRunner childRunner;
    private RuntimeSubWorkflowPort subWorkflowPort;
    private RuntimeKnowledgeWriteClient knowledgeWriteClient;

    RuntimeIntegrationNodeHandlers(RuntimeNodeValueResolver nodeValueResolver,
                                   ObjectMapper objectMapper,
                                   ChildGraphRunner childRunner) {
        this.nodeValueResolver = nodeValueResolver;
        this.objectMapper = objectMapper;
        this.childRunner = childRunner;
    }

    /** Optional: without it SUB_WORKFLOW fails closed. Set once while wiring the executor bean. */
    public void setSubWorkflowPort(RuntimeSubWorkflowPort subWorkflowPort) {
        this.subWorkflowPort = subWorkflowPort;
    }

    /**
     * Runs a pinned published workflow synchronously with the caller's identity, cancellation and eval policy.
     * The child sees only the mapped inputs (as params) and the caller's sys values; its final answer and
     * structured output become this node's output. A child that would pause fails this node instead.
     */
    @SuppressWarnings("unchecked")
    RuntimeGraphSpecExecutionResult executeSubWorkflow(GraphSpec.Node node, RuntimeNodeExecutionContext execution) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("subWorkflowConfig"));
        if (nested != null) {
            for (String key : List.of("workflowId", "versionId")) {
                if (config.get(key) != null && nested.get(key) != null
                        && !java.util.Objects.equals(text(config.get(key)), text(nested.get(key)))) {
                    return failure("RUNTIME_GRAPH_SUB_WORKFLOW_REFERENCE_CONFLICT", "子流程版本配置冲突，请重新选择",
                            node.getId(), "SUB_WORKFLOW");
                }
            }
        }
        Map<String, Object> merged = new LinkedHashMap<>(nested == null ? Map.of() : nested);
        config.forEach((key, value) -> {
            if (!"subWorkflowConfig".equals(key) && value != null) merged.put(key, value);
        });
        String workflowId = text(merged.get("workflowId"));
        Long versionId = merged.get("versionId") instanceof Number number ? number.longValue()
                : parseLong(text(merged.get("versionId")));
        if (!StringUtils.hasText(workflowId) || versionId == null) {
            return failure("RUNTIME_GRAPH_SUB_WORKFLOW_REQUIRED", "子流程需要选择一个已发布的工作流版本",
                    node.getId(), "SUB_WORKFLOW");
        }
        if (subWorkflowPort == null) {
            return failure("RUNTIME_GRAPH_SUB_WORKFLOW_UNAVAILABLE", "当前环境不能调用子流程", node.getId(), "SUB_WORKFLOW");
        }
        Map<String, Object> context = execution.variables();
        List<String> stack = context.get(SUB_WORKFLOW_STACK_KEY) instanceof List<?> raw
                ? raw.stream().map(String::valueOf).toList() : List.of();
        if (stack.contains(workflowId)) {
            return failure("RUNTIME_GRAPH_SUB_WORKFLOW_CYCLE", "子流程不能调用自己（循环调用）", node.getId(), "SUB_WORKFLOW");
        }
        if (stack.size() >= MAX_SUB_WORKFLOW_DEPTH) {
            return failure("RUNTIME_GRAPH_SUB_WORKFLOW_TOO_DEEP", "子流程最多嵌套 " + MAX_SUB_WORKFLOW_DEPTH + " 层",
                    node.getId(), "SUB_WORKFLOW");
        }
        String childGraph;
        try {
            childGraph = subWorkflowPort.resolvePublishedGraph(workflowId, versionId);
        } catch (RuntimeException ex) {
            return failure("RUNTIME_GRAPH_SUB_WORKFLOW_NOT_FOUND",
                    "子流程版本不可用：" + firstText(ex.getMessage(), "未找到"), node.getId(), "SUB_WORKFLOW");
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        Object rawInputs = merged.get("inputs");
        if (rawInputs instanceof Map<?, ?> map) {
            map.forEach((key, value) -> inputs.put(String.valueOf(key), nodeValueResolver.renderInputValue(value, context)));
        }
        Map<String, Object> request = new LinkedHashMap<>(inputs);
        request.put("params", new LinkedHashMap<>(inputs));
        String message = text(inputs.get("message"));
        request.put("message", message == null ? "" : message);
        if (context.get(WorkflowVariableNamespaces.SYS_ROOT) instanceof Map<?, ?> sys) {
            request.put(WorkflowVariableNamespaces.SYS_ROOT, new LinkedHashMap<>((Map<String, Object>) sys));
        }
        List<String> childStack = new ArrayList<>(stack);
        childStack.add(workflowId);
        request.put(SUB_WORKFLOW_STACK_KEY, childStack);
        request.put(RuntimeInvocationScope.KEY, RuntimeInvocationScope.from(context).child("sub:" + node.getId()));
        Object identity = context.get(TRUSTED_IDENTITY_CONTEXT_KEY);
        Object eval = context.get(TRUSTED_EVAL_CONTEXT_KEY);
        RuntimeGraphSpecExecutionResult child = childRunner.run(childGraph, request, execution.cancellation(),
                identity instanceof WorkflowExecutionIdentity trusted ? trusted : WorkflowExecutionIdentity.untrustedDebug(),
                eval instanceof RuntimeEvalExecutionContext evalContext ? evalContext : RuntimeEvalExecutionContext.none());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeId", node.getId());
        metadata.put("nodeType", "SUB_WORKFLOW");
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("workflowId", workflowId);
        trace.put("versionId", versionId);
        trace.put("childCode", child.code());
        trace.put("childSteps", child.steps() == null ? 0 : child.steps().size());
        metadata.put("traceSummary", trace);
        if (child.metadata() != null && child.metadata().get("workflowNodeTraces") instanceof List<?> traces) {
            metadata.put("subWorkflowTraces", traces);
        }
        if ("RUNTIME_GRAPH_CANCELLED".equals(child.code())) {
            return new RuntimeGraphSpecExecutionResult(false, "RUNTIME_GRAPH_CANCELLED", "Workflow execution cancelled",
                    node.getId(), "SUB_WORKFLOW", List.of(), metadata);
        }
        if (child.isWaitingUser() || WorkflowInteractionCodes.WAITING.equals(child.code())) {
            return new RuntimeGraphSpecExecutionResult(false, "RUNTIME_GRAPH_SUB_WORKFLOW_WAITING",
                    "子流程需要人工确认或输入，不能在子流程里暂停；请直接调用该工作流", node.getId(), "SUB_WORKFLOW",
                    List.of(), metadata);
        }
        if (!child.success()) {
            if (child.metadata() != null && Boolean.TRUE.equals(child.metadata().get("nonRecoverableWrite"))) {
                metadata.put("nonRecoverableWrite", true);
                metadata.put("dispatchStage", "UNKNOWN");
            }
            return new RuntimeGraphSpecExecutionResult(false, firstText(child.code(), "RUNTIME_GRAPH_SUB_WORKFLOW_FAILED"),
                    "子流程失败：" + firstText(child.answer(), child.code()), node.getId(), "SUB_WORKFLOW",
                    List.of(), metadata);
        }
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("answer", child.answer());
        Object childOutput = child.metadata() == null ? null : child.metadata().get("structuredOutput");
        structured.put("output", childOutput);
        metadata.put("structuredOutput", structured);
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", "execute-node");
        step.put("detail", node.getId());
        return new RuntimeGraphSpecExecutionResult(true, "RUNTIME_GRAPH_EXECUTED", child.answer(), node.getId(),
                "SUB_WORKFLOW", List.of(step), metadata);
    }

    public void setKnowledgeWriteClient(RuntimeKnowledgeWriteClient knowledgeWriteClient) {
        this.knowledgeWriteClient = knowledgeWriteClient;
    }

    /**
     * Writes rendered text into a knowledge base of the same project. This is a write: evaluation runs skip it,
     * and a retry of the same run and node replaces the earlier document instead of duplicating it.
     */
    RuntimeGraphSpecExecutionResult executeKnowledgeWrite(GraphSpec.Node node, RuntimeNodeExecutionContext execution) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("knowledgeWriteConfig"));
        Map<String, Object> merged = new LinkedHashMap<>(nested == null ? Map.of() : nested);
        config.forEach((key, value) -> {
            if (!"knowledgeWriteConfig".equals(key) && value != null) merged.put(key, value);
        });
        Map<String, Object> context = execution.variables();
        String kbCode = text(merged.get("knowledgeBaseCode"));
        String content = textOf(nodeValueResolver.renderInputValue(
                firstText(text(merged.get("content")), text(merged.get("contentExpression")), "lastOutput"), context));
        String title = textOf(nodeValueResolver.renderInputValue(
                firstText(text(merged.get("title")), text(merged.get("titleExpression")), node.getName(), "工作流写入"), context));
        if (!StringUtils.hasText(kbCode)) {
            return failure("RUNTIME_GRAPH_KNOWLEDGE_WRITE_KB_REQUIRED", "写知识库需要选择一个知识库", node.getId(), "KNOWLEDGE_WRITE");
        }
        if (!StringUtils.hasText(content)) {
            return failure("RUNTIME_GRAPH_KNOWLEDGE_WRITE_EMPTY", "要写入知识库的内容为空", node.getId(), "KNOWLEDGE_WRITE");
        }
        Object eval = context.get(TRUSTED_EVAL_CONTEXT_KEY);
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("knowledgeBaseCode", kbCode);
        structured.put("title", title);
        structured.put("length", content.length());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeId", node.getId());
        metadata.put("nodeType", "KNOWLEDGE_WRITE");
        if (eval instanceof RuntimeEvalExecutionContext evalContext && evalContext.isEvaluation()) {
            structured.put("written", false);
            structured.put("skippedReason", "EVALUATION");
            metadata.put("structuredOutput", structured);
            return new RuntimeGraphSpecExecutionResult(true, "RUNTIME_GRAPH_EXECUTED", "评测运行不写知识库",
                    node.getId(), "KNOWLEDGE_WRITE", List.of(step("execute-node", node.getId())), metadata);
        }
        if (knowledgeWriteClient == null) {
            return failure("RUNTIME_GRAPH_KNOWLEDGE_WRITE_UNAVAILABLE", "当前环境不能写知识库", node.getId(), "KNOWLEDGE_WRITE");
        }
        WorkflowExecutionIdentity identity = RuntimeTrustedExecutionContexts.identity(context);
        if (!identity.projectTrusted() || !StringUtils.hasText(identity.projectCode())
                || identity.source() == WorkflowExecutionIdentity.Source.STUDIO_PROJECT_TEST) {
            return failure("RUNTIME_GRAPH_KNOWLEDGE_WRITE_IDENTITY_REQUIRED",
                    "写知识库需要可信项目身份", node.getId(), "KNOWLEDGE_WRITE");
        }
        String projectCode = identity.projectCode();
        RuntimeInvocationScope scope = RuntimeInvocationScope.from(context);
        String runKey = scope.runId() + scope.path() + ":" + node.getId();
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(runKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            runKey = java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
        RuntimeKnowledgeWriteClient.WriteResult response;
        try {
            response = knowledgeWriteClient.writeText(new RuntimeKnowledgeWriteClient.WriteTextRequest(
                    kbCode, content, title, projectCode, runKey));
        } catch (RuntimeException ex) {
            Map<String, Object> failed = new LinkedHashMap<>(metadata);
            failed.put("nonRecoverableWrite", true);
            failed.put("dispatchStage", "UNKNOWN");
            return new RuntimeGraphSpecExecutionResult(false, "RUNTIME_GRAPH_KNOWLEDGE_WRITE_FAILED",
                    "写知识库失败，结果未知，请到知识库确认", node.getId(), "KNOWLEDGE_WRITE", List.of(), failed);
        }
        if (response == null || response.code() != 200 && response.code() != 0) {
            return failure("RUNTIME_GRAPH_KNOWLEDGE_WRITE_REJECTED",
                    "知识库拒绝写入：" + firstText(response == null ? null : response.message(), "未知原因"),
                    node.getId(), "KNOWLEDGE_WRITE");
        }
        structured.put("written", true);
        if (response.data() != null) {
            structured.put("fileId", response.data().get("fileId"));
            structured.put("chunkCount", response.data().get("chunkCount"));
        }
        metadata.put("structuredOutput", structured);
        metadata.put("traceSummary", Map.of("knowledgeBaseCode", kbCode, "length", content.length()));
        return new RuntimeGraphSpecExecutionResult(true, "RUNTIME_GRAPH_EXECUTED", "已写入知识库 " + kbCode,
                node.getId(), "KNOWLEDGE_WRITE", List.of(step("execute-node", node.getId())), metadata);
    }


    private String textOf(Object value) {
        if (value == null) return null;
        if (value instanceof CharSequence text) return text.toString();
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private static Long parseLong(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private RuntimeGraphSpecExecutionResult failure(String code, String answer, String nodeId, String nodeType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (StringUtils.hasText(nodeId)) metadata.put("nodeId", nodeId);
        if (StringUtils.hasText(nodeType)) metadata.put("nodeType", nodeType);
        return new RuntimeGraphSpecExecutionResult(false, code, answer, nodeId, nodeType, List.of(), metadata);
    }

    private static Map<String, Object> step(String name, String detail) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("detail", detail);
        return step;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value;
        }
        return null;
    }
}

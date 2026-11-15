package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigService;
import com.enterprise.ai.runtime.agent.RuntimeAgentService;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowAgentAttachmentPort;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowAgentAttachmentPort.AttachRequest;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowAgentAttachmentPort.AttachmentResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowManagementService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.WorkflowSemanticValues;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reasoning → fixed flow. When the assistant has answered the same kind of request more than once by
 * reasoning its way to one read-only business capability, that path can become a direct-flow Workflow:
 * {@code user input → capability → answer from the result}. The draft is ordinary Studio content; it runs
 * for employees only after a person enables it, which publishes it and attaches it to the Agent.
 */
@Service
@RequiredArgsConstructor
public class RuntimeLearnedFlowService {

    static final String SOURCE = "LEARNED_FLOW";
    static final int MIN_RUNS = 2;
    private static final int SPAN_LIMIT = 2000;
    private static final String VERSION = "v1.0.0";

    private final RuntimeAgentService agentService;
    private final RuntimeAgentConfigService agentConfigService;
    private final RuntimeTraceSpanMapper spanMapper;
    private final RuntimeCapabilityCatalogClient catalog;
    private final RuntimeWorkflowManagementService workflows;
    private final RuntimeWorkflowAiCodingService workflowAiCodingService;
    private final RuntimeWorkflowAgentAttachmentPort attachmentPort;
    private final ObjectMapper objectMapper;

    /** Requests the assistant keeps solving by reasoning to one read-only capability, most frequent first. */
    public List<CandidateView> candidates(String agentId, Integer days) {
        RuntimeAgentView agent = requireAgent(agentId);
        int window = Math.max(1, Math.min(90, days == null ? 30 : days));
        List<Map<String, Object>> rows = spanMapper.selectCapabilityCalls(
                agent.id(), LocalDateTime.now().minusDays(window), SPAN_LIMIT);
        // One capability call per run only: a run that needed several steps is not a single-step flow.
        Map<String, List<Map<String, Object>>> byTrace = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byTrace.computeIfAbsent(text(value(row, "traceId")), key -> new ArrayList<>()).add(row);
        }
        Map<String, Stats> stats = new LinkedHashMap<>();
        for (List<Map<String, Object>> calls : byTrace.values()) {
            if (calls.size() != 1) continue;
            Map<String, Object> call = calls.get(0);
            JsonNode outcome = json(text(value(call, "outputSummary")));
            String sourceRef = outcome.path("sourceRef").asText("");
            if (!outcome.path("readOnly").asBoolean(false) || outcome.path("approvalPending").asBoolean(false)
                    || !"CAPABILITY".equalsIgnoreCase(outcome.path("sourceKind").asText(""))
                    || !StringUtils.hasText(sourceRef)) continue;
            Stats stat = stats.computeIfAbsent(sourceRef.trim(), key -> new Stats());
            stat.runs++;
            Object latency = value(call, "runLatencyMs");
            if (latency instanceof Number number) stat.latencyTotal += number.longValue();
            String at = text(value(call, "startedAt"));
            if (stat.lastAt == null || (at != null && at.compareTo(stat.lastAt) > 0)) stat.lastAt = at;
        }
        Set<String> attached = attachedWorkflowIds(agent.id());
        List<CandidateView> views = new ArrayList<>();
        stats.forEach((sourceRef, stat) -> {
            if (stat.runs < MIN_RUNS) return;
            RuntimeWorkflowDefinitionView flow = workflows.findByKeySlug(keySlug(sourceRef)).orElse(null);
            String title = sourceRef;
            try {
                title = firstText(text(catalog.getToolDefinition(sourceRef).get("title")), sourceRef);
            } catch (RuntimeException unavailable) {
                // The count is still worth showing; the draft step reports an unavailable capability.
            }
            views.add(new CandidateView(sourceRef, title, stat.runs, stat.runs == 0 ? 0 : stat.latencyTotal / stat.runs,
                    stat.lastAt, flow == null ? null : flow.getId(), flow == null ? null : flow.getStatus(),
                    flow != null && attached.contains(flow.getId())));
        });
        views.sort(Comparator.comparingInt(CandidateView::runCount).reversed());
        return views;
    }

    /**
     * Every Agent's repeated read-only requests that are not yet a flow attached to it, across one
     * business system (or all of them): the single 「待确认流程」 list an admin works through.
     */
    public List<PendingView> pending(String projectCode, Integer days) {
        List<PendingView> result = new ArrayList<>();
        for (RuntimeAgentView agent : agentService.list(null, StringUtils.hasText(projectCode) ? projectCode.trim() : null)) {
            if (!StringUtils.hasText(agent.projectCode())) continue;
            List<CandidateView> candidates;
            try {
                candidates = candidates(agent.id(), days);
            } catch (RuntimeException unavailable) {
                continue;
            }
            for (CandidateView candidate : candidates) {
                if (candidate.attached()) continue;
                result.add(new PendingView(agent.id(), agent.name(), agent.projectCode(), candidate));
            }
        }
        result.sort(Comparator.comparingInt((PendingView view) -> view.candidate().runCount()).reversed());
        return result;
    }

    /** Creates the draft flow for one capability (or returns the existing one). Nothing runs for employees yet. */
    @Transactional
    public FlowView draft(String agentId, FlowRequest request) {
        RuntimeAgentView agent = requireAgent(agentId);
        String sourceRef = requireText(request == null ? null : request.sourceRef(), "sourceRef");
        Map<String, Object> definition = readOnlyDefinition(agent, sourceRef);
        String title = firstText(text(definition.get("title")), sourceRef);
        RuntimeWorkflowDefinitionView existing = workflows.findByKeySlug(keySlug(sourceRef)).orElse(null);
        if (existing != null) {
            requireSameProject(agent, existing);
            return view(agent, existing, false, null, title);
        }
        String model = firstText(request.modelInstanceId(), activeAgentModel(agent.id()));
        if (!StringUtils.hasText(model)) {
            throw new IllegalArgumentException("没有可用的回答模型：请先给 Agent 配置模型");
        }
        var context = workflowAiCodingService.createWorkflow(new RuntimeWorkflowAiCodingService.CreateRequest(
                "直达流程：" + title,
                keySlug(sourceRef),
                agent.projectId(),
                agent.projectCode(),
                toolDescription(title),
                WorkflowSemanticValues.KIND_GENERAL,
                WorkflowSemanticValues.ENGINE_GRAPH_SPEC,
                model,
                graphSpec(definition, sourceRef, title, model),
                null,
                Map.of("source", SOURCE, "sourceRef", sourceRef),
                List.of(),
                "助手多次通过推理完成「" + title + "」，沉淀为直达流程"));
        RuntimeWorkflowDefinitionView created = workflows.findById(context.workflow().id())
                .orElseThrow(() -> new IllegalStateException("直达流程草稿创建失败"));
        return view(agent, created, true, null, title);
    }

    /** A person's confirmation: publish the flow (if not yet) and attach it to the Agent as a read-only tool. */
    @Transactional
    public FlowView enable(String agentId, FlowRequest request) {
        RuntimeAgentView agent = requireAgent(agentId);
        String sourceRef = requireText(request == null ? null : request.sourceRef(), "sourceRef");
        Map<String, Object> definition = readOnlyDefinition(agent, sourceRef);
        String title = firstText(text(definition.get("title")), sourceRef);
        String publishedBy = firstText(request.publishedBy(), "ReachAI 直达流程");
        RuntimeWorkflowDefinitionView workflow = workflows.findByKeySlug(keySlug(sourceRef))
                .orElseThrow(() -> new IllegalArgumentException("还没有生成流程草稿，请先生成"));
        requireSameProject(agent, workflow);
        if (!"ACTIVE".equalsIgnoreCase(workflow.getStatus())) {
            RuntimeWorkflowReleaseValidationResult validation = workflows.validateRelease(workflow.getId());
            if (!validation.valid()) {
                String reason = validation.errors().isEmpty() ? "UNKNOWN" : validation.errors().get(0).message();
                throw new IllegalArgumentException("流程校验未通过: " + reason);
            }
            int published = workflows.listVersions(workflow.getId()).size();
            workflows.publish(workflow.getId(), published == 0 ? VERSION : "v1.0." + published, 100,
                    "直达流程「" + title + "」启用", publishedBy, workflow.getUpdatedAt().toString());
        }
        AttachmentResult attachment = attachmentPort.attachInKnownProject(agent.projectId(), agent.projectCode(),
                new AttachRequest(workflow.getId(), agent.id(), null, null, publishedBy,
                        null, toolDescription(title), null, null, "READ", null, Boolean.TRUE, null, null, null));
        RuntimeWorkflowDefinitionView current = workflows.findById(workflow.getId()).orElse(workflow);
        return view(agent, current, false, attachment, title);
    }

    private Map<String, Object> readOnlyDefinition(RuntimeAgentView agent, String sourceRef) {
        Map<String, Object> definition;
        try {
            definition = catalog.getToolDefinition(sourceRef);
        } catch (RuntimeException unavailable) {
            throw new IllegalArgumentException("业务能力不存在或暂时不可用: " + sourceRef);
        }
        if (definition == null || definition.isEmpty()) {
            throw new IllegalArgumentException("业务能力不存在: " + sourceRef);
        }
        if (!agent.projectCode().equalsIgnoreCase(text(definition.get("projectCode")))) {
            throw new IllegalArgumentException("业务能力不属于这个助手所在的项目");
        }
        String sideEffect = firstText(text(definition.get("sideEffect")), "WRITE").toUpperCase(Locale.ROOT);
        if (!"READ_ONLY".equals(sideEffect) && !"READ".equals(sideEffect)) {
            throw new IllegalArgumentException("只有只读的业务能力可以沉淀为直达流程");
        }
        return definition;
    }

    GraphSpec graphSpec(Map<String, Object> definition, String sourceRef, String title, String model) {
        List<Map<String, Object>> fields = new ArrayList<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> inputMapping = new LinkedHashMap<>();
        fields.add(field("question", "string", true, "用户的完整问题"));
        properties.put("question", Map.of("type", "string", "description", "用户的完整问题"));
        for (JsonNode parameter : json(text(definition.get("parametersJson")))) {
            String name = parameter.path("name").asText("");
            String type = parameter.path("type").asText("string").toLowerCase(Locale.ROOT);
            String leaf = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
            if (!StringUtils.hasText(leaf) || "object".equals(type) || "array".equals(type)
                    || "question".equals(leaf) || inputMapping.containsKey(leaf)
                    || parameter.path("metadata").path("sensitive").asBoolean(false)) continue;
            String jsonType = Set.of("number", "integer", "boolean").contains(type) ? type : "string";
            String description = firstText(parameter.path("description").asText(""), leaf) + "；用户没有提到就不要填";
            fields.add(field(leaf, jsonType, false, description));
            properties.put(leaf, Map.of("type", jsonType, "description", description));
            inputMapping.put(leaf, "params." + leaf);
        }
        Map<String, Object> userInput = new LinkedHashMap<>();
        userInput.put("fields", fields);
        userInput.put("outputAlias", "params");

        Map<String, Object> toolConfig = new LinkedHashMap<>();
        toolConfig.put("ref", sourceRef);
        toolConfig.put("qualifiedName", sourceRef);
        toolConfig.put("maxRequestTimeMs", 180000);
        toolConfig.put("inputMapping", inputMapping);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("inputMapping", inputMapping);
        tool.put("maxRequestTimeMs", 180000);
        tool.put("toolConfig", toolConfig);

        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("modelInstanceId", model);
        llm.put("systemPrompt", "你是业务系统里的助手。只根据下面「查询结果」回答用户的问题，不要编造结果里没有的内容；"
                + "结果为空就直接说没有查到。数量、金额用结果里的数字，需要合计时自己算并写清楚。"
                + "回答用中文，简洁，多条记录用表格，不要输出 JSON，不要解释你是怎么查的。");
        llm.put("userPrompt", "问题：{{ params.question }}\n\n查询结果（" + title + "，JSON）：\n{{ nodeOutput.query }}");

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("template", "{{ nodeOutput.answer_llm }}");
        answer.put("directAnswer", true);

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", List.of("question"));
        inputSchema.put("additionalProperties", false);

        return GraphSpec.builder()
                .inputSchema(inputSchema)
                .entryNodeId("user_input")
                .exitNodeIds(List.of("answer"))
                .node(GraphSpec.Node.builder().id("user_input").type("USER_INPUT").name("用户问题")
                        .config(userInput).build())
                .node(GraphSpec.Node.builder().id("query").type("TOOL").name(title)
                        .ref(GraphSpec.CapabilityRef.builder().kind("TOOL")
                                .name(text(definition.get("name"))).qualifiedName(sourceRef)
                                .projectCode(text(definition.get("projectCode"))).build())
                        .config(tool).build())
                .node(GraphSpec.Node.builder().id("answer_llm").type("LLM").name("按结果回答")
                        .config(llm).build())
                .node(GraphSpec.Node.builder().id("answer").type("ANSWER").name("回复")
                        .config(answer).build())
                .edge(GraphSpec.Edge.builder().id("e_input_query").from("user_input").to("query")
                        .condition("always").build())
                .edge(GraphSpec.Edge.builder().id("e_query_llm").from("query").to("answer_llm")
                        .condition("always").build())
                .edge(GraphSpec.Edge.builder().id("e_llm_answer").from("answer_llm").to("answer")
                        .condition("always").build())
                .build();
    }

    private static Map<String, Object> field(String name, String type, boolean required, String description) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        field.put("key", name);
        field.put("type", type);
        field.put("required", required);
        field.put("description", description);
        field.put("source", "input." + name);
        return field;
    }

    private FlowView view(RuntimeAgentView agent, RuntimeWorkflowDefinitionView workflow, boolean created,
                          AttachmentResult attachment, String title) {
        boolean attached = attachment != null || attachedWorkflowIds(agent.id()).contains(workflow.getId());
        return new FlowView(workflow.getId(), workflow.getKeySlug(), workflow.getName(), workflow.getStatus(),
                created, attached, attachment == null ? null : attachment.toolName(),
                attachment == null || attachment.activeConfig() == null ? null : attachment.activeConfig().version(),
                title);
    }

    private Set<String> attachedWorkflowIds(String agentId) {
        Set<String> ids = new LinkedHashSet<>();
        for (var tool : agentConfigService.resolveActiveTools(agentId, agentConfigService.resolveActive(agentId).orElse(null))) {
            if (!Boolean.FALSE.equals(tool.getEnabled()) && StringUtils.hasText(tool.getWorkflowId())) {
                ids.add(tool.getWorkflowId());
            }
        }
        return ids;
    }

    private RuntimeAgentView requireAgent(String agentId) {
        RuntimeAgentView agent = agentService.findById(requireText(agentId, "agentId"))
                .orElseThrow(() -> new IllegalArgumentException("Agent 不存在: " + agentId));
        if (agent.projectId() == null || !StringUtils.hasText(agent.projectCode())) {
            throw new IllegalArgumentException("Agent 没有所属项目");
        }
        return agent;
    }

    private static void requireSameProject(RuntimeAgentView agent, RuntimeWorkflowDefinitionView workflow) {
        if (!Objects.equals(agent.projectId(), workflow.getProjectId())) {
            throw new IllegalArgumentException("同名流程属于其他项目: " + workflow.getKeySlug());
        }
    }

    private String activeAgentModel(String agentId) {
        return agentConfigService.resolveActive(agentId).map(config -> config.getModelInstanceId()).orElse(null);
    }

    static String keySlug(String sourceRef) {
        String slug = ("flow-" + sourceRef).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-").replaceAll("-+", "-").replaceAll("[-_]+$", "");
        return slug.length() > 128 ? slug.substring(0, 128).replaceAll("[-_]+$", "") : slug;
    }

    static String toolDescription(String title) {
        return title + "（只读直达流程）。用户的请求就是「" + title + "」时调用：question 传用户的完整问题，"
                + "其余参数只填用户明确提到的条件。流程的回复会原样作为最终回答。";
    }

    private JsonNode json(String value) {
        if (!StringUtils.hasText(value)) return objectMapper.createArrayNode();
        try {
            return objectMapper.readTree(value);
        } catch (Exception unreadable) {
            return objectMapper.createArrayNode();
        }
    }

    /** MyBatis map keys follow the column label; some drivers upper-case it. */
    private static Object value(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value != null ? value : row.get(key.toUpperCase(Locale.ROOT));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }

    private static final class Stats {
        private int runs;
        private long latencyTotal;
        private String lastAt;
    }

    public record FlowRequest(String sourceRef, String modelInstanceId, String publishedBy) {
    }

    public record PendingView(String agentId, String agentName, String projectCode, CandidateView candidate) {
    }

    public record CandidateView(String sourceRef, String title, int runCount, long avgLatencyMs, String lastAt,
                                String workflowId, String workflowStatus, boolean attached) {
    }

    public record FlowView(String workflowId, String workflowKeySlug, String workflowName, String workflowStatus,
                           boolean created, boolean attached, String toolName, Integer agentConfigVersion,
                           String title) {
    }
}

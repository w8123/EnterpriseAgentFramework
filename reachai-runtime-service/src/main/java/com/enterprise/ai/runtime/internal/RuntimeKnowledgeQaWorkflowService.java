package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigService;
import com.enterprise.ai.runtime.agent.RuntimeAgentService;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowFreshnessService;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowFreshnessViews.WorkflowAgentReferenceView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowAgentAttachmentPort;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowAgentAttachmentPort.AttachRequest;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowAgentAttachmentPort.AttachmentResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowManagementService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionView;
import com.enterprise.ai.runtime.workflow.WorkflowSemanticValues;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Knowledge base → Agent in one step: a template knowledge Q&A Workflow
 * (user input → required-evidence retrieval → grounded answer / fixed "not found")
 * is created and published once per project and knowledge base, then attached to
 * the Agent as a Workflow tool. The Workflow stays an ordinary Studio Workflow.
 */
@Service
@RequiredArgsConstructor
public class RuntimeKnowledgeQaWorkflowService {

    static final String SOURCE = "KNOWLEDGE_QA";
    static final String VERSION = "v1.0.0";

    private final RuntimeAgentService agentService;
    private final RuntimeAgentConfigService agentConfigService;
    private final RuntimeAgentWorkflowFreshnessService freshnessService;
    private final RuntimeWorkflowManagementService workflows;
    private final RuntimeWorkflowAiCodingService workflowAiCodingService;
    private final RuntimeWorkflowAgentAttachmentPort attachmentPort;
    private final ObjectMapper objectMapper;

    @Transactional
    public AttachView attach(String agentId, AttachKnowledgeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        String code = requireText(request.knowledgeBaseCode(), "knowledgeBaseCode");
        String kbName = defaultText(request.knowledgeBaseName(), code);
        String publishedBy = defaultText(request.publishedBy(), "ReachAI 知识库");
        RuntimeAgentView agent = agentService.findById(requireText(agentId, "agentId"))
                .orElseThrow(() -> new IllegalArgumentException("Agent 不存在: " + agentId));
        if (agent.projectId() == null || !StringUtils.hasText(agent.projectCode())) {
            throw new IllegalArgumentException("Agent 没有所属项目，无法接入知识库");
        }
        String model = firstText(request.modelInstanceId(), activeAgentModel(agent.id()));
        if (!StringUtils.hasText(model)) {
            throw new IllegalArgumentException("没有可用的回答模型：请先给 Agent 配置模型，或在请求里指定模型");
        }

        String keySlug = keySlug(agent.projectCode(), code);
        RuntimeWorkflowDefinitionView workflow = workflows.findByKeySlug(keySlug).orElse(null);
        boolean created = false;
        if (workflow == null) {
            var context = workflowAiCodingService.createWorkflow(new RuntimeWorkflowAiCodingService.CreateRequest(
                    "知识问答：" + kbName,
                    keySlug,
                    agent.projectId(),
                    agent.projectCode(),
                    toolDescription(kbName),
                    WorkflowSemanticValues.KIND_GENERAL,
                    WorkflowSemanticValues.ENGINE_GRAPH_SPEC,
                    model,
                    graphSpec(code, kbName, model),
                    null,
                    Map.of("source", SOURCE, "knowledgeBaseCode", code),
                    List.of(),
                    "知识库「" + kbName + "」接入 Agent"));
            workflow = workflows.findById(context.workflow().id())
                    .orElseThrow(() -> new IllegalStateException("知识问答 Workflow 创建失败"));
            created = true;
        } else if (!Objects.equals(agent.projectId(), workflow.getProjectId())) {
            throw new IllegalArgumentException("同名知识问答 Workflow 属于其他项目: " + keySlug);
        }

        if (!"ACTIVE".equalsIgnoreCase(workflow.getStatus())) {
            RuntimeWorkflowReleaseValidationResult validation =
                    workflows.validateRelease(workflow.getId());
            if (!validation.valid()) {
                String reason = validation.errors().isEmpty() ? "UNKNOWN" : validation.errors().get(0).message();
                throw new IllegalArgumentException("知识问答 Workflow 校验未通过: " + reason);
            }
            workflows.publish(workflow.getId(), nextVersion(workflow.getId()), 100,
                    "知识库「" + kbName + "」接入 Agent", publishedBy, workflow.getUpdatedAt().toString());
        }

        AttachmentResult attachment = attachmentPort.attach(agent.projectId(), new AttachRequest(
                workflow.getId(), agent.id(), null, null, publishedBy));
        return new AttachView(
                code,
                workflow.getId(),
                workflow.getKeySlug(),
                workflow.getName(),
                created,
                agent.id(),
                agent.name(),
                attachment.activeConfig() == null ? null : attachment.activeConfig().version(),
                attachment.toolName(),
                attachment.reused());
    }

    /** Workflows whose GraphSpec retrieves from the given knowledge bases, with the Agents running them. */
    public List<KnowledgeUsageView> usage(List<String> knowledgeBaseCodes) {
        Set<String> codes = new LinkedHashSet<>();
        for (String code : knowledgeBaseCodes == null ? List.<String>of() : knowledgeBaseCodes) {
            for (String part : String.valueOf(code).split(",")) {
                if (StringUtils.hasText(part)) {
                    codes.add(part.trim());
                }
            }
        }
        if (codes.isEmpty()) {
            return List.of();
        }
        Map<String, List<WorkflowUsageView>> byCode = new LinkedHashMap<>();
        codes.forEach(code -> byCode.put(code, new ArrayList<>()));
        for (RuntimeWorkflowDefinitionView workflow : workflows.list(null, null, null, null, null)) {
            Set<String> used = retrievedCodes(workflow.getGraphSpecJson());
            used.retainAll(codes);
            if (used.isEmpty()) {
                continue;
            }
            List<AgentUsageView> agents = new ArrayList<>();
            for (WorkflowAgentReferenceView reference : freshnessService.workflowReferences(workflow.getId()).agents()) {
                agents.add(new AgentUsageView(reference.agentId(), reference.agentName(), reference.projectCode()));
            }
            WorkflowUsageView view = new WorkflowUsageView(workflow.getId(), workflow.getName(),
                    workflow.getKeySlug(), workflow.getStatus(), workflow.getProjectId(),
                    workflow.getProjectCode(), List.copyOf(agents));
            used.forEach(code -> byCode.get(code).add(view));
        }
        List<KnowledgeUsageView> result = new ArrayList<>();
        byCode.forEach((code, workflows) -> result.add(new KnowledgeUsageView(code, List.copyOf(workflows))));
        return result;
    }

    private Set<String> retrievedCodes(String graphJson) {
        Set<String> codes = new LinkedHashSet<>();
        if (!StringUtils.hasText(graphJson)) {
            return codes;
        }
        try {
            JsonNode nodes = objectMapper.readTree(graphJson).path("nodes");
            for (JsonNode node : nodes) {
                if (!"KNOWLEDGE_RETRIEVAL".equals(node.path("type").asText())) {
                    continue;
                }
                collectCodes(node.path("config").path("knowledgeBaseCodes"), codes);
                collectCodes(node.path("config").path("knowledgeConfig").path("knowledgeBaseCodes"), codes);
            }
        } catch (Exception ignored) {
            // An unreadable draft graph simply does not count as a usage.
        }
        return codes;
    }

    private static void collectCodes(JsonNode values, Set<String> codes) {
        if (values.isArray()) {
            values.forEach(value -> {
                if (StringUtils.hasText(value.asText())) {
                    codes.add(value.asText().trim());
                }
            });
        }
    }

    private String activeAgentModel(String agentId) {
        return agentConfigService.resolveActive(agentId)
                .map(config -> config.getModelInstanceId())
                .orElse(null);
    }

    private String nextVersion(String workflowId) {
        List<RuntimeWorkflowVersionView> versions = workflows.listVersions(workflowId);
        if (versions.stream().noneMatch(version -> VERSION.equals(version.getVersion()))) {
            return VERSION;
        }
        return "v1.0." + versions.size();
    }

    static String keySlug(String projectCode, String knowledgeBaseCode) {
        String slug = ("kb-qa-" + projectCode + "-" + knowledgeBaseCode)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("[-_]+$", "");
        return slug.length() > 128 ? slug.substring(0, 128).replaceAll("[-_]+$", "") : slug;
    }

    static String toolDescription(String kbName) {
        return "查询知识库《" + kbName + "》里的制度、流程、说明等资料并据此回答。"
                + "用户问到这些资料涉及的内容时调用，question 传用户的完整问题；查不到时会如实说明资料里没有。";
    }

    static GraphSpec graphSpec(String code, String kbName, String model) {
        Map<String, Object> questionField = new LinkedHashMap<>();
        questionField.put("name", "question");
        questionField.put("key", "question");
        questionField.put("type", "string");
        questionField.put("required", true);
        questionField.put("description", "用户的完整问题");
        questionField.put("source", "input.question");
        Map<String, Object> userInput = new LinkedHashMap<>();
        userInput.put("fields", List.of(questionField));
        userInput.put("outputAlias", "params");

        Map<String, Object> retrieval = new LinkedHashMap<>();
        retrieval.put("knowledgeBaseCodes", List.of(code));
        retrieval.put("query", "{{ params.question }}");
        retrieval.put("evidencePolicy", "REQUIRED");
        retrieval.put("outputAlias", "hits");

        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("modelInstanceId", model);
        llm.put("systemPrompt", "你是企业知识助手。只根据下面「资料」回答用户问题，不要编造资料里没有的内容；"
                + "资料不足以回答时直接说资料里没有。回答简洁，最后一行写「出处：《" + kbName + "》」并附上资料标题或文件名。");
        llm.put("userPrompt", "问题：{{ params.question }}\n\n资料（JSON，hits 为检索到的段落）：\n{{ var.hits }}");

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", Map.of("question", Map.of("type", "string", "description", "用户的完整问题")));
        inputSchema.put("required", List.of("question"));
        inputSchema.put("additionalProperties", false);

        return GraphSpec.builder()
                .inputSchema(inputSchema)
                .entryNodeId("user_input")
                .exitNodeIds(List.of("answer", "no_evidence_answer"))
                .node(GraphSpec.Node.builder().id("user_input").type("USER_INPUT").name("用户问题")
                        .config(userInput).build())
                .node(GraphSpec.Node.builder().id("kr").type("KNOWLEDGE_RETRIEVAL").name("检索《" + kbName + "》")
                        .config(retrieval).build())
                .node(GraphSpec.Node.builder().id("answer_llm").type("LLM").name("按资料回答")
                        .config(llm).build())
                .node(GraphSpec.Node.builder().id("answer").type("ANSWER").name("回复")
                        .config(Map.of("template", "{{ nodeOutput.answer_llm }}")).build())
                .node(GraphSpec.Node.builder().id("no_evidence_answer").type("ANSWER").name("资料里没有")
                        .config(Map.of("template", "资料《" + kbName + "》里没有找到与「{{ params.question }}」相关的内容。"))
                        .build())
                .edge(GraphSpec.Edge.builder().id("e_input_kr").from("user_input").to("kr").condition("always").build())
                .edge(GraphSpec.Edge.builder().id("e_kr_llm").from("kr").to("answer_llm")
                        .condition("route:evidence").build())
                .edge(GraphSpec.Edge.builder().id("e_kr_none").from("kr").to("no_evidence_answer")
                        .condition("route:no_evidence").build())
                .edge(GraphSpec.Edge.builder().id("e_llm_answer").from("answer_llm").to("answer")
                        .condition("always").build())
                .build();
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    public record AttachKnowledgeRequest(String knowledgeBaseCode,
                                         String knowledgeBaseName,
                                         String modelInstanceId,
                                         String publishedBy) {
    }

    public record AttachView(String knowledgeBaseCode,
                             String workflowId,
                             String workflowKeySlug,
                             String workflowName,
                             boolean workflowCreated,
                             String agentId,
                             String agentName,
                             Integer agentConfigVersion,
                             String toolName,
                             boolean alreadyAttached) {
    }

    public record KnowledgeUsageView(String knowledgeBaseCode, List<WorkflowUsageView> workflows) {
    }

    public record WorkflowUsageView(String id,
                                    String name,
                                    String keySlug,
                                    String status,
                                    Long projectId,
                                    String projectCode,
                                    List<AgentUsageView> agents) {
    }

    public record AgentUsageView(String id, String name, String projectCode) {
    }
}

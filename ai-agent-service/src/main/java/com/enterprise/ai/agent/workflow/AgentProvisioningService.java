package com.enterprise.ai.agent.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AgentProvisioningService {

    public static final String PAGE_COPILOT_KIND = "PAGE_COPILOT";

    private static final Pattern UNSAFE_KEY_CHARS = Pattern.compile("[^A-Za-z0-9_-]+");

    private final AgentEntryService agentEntryService;
    private final WorkflowDefinitionService workflowDefinitionService;
    private final AgentWorkflowBindingService bindingService;
    private final ObjectMapper objectMapper;

    @Transactional
    public AgentProvisioningResult provisionPageCopilot(ScanProjectEntity project,
                                                        String source,
                                                        boolean ensureDefaultWorkflow) {
        if (project == null || project.getId() == null) {
            throw new IllegalArgumentException("project is required");
        }
        String projectCode = requireText(project.getProjectCode(), "project code is required");
        String agentKeySlug = pageCopilotKeySlug(projectCode);
        AgentLookup agentLookup = findOrCreatePageCopilotAgent(project, projectCode, agentKeySlug, source);
        WorkflowLookup workflowLookup = ensureDefaultWorkflow
                ? findOrCreateDefaultWorkflow(project, projectCode, agentKeySlug, source)
                : new WorkflowLookup(null, false);
        BindingLookup bindingLookup = workflowLookup.workflow() == null
                ? new BindingLookup(null, false)
                : findOrCreateDefaultBinding(agentLookup.agent(), workflowLookup.workflow(), projectCode, source);

        return new AgentProvisioningResult(
                agentLookup.agent(),
                workflowLookup.workflow(),
                bindingLookup.binding(),
                agentLookup.created(),
                workflowLookup.created(),
                bindingLookup.created());
    }

    public String pageCopilotKeySlug(String projectCode) {
        return limitKey(safeKey(requireText(projectCode, "project code is required")) + "-page-copilot");
    }

    public String defaultWorkflowKeySlug(String projectCode) {
        return limitKey(pageCopilotKeySlug(projectCode) + "-default");
    }

    private AgentLookup findOrCreatePageCopilotAgent(ScanProjectEntity project,
                                                     String projectCode,
                                                     String agentKeySlug,
                                                     String source) {
        return agentEntryService.findByKeySlug(agentKeySlug)
                .map(agent -> new AgentLookup(agent, false))
                .orElseGet(() -> {
                    AgentEntryEntity entity = new AgentEntryEntity();
                    entity.setProjectId(project.getId());
                    entity.setProjectCode(projectCode);
                    entity.setKeySlug(agentKeySlug);
                    entity.setName(displayName(project, "Page Copilot"));
                    entity.setDescription("Project page copilot Agent for embedded chat, page understanding, and Workflow routing.");
                    entity.setAgentKind(PAGE_COPILOT_KIND);
                    entity.setVisibility(StringUtils.hasText(project.getVisibility()) ? project.getVisibility().trim() : "PROJECT");
                    entity.setEnabled(true);
                    entity.setSystemPrompt("You are the project's page copilot. Understand the current business page and route executable work to bound Workflows.");
                    entity.setEntryConfigJson(writeJson(Map.of(
                            "source", normalizedSource(source),
                            "purpose", "page-copilot",
                            "routing", "agent-workflow-binding"
                    )));
                    return new AgentLookup(agentEntryService.create(entity), true);
                });
    }

    private WorkflowLookup findOrCreateDefaultWorkflow(ScanProjectEntity project,
                                                       String projectCode,
                                                       String agentKeySlug,
                                                       String source) {
        String keySlug = defaultWorkflowKeySlug(projectCode);
        return workflowDefinitionService.findByKeySlug(keySlug)
                .map(workflow -> new WorkflowLookup(workflow, false))
                .orElseGet(() -> {
                    WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
                    entity.setProjectId(project.getId());
                    entity.setProjectCode(projectCode);
                    entity.setKeySlug(keySlug);
                    entity.setName(displayName(project, "Page Copilot Default"));
                    entity.setDescription("Default Workflow for the project page copilot before page-specific actions are registered.");
                    entity.setWorkflowType("PAGE_COPILOT_DEFAULT");
                    entity.setRuntimeType("LANGGRAPH4J");
                    entity.setStatus("DRAFT");
                    entity.setManagedBy("AGENT_PROVISIONING");
                    entity.setGraphSpecJson(writeJson(buildDefaultGraphSpec(projectCode, keySlug)));
                    entity.setInputSchemaJson(writeJson(Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "message", Map.of("type", "string"),
                                    "pageKey", Map.of("type", "string"),
                                    "route", Map.of("type", "string")
                            )
                    )));
                    entity.setExtraJson(writeJson(Map.of(
                            "source", normalizedSource(source),
                            "agentKeySlug", agentKeySlug,
                            "purpose", "page-copilot-default"
                    )));
                    return new WorkflowLookup(workflowDefinitionService.create(entity), true);
                });
    }

    private BindingLookup findOrCreateDefaultBinding(AgentEntryEntity agent,
                                                     WorkflowDefinitionEntity workflow,
                                                     String projectCode,
                                                     String source) {
        return bindingService.list(agent.getId()).stream()
                .filter(binding -> "DEFAULT".equalsIgnoreCase(binding.getBindingType()))
                .filter(binding -> Objects.equals(workflow.getId(), binding.getWorkflowId()))
                .findFirst()
                .map(binding -> new BindingLookup(binding, false))
                .orElseGet(() -> {
                    AgentWorkflowBindingEntity entity = new AgentWorkflowBindingEntity();
                    entity.setAgentId(agent.getId());
                    entity.setWorkflowId(workflow.getId());
                    entity.setProjectCode(projectCode);
                    entity.setBindingType("DEFAULT");
                    entity.setPriority(0);
                    entity.setEnabled(true);
                    entity.setMetadataJson(writeJson(Map.of(
                            "source", "agent-provisioning",
                            "requestedBy", normalizedSource(source),
                            "workflowKeySlug", workflow.getKeySlug()
                    )));
                    return new BindingLookup(bindingService.create(agent.getId(), entity), true);
                });
    }

    private GraphSpec buildDefaultGraphSpec(String projectCode, String keySlug) {
        Map<String, Object> llmConfig = new LinkedHashMap<>();
        llmConfig.put("systemPrompt", """
                你是嵌入在业务系统页面里的业务系统页面助手。
                你只能围绕当前业务页面、用户正在查看的数据、已接入的页面能力和后续可接入能力回答。
                如果用户只是问候，请用中文简短问候，并说明可以协助查询、解释或操作当前页面。
                如果当前页面还没有绑定具体页面助手 Workflow 或页面动作，不要编造业务数据，不要输出代码、脚本或通用编程示例。
                """);
        llmConfig.put("userPrompt", """
                当前项目编码：{{ projectCode }}
                当前页面标识：{{ params.pageKey }}
                当前路由：{{ params.route }}
                用户消息：{{ input }}

                请作为当前业务页面助手回答。
                """);
        llmConfig.put("projectCode", projectCode);

        return GraphSpec.builder()
                .code(keySlug)
                .name(keySlug)
                .mode("WORKFLOW")
                .runtimeHint("LANGGRAPH4J")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "message", Map.of("type", "string"),
                                "pageKey", Map.of("type", "string"),
                                "route", Map.of("type", "string")
                        )
                ))
                .node(GraphSpec.Node.builder()
                        .id("page_copilot_answer")
                        .type("LLM")
                        .name("Page Copilot Answer")
                        .config(llmConfig)
                        .build())
                .edge(GraphSpec.Edge.builder().id("start_to_answer").from("START").to("page_copilot_answer").condition("always").build())
                .edge(GraphSpec.Edge.builder().id("answer_to_end").from("page_copilot_answer").to("END").condition("always").build())
                .entry("page_copilot_answer")
                .finishNode("END")
                .build();
    }

    private String safeKey(String value) {
        String normalized = UNSAFE_KEY_CHARS.matcher(value.trim().replace('.', '_')).replaceAll("-");
        normalized = normalized.replaceAll("[-_]{2,}", "-");
        normalized = normalized.replaceAll("^[^A-Za-z0-9]+", "");
        normalized = normalized.replaceAll("[^A-Za-z0-9]+$", "");
        if (normalized.length() < 2) {
            normalized = "agent-" + normalized;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String limitKey(String key) {
        return key.length() <= 128 ? key : key.substring(0, 128);
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String normalizedSource(String source) {
        return StringUtils.hasText(source) ? source.trim() : "agent-provisioning";
    }

    private String displayName(ScanProjectEntity project, String suffix) {
        String prefix = StringUtils.hasText(project.getName()) ? project.getName().trim() : project.getProjectCode().trim();
        return prefix + " " + suffix;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("failed to serialize agent provisioning metadata", ex);
        }
    }

    private record AgentLookup(AgentEntryEntity agent, boolean created) {
    }

    private record WorkflowLookup(WorkflowDefinitionEntity workflow, boolean created) {
    }

    private record BindingLookup(AgentWorkflowBindingEntity binding, boolean created) {
    }

    public record AgentProvisioningResult(
            AgentEntryEntity agent,
            WorkflowDefinitionEntity defaultWorkflow,
            AgentWorkflowBindingEntity defaultBinding,
            boolean createdAgent,
            boolean createdDefaultWorkflow,
            boolean createdDefaultBinding
    ) {
    }
}

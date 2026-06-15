package com.enterprise.ai.agent.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.scan.ScanProjectEntity;
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
public class PageAssistantWorkflowBindingService {

    private static final Pattern UNSAFE_KEY_CHARS = Pattern.compile("[^A-Za-z0-9_-]+");

    private final AgentEntryService agentEntryService;
    private final WorkflowDefinitionService workflowDefinitionService;
    private final AgentWorkflowBindingService bindingService;
    private final ObjectMapper objectMapper;

    @Transactional
    public PageAssistantWorkflowBindingResult ensurePageWorkflowBinding(ScanProjectEntity project,
                                                                        String pageKey,
                                                                        String routePattern,
                                                                        List<String> actionKeys) {
        if (project == null || project.getId() == null) {
            throw new IllegalArgumentException("project is required");
        }
        String projectCode = requireText(project.getProjectCode(), "project code is required");
        String normalizedPageKey = requireText(pageKey, "page key is required");
        List<String> normalizedActionKeys = normalizeActionKeys(actionKeys);

        AgentEntryEntity agent = findOrCreateGlobalAgent(project, projectCode);
        WorkflowDefinitionEntity workflow = findOrCreatePageWorkflow(project, projectCode, normalizedPageKey, routePattern, normalizedActionKeys);
        AgentWorkflowBindingEntity binding = findOrCreatePageBinding(agent, workflow, projectCode, normalizedPageKey, routePattern, normalizedActionKeys);

        return new PageAssistantWorkflowBindingResult(
                agent.getId(),
                agent.getKeySlug(),
                workflow.getId(),
                workflow.getKeySlug(),
                binding.getId());
    }

    private AgentEntryEntity findOrCreateGlobalAgent(ScanProjectEntity project, String projectCode) {
        String keySlug = globalAgentKeySlug(projectCode);
        return agentEntryService.findByKeySlug(keySlug)
                .orElseGet(() -> {
                    AgentEntryEntity entity = new AgentEntryEntity();
                    entity.setProjectId(project.getId());
                    entity.setProjectCode(projectCode);
                    entity.setKeySlug(keySlug);
                    entity.setName(displayName(project, "AI Assistant"));
                    entity.setDescription("Project global embedded AI entry. Page workflows are selected by current page context.");
                    entity.setAgentKind("GLOBAL_EMBED");
                    entity.setVisibility(StringUtils.hasText(project.getVisibility()) ? project.getVisibility().trim() : "PROJECT");
                    entity.setEnabled(true);
                    entity.setEntryConfigJson(writeJson(Map.of(
                            "source", "page-assistant",
                            "routing", "current-page-workflow"
                    )));
                    return agentEntryService.create(entity);
                });
    }

    private WorkflowDefinitionEntity findOrCreatePageWorkflow(ScanProjectEntity project,
                                                              String projectCode,
                                                              String pageKey,
                                                              String routePattern,
                                                              List<String> actionKeys) {
        String keySlug = pageWorkflowKeySlug(projectCode, pageKey);
        return workflowDefinitionService.findByKeySlug(keySlug)
                .orElseGet(() -> {
                    WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
                    entity.setProjectId(project.getId());
                    entity.setProjectCode(projectCode);
                    entity.setKeySlug(keySlug);
                    entity.setName(pageKey + " Page Assistant");
                    entity.setDescription("Page assistant workflow generated from AI Coding page registration.");
                    entity.setWorkflowType("PAGE_ASSISTANT");
                    entity.setRuntimeType("LANGGRAPH4J");
                    entity.setStatus("DRAFT");
                    entity.setManagedBy("PAGE_ASSISTANT");
                    entity.setGraphSpecJson(writeJson(buildGraphSpec(projectCode, pageKey, routePattern, actionKeys)));
                    entity.setInputSchemaJson(writeJson(Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "message", Map.of("type", "string"),
                                    "pageKey", Map.of("type", "string"),
                                    "route", Map.of("type", "string")
                            )
                    )));
                    entity.setExtraJson(writeJson(Map.of(
                            "source", "page-assistant",
                            "pageKey", pageKey,
                            "routePattern", StringUtils.hasText(routePattern) ? routePattern.trim() : "",
                            "actionKeys", actionKeys
                    )));
                    return workflowDefinitionService.create(entity);
                });
    }

    private AgentWorkflowBindingEntity findOrCreatePageBinding(AgentEntryEntity agent,
                                                               WorkflowDefinitionEntity workflow,
                                                               String projectCode,
                                                               String pageKey,
                                                               String routePattern,
                                                               List<String> actionKeys) {
        return bindingService.list(agent.getId()).stream()
                .filter(binding -> "PAGE".equalsIgnoreCase(binding.getBindingType()))
                .filter(binding -> Objects.equals(workflow.getId(), binding.getWorkflowId()))
                .filter(binding -> Objects.equals(pageKey, binding.getPageKey()))
                .findFirst()
                .orElseGet(() -> {
                    AgentWorkflowBindingEntity entity = new AgentWorkflowBindingEntity();
                    entity.setAgentId(agent.getId());
                    entity.setWorkflowId(workflow.getId());
                    entity.setProjectCode(projectCode);
                    entity.setBindingType("PAGE");
                    entity.setPageKey(pageKey);
                    entity.setRoutePattern(StringUtils.hasText(routePattern) ? routePattern.trim() : null);
                    entity.setPriority(100);
                    entity.setEnabled(true);
                    entity.setMetadataJson(writeJson(Map.of(
                            "source", "page-assistant",
                            "workflowKeySlug", workflow.getKeySlug(),
                            "actionKeys", actionKeys
                    )));
                    return bindingService.create(agent.getId(), entity);
                });
    }

    private GraphSpec buildGraphSpec(String projectCode, String pageKey, String routePattern, List<String> actionKeys) {
        String primaryActionKey = actionKeys.isEmpty() ? "getPageState" : actionKeys.get(0);
        Map<String, Object> pageActionConfig = new LinkedHashMap<>();
        pageActionConfig.put("projectCode", projectCode);
        pageActionConfig.put("pageKey", pageKey);
        pageActionConfig.put("routePattern", StringUtils.hasText(routePattern) ? routePattern.trim() : "");
        pageActionConfig.put("actionKey", primaryActionKey);
        pageActionConfig.put("availableActionKeys", actionKeys);
        pageActionConfig.put("args", Map.of());

        return GraphSpec.builder()
                .code(pageWorkflowKeySlug(projectCode, pageKey))
                .name(pageKey + " Page Assistant")
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
                        .id("classify_intent")
                        .type("LLM")
                        .name("Classify page intent")
                        .config(Map.of(
                                "prompt", "Decide which registered page action should handle the user request.",
                                "availableActionKeys", actionKeys
                        ))
                        .build())
                .node(GraphSpec.Node.builder()
                        .id("page_action")
                        .type("PAGE_ACTION")
                        .name("Execute page action")
                        .config(pageActionConfig)
                        .build())
                .edge(GraphSpec.Edge.builder().id("start_to_intent").from("START").to("classify_intent").condition("always").build())
                .edge(GraphSpec.Edge.builder().id("intent_to_page_action").from("classify_intent").to("page_action").condition("always").build())
                .edge(GraphSpec.Edge.builder().id("page_action_to_end").from("page_action").to("END").condition("always").build())
                .entry("START")
                .finishNode("END")
                .build();
    }

    private List<String> normalizeActionKeys(List<String> actionKeys) {
        if (actionKeys == null) {
            return List.of();
        }
        return actionKeys.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String globalAgentKeySlug(String projectCode) {
        return limitKey(safeKey(projectCode) + "-global-ai-assistant");
    }

    private String pageWorkflowKeySlug(String projectCode, String pageKey) {
        return limitKey(safeKey(projectCode) + "-" + safeKey(pageKey) + "-page-assistant");
    }

    private String safeKey(String value) {
        String normalized = UNSAFE_KEY_CHARS.matcher(value.trim().replace('.', '_')).replaceAll("-");
        normalized = normalized.replaceAll("[-_]{2,}", "-");
        normalized = normalized.replaceAll("^[^A-Za-z0-9]+", "");
        normalized = normalized.replaceAll("[^A-Za-z0-9]+$", "");
        if (normalized.length() < 2) {
            normalized = "pa-" + normalized;
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

    private String displayName(ScanProjectEntity project, String suffix) {
        String prefix = StringUtils.hasText(project.getName()) ? project.getName().trim() : project.getProjectCode().trim();
        return prefix + " " + suffix;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("failed to serialize page assistant workflow metadata", ex);
        }
    }
}

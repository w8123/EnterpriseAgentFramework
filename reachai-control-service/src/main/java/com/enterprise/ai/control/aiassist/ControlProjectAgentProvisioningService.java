package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.model.ControlModelCatalogClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Control-owned, idempotent project copilot provisioning.
 *
 * <p>The console and project-key AI Coding API deliberately share this service
 * so Agent/Supervisor creation has one implementation and one set of defaults.
 * Browser code and task handoff prompts never receive a project-level key.</p>
 */
@Service
public class ControlProjectAgentProvisioningService {

    static final String DEFAULT_PAGE_COPILOT_DESCRIPTION =
            "项目页面副驾驶 Agent，用于嵌入式对话、页面理解和 Workflow 路由。";
    static final String DEFAULT_PAGE_COPILOT_SYSTEM_PROMPT =
            "你是当前项目的页面副驾驶 Supervisor。理解用户请求并制定计划，"
                    + "从允许的 Workflow 中选择一个或多个作为 Tool 执行。"
                    + "只有当用户明确要求打开、跳转、查询或操作页面时，"
                    + "才使用包含页面操作的 Workflow。"
                    + "名称、说明和回复默认使用简体中文；Token、MCP、AI、Agent、"
                    + "Supervisor、Workflow、Tool、API、SDK 等熟知专业术语和技术标识可保留英文。";
    private static final Set<String> LEGACY_PAGE_COPILOT_DESCRIPTIONS = Set.of(
            "Project page copilot Agent for embedded chat and Workflow routing.",
            "Project page copilot Agent for embedded chat, page understanding, and Workflow routing.");
    private static final Set<String> LEGACY_PAGE_COPILOT_SYSTEM_PROMPTS = Set.of(
            "You are the project's page copilot Supervisor. Understand the request, plan, and select one or more permitted Workflows as tools. Use page-action Workflows only when the user explicitly asks to open, navigate, query, or operate a page.",
            "You are the project's page copilot. Understand the user's intent and select the permitted Workflows as tools.");

    private final CapabilityProjectOnboardingClient capabilityClient;
    private final RuntimeProxyClient runtimeClient;
    private final ControlModelCatalogClient modelCatalogClient;

    public ControlProjectAgentProvisioningService(
            CapabilityProjectOnboardingClient capabilityClient,
            RuntimeProxyClient runtimeClient,
            ControlModelCatalogClient modelCatalogClient) {
        this.capabilityClient = capabilityClient;
        this.runtimeClient = runtimeClient;
        this.modelCatalogClient = modelCatalogClient;
    }

    public Map<String, Object> provision(
            Long projectId,
            Map<String, ?> request) {
        Map<String, Object> project =
                capabilityClient.getOnboardingProjectById(projectId);
        String projectCode = stringValue(project.get("projectCode"));
        String keySlug = pageCopilotKeySlug(projectCode, projectId);
        String modelInstanceId = resolveSupervisorModelInstanceId(
                stringValue(request == null ? null : request.get("modelInstanceId")));
        String requestedBy = firstText(
                stringValue(request == null ? null : request.get("requestedBy")),
                "project-onboarding");

        RuntimeObject agent = findOrCreateAgent(
                projectId,
                projectCode,
                keySlug,
                stringValue(project.get("name")));
        RuntimeObject supervisorConfig = ensureActiveSupervisorConfig(
                agent.id(),
                modelInstanceId,
                requestedBy);

        Map<String, Object> provisionedAgent =
                new LinkedHashMap<>(agent.body());
        provisionedAgent.put(
                "activeConfigVersionId",
                supervisorConfig.body().get("id"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", "agent-provisioning.v2");
        body.put("agent", provisionedAgent);
        body.put("supervisorConfig", supervisorConfig.body());
        body.put(
                "workflowTools",
                listValue(supervisorConfig.body().get("tools")));
        body.put("createdAgent", agent.created());
        body.put(
                "createdSupervisorConfig",
                supervisorConfig.created());
        body.put("runtimeType", "AGENTSCOPE");
        return body;
    }

    public static String pageCopilotKeySlug(
            String projectCode,
            Long projectId) {
        String source = StringUtils.hasText(projectCode)
                ? projectCode.trim()
                : "project-" + projectId;
        return source.toLowerCase()
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "") + "-page-copilot";
    }

    private RuntimeObject findOrCreateAgent(
            Long projectId,
            String projectCode,
            String keySlug,
            String projectName) {
        List<Map<String, Object>> agents =
                responseList(runtimeClient.listAgents(projectId, projectCode));
        Map<String, Object> existing = agents.stream()
                .filter(item -> Objects.equals(
                        keySlug,
                        stringValue(item.get("keySlug"))))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return new RuntimeObject(
                    false,
                    localizeLegacyAgentDefaults(
                            existing,
                            projectName,
                            projectCode));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("projectCode", projectCode);
        body.put("keySlug", keySlug);
        body.put(
                "name",
                pageCopilotDisplayName(projectName, projectCode));
        body.put(
                "description",
                DEFAULT_PAGE_COPILOT_DESCRIPTION);
        body.put("visibility", "PROJECT");
        body.put("enabled", true);
        return new RuntimeObject(
                true,
                responseMap(runtimeClient.createAgent(body)));
    }

    private RuntimeObject ensureActiveSupervisorConfig(
            String agentId,
            String modelInstanceId,
            String publishedBy) {
        if (!StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException(
                    "Runtime did not return an Agent id");
        }
        List<Map<String, Object>> versions =
                responseList(runtimeClient.listAgentConfigVersions(agentId));
        Map<String, Object> active = versions.stream()
                .filter(item -> "ACTIVE".equalsIgnoreCase(
                        stringValue(item.get("status"))))
                .findFirst()
                .orElse(null);
        if (active != null) {
            boolean hasDraft = versions.stream()
                    .anyMatch(item -> "DRAFT".equalsIgnoreCase(
                            stringValue(item.get("status"))));
            String activeSystemPrompt =
                    stringValue(active.get("systemPrompt"));
            if (hasDraft
                    || !StringUtils.hasText(activeSystemPrompt)
                    || !LEGACY_PAGE_COPILOT_SYSTEM_PROMPTS.contains(
                            activeSystemPrompt)) {
                return new RuntimeObject(false, active);
            }
        }

        Map<String, Object> draftRequest = new LinkedHashMap<>();
        draftRequest.put("runtimeType", "AGENTSCOPE");
        draftRequest.put(
                "systemPrompt",
                DEFAULT_PAGE_COPILOT_SYSTEM_PROMPT);
        draftRequest.put("modelInstanceId", modelInstanceId);
        draftRequest.put("policyProfile", "DEV_ALLOW_ALL");
        draftRequest.put("toolCatalogMode", "ALLOW_LIST");
        draftRequest.put(
                "configJson",
                "{\"source\":\"project-onboarding\",\"purpose\":\"page-copilot\",\"routing\":\"supervisor-workflow-tools\"}");
        Map<String, Object> draft = responseMap(
                runtimeClient.saveAgentConfigDraft(agentId, draftRequest));
        Long configVersionId = nullableLongValue(draft.get("id"));
        if (configVersionId == null) {
            throw new IllegalArgumentException(
                    "Runtime did not return an Agent Supervisor config version id");
        }
        Map<String, Object> published = responseMap(
                runtimeClient.publishAgentConfigVersion(
                        agentId,
                        configVersionId,
                        Map.of("publishedBy", publishedBy)));
        if (!"ACTIVE".equalsIgnoreCase(
                stringValue(published.get("status")))) {
            throw new IllegalArgumentException(
                    "Agent Supervisor config was not activated");
        }
        return new RuntimeObject(true, published);
    }

    private Map<String, Object> localizeLegacyAgentDefaults(
            Map<String, Object> existing,
            String projectName,
            String projectCode) {
        Map<String, Object> update = new LinkedHashMap<>();
        String currentName = stringValue(existing.get("name"));
        String legacyNameByProject = firstText(
                projectName,
                projectCode,
                "Project") + " Page Copilot";
        String legacyNameByCode = firstText(
                projectCode,
                "Project") + " Page Copilot";
        if (Objects.equals(currentName, legacyNameByProject)
                || Objects.equals(currentName, legacyNameByCode)) {
            update.put(
                    "name",
                    pageCopilotDisplayName(projectName, projectCode));
        }
        String currentDescription =
                stringValue(existing.get("description"));
        if (StringUtils.hasText(currentDescription)
                && LEGACY_PAGE_COPILOT_DESCRIPTIONS.contains(
                        currentDescription)) {
            update.put(
                    "description",
                    DEFAULT_PAGE_COPILOT_DESCRIPTION);
        }
        String agentId = stringValue(existing.get("id"));
        if (update.isEmpty() || !StringUtils.hasText(agentId)) {
            return existing;
        }
        Map<String, Object> localized = new LinkedHashMap<>(existing);
        localized.putAll(update);
        localized.putAll(responseMap(
                runtimeClient.updateAgent(agentId.trim(), update)));
        return localized;
    }

    private static String pageCopilotDisplayName(
            String projectName,
            String projectCode) {
        return firstText(projectName, projectCode, "项目")
                + " 页面副驾驶 Agent";
    }

    private String resolveSupervisorModelInstanceId(
            String requestedModelInstanceId) {
        List<Map<String, Object>> activeModels =
                modelDataList(modelCatalogClient.list(null, "LLM", null))
                        .stream()
                        .filter(item -> "ACTIVE".equalsIgnoreCase(
                                stringValue(item.get("status"))))
                        .toList();
        if (StringUtils.hasText(requestedModelInstanceId)) {
            return activeModels.stream()
                    .filter(item -> requestedModelInstanceId.trim().equals(
                            stringValue(item.get("id"))))
                    .map(item -> requestedModelInstanceId.trim())
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Requested Agent Supervisor model is not an ACTIVE LLM instance: "
                                    + requestedModelInstanceId.trim()));
        }
        return activeModels.stream()
                .map(item -> stringValue(item.get("id")))
                .filter(StringUtils::hasText)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No ACTIVE LLM model instance is available for the Agent Supervisor"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> responseList(
            ResponseEntity<Object> response) {
        Object body = response == null ? null : response.getBody();
        if (body instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> modelDataList(
            ResponseEntity<Map<String, Object>> response) {
        Object body = response == null ? null : response.getBody();
        Object data = body instanceof Map<?, ?> map
                ? map.get("data")
                : null;
        if (data instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> responseMap(
            ResponseEntity<Object> response) {
        Object body = response == null ? null : response.getBody();
        return body instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
    }

    private static List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static Long nullableLongValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return Long.valueOf(text.trim());
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstText(String... values) {
        if (values != null) {
            for (String value : values) {
                if (StringUtils.hasText(value)) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private record RuntimeObject(
            boolean created,
            Map<String, Object> body) {
        String id() {
            return body == null ? null : stringValue(body.get("id"));
        }
    }
}

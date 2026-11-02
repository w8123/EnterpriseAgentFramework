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
                    + "当前页面的数据、列表、筛选、统计、可见行和详情都属于页面请求；"
                    + "即使用户只说查、查询、统计、多少、哪些或筛选，也应使用匹配的页面 Workflow，"
                    + "不能因为用户没有说打开或操作页面就拒绝。"
                    + "名称、说明和回复默认使用简体中文；Token、MCP、AI、Agent、"
                    + "Supervisor、Workflow、Tool、API、SDK 等熟知专业术语和技术标识可保留英文。";
    private final CapabilityProjectOnboardingClient capabilityClient;
    private final RuntimeProxyClient runtimeClient;
    private final ControlModelCatalogClient modelCatalogClient;
    private final ControlProjectAgentProvisioningDraftGateway drafts;

    public ControlProjectAgentProvisioningService(
            CapabilityProjectOnboardingClient capabilityClient,
            RuntimeProxyClient runtimeClient,
            ControlModelCatalogClient modelCatalogClient,
            ControlProjectAgentProvisioningDraftGateway drafts) {
        this.capabilityClient = capabilityClient;
        this.runtimeClient = runtimeClient;
        this.modelCatalogClient = modelCatalogClient;
        this.drafts = drafts;
    }

    public Map<String, Object> provision(
            Long projectId,
            Map<String, ?> request) {
        Map<String, Object> project =
                capabilityClient.getOnboardingProjectById(projectId);
        if (project == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "Project lookup is unavailable");
        String projectCode = stringValue(project.get("projectCode"));
        if (projectId == null || projectId < 1 || !Objects.equals(projectId, nullableLongValue(project.get("id")))
                || !StringUtils.hasText(projectCode)) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "Project lookup returned an incomplete or different project");
        var access = drafts.open(projectId, projectCode);
        String keySlug = pageCopilotKeySlug(projectCode, projectId);
        String modelInstanceId = resolveSupervisorModelInstanceId(
                stringValue(request == null ? null : request.get("modelInstanceId")));
        RuntimeObject agent = findOrCreateAgent(
                projectId,
                projectCode,
                keySlug,
                stringValue(project.get("name")));
        RuntimeObject supervisorConfig = ensureActiveSupervisorConfig(
                agent.id(),
                modelInstanceId,
                access);

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
            requireAgentProject(existing, projectId, projectCode);
            return new RuntimeObject(false, existing);
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
        Map<String, Object> created = responseMap(runtimeClient.createAgent(body));
        requireAgentProject(created, projectId, projectCode);
        return new RuntimeObject(true, created);
    }

    private RuntimeObject ensureActiveSupervisorConfig(
            String agentId,
            String modelInstanceId,
            ControlProjectAgentProvisioningDraftGateway.Drafts access) {
        if (!StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException(
                    "Runtime did not return an Agent id");
        }
        Map<String, Object> context = access.context(agentId);
        Map<String, Object> base = context.get("base") instanceof Map<?, ?> map ? stringMap(map) : null;
        if (base != null && "ACTIVE".equals(base.get("status"))) return new RuntimeObject(false, base);
        List<Map<String, Object>> versions = base == null ? List.of()
                : responseList(ResponseEntity.ok(access.versions(agentId).get("versions")));
        Map<String, Object> active = versions.stream()
                .filter(item -> "ACTIVE".equalsIgnoreCase(
                        stringValue(item.get("status"))))
                .findFirst()
                .orElse(null);
        if (active != null) {
            if (!agentId.equals(active.get("agentId"))) throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, "Runtime returned another Agent's active config");
            return new RuntimeObject(false, active);
        }
        if (base != null) throw new IllegalArgumentException(
                "Agent already has an unpublished draft; read and review it before saving or publishing. Provisioning never overwrites it.");

        Map<String, Object> draftRequest = new LinkedHashMap<>();
        draftRequest.put("runtimeType", "AGENTSCOPE");
        draftRequest.put(
                "systemPrompt",
                DEFAULT_PAGE_COPILOT_SYSTEM_PROMPT);
        draftRequest.put("modelInstanceId", modelInstanceId);
        draftRequest.put("policyProfile", "STANDARD");
        draftRequest.put("toolCatalogMode", "ALLOW_LIST");
        draftRequest.put(
                "configJson",
                "{\"source\":\"project-onboarding\",\"purpose\":\"page-copilot\",\"routing\":\"supervisor-workflow-tools\"}");
        Map<String, Object> saved = access.save(agentId, stringValue(context.get("revision")), draftRequest);
        Map<String, Object> draft = stringMap((Map<?, ?>) saved.get("base"));
        Long configVersionId = nullableLongValue(draft.get("id"));
        if (configVersionId == null) {
            throw new IllegalArgumentException(
                    "Runtime did not return an Agent Supervisor config version id");
        }
        Map<String, Object> publishedContext = access.publish(agentId, stringValue(saved.get("revision")), configVersionId);
        Map<String, Object> published = stringMap((Map<?, ?>) publishedContext.get("base"));
        if (!"ACTIVE".equalsIgnoreCase(
                stringValue(published.get("status")))) {
            throw new IllegalArgumentException(
                    "Agent Supervisor config was not activated");
        }
        return new RuntimeObject(true, published);
    }

    private static Map<String, Object> stringMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static void requireAgentProject(Map<String, Object> agent, Long projectId, String code) {
        if (!StringUtils.hasText(stringValue(agent.get("id"))) || !Objects.equals(projectId, nullableLongValue(agent.get("projectId")))
                || !Objects.equals(code, agent.get("projectCode"))) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "Runtime returned an Agent from an incomplete or different project");
    }

    private static String pageCopilotDisplayName(
            String projectName,
            String projectCode) {
        return firstText(projectName, projectCode, "项目")
                + " 页面副驾驶 Agent";
    }

    String resolveSupervisorModelInstanceId(
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
        if (response == null || !response.getStatusCode().is2xxSuccessful() || !(body instanceof Collection<?>))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "Runtime Agent list is unavailable");
        if (body instanceof Collection<?> collection) {
            if (collection.stream().anyMatch(item -> !(item instanceof Map<?, ?>)))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "Runtime returned a malformed Agent list");
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
        if (response == null || !response.getStatusCode().is2xxSuccessful() || !(body instanceof Map<?, ?>))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "Model catalog is unavailable");
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
        if (response == null || !response.getStatusCode().is2xxSuccessful() || !(body instanceof Map<?, ?>))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "Runtime Agent result is unconfirmed; read current state before retrying");
        return body instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
    }

    private static List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static Long nullableLongValue(Object value) {
        if (value instanceof Number number) {
            try { return new java.math.BigDecimal(number.toString()).longValueExact(); }
            catch (ArithmeticException | NumberFormatException malformed) { return null; }
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

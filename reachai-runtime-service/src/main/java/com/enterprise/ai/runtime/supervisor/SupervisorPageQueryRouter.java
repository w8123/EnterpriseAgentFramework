package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeResolvedWorkflowTarget;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowSchemaResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter.SupervisorRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Resolves current-page read routes from published Workflows and the Control action catalog. */
final class SupervisorPageQueryRouter {
    private static final Logger log = LoggerFactory.getLogger(SupervisorPageQueryRouter.class);
    private static final Pattern EXPLICIT_API_INTENT_PATTERN = Pattern.compile(
            "(?i)(?:\\bapi\\b|接口)");

    private final RuntimeControlCatalogClient controlCatalogClient;
    private final ObjectMapper objectMapper;

    SupervisorPageQueryRouter(RuntimeControlCatalogClient controlCatalogClient, ObjectMapper objectMapper) {
        this.controlCatalogClient = controlCatalogClient;
        this.objectMapper = objectMapper;
    }

    Route resolve(SupervisorRequest request, List<RuntimeResolvedWorkflowTarget> targets, String message) {
        String currentPageKey = textObj(request.input().get("pageKey"));
        if (!StringUtils.hasText(message)
                || !StringUtils.hasText(currentPageKey)
                || !SupervisorPageQueryPolicy.looksLikePageReadRequest(message)
                || EXPLICIT_API_INTENT_PATTERN.matcher(message).find()
                || SupervisorPageQueryPolicy.containsAny(message, "打开", "跳转", "进入", "导航")
                || SupervisorPageQueryPolicy.hasUnnegatedWriteIntent(message)) {
            return null;
        }

        RuntimeResolvedWorkflowTarget selected = null;
        PageActionReference selectedReference = null;
        int selectedScore = Integer.MIN_VALUE;
        for (RuntimeResolvedWorkflowTarget target : targets) {
            if (target == null || target.tool() == null
                    || !"PAGE_ACTION".equalsIgnoreCase(target.tool().getRiskLevel())) {
                continue;
            }
            PageActionReference reference = pageActionReference(target);
            if (reference == null || !currentPageKey.equals(reference.pageKey())) {
                continue;
            }
            String schemaJson = RuntimeWorkflowSchemaResolver.publishedInputSchemaJson(objectMapper, target.version(), target.tool().getInputSchemaOverrideJson());
            String haystack = (target.tool().getToolName() + " "
                    + firstText(target.tool().getDescriptionOverride(), target.workflow().getDescription(), "")
                    + " " + reference.actionKey() + " " + schemaJson).toLowerCase(Locale.ROOT);
            int score = 0;
            if (SupervisorPageQueryPolicy.containsAnyIgnoreCase(haystack, "query", "search", "list", "count", "filter", "read",
                    "查询", "搜索", "列表", "统计", "读取", "筛选")) {
                score += 10;
            }
            if (SupervisorPageQueryPolicy.containsAnyIgnoreCase(haystack, "query", "查询", "统计", "筛选", "search", "filter", "count")) {
                score += 8;
            }
            if (SupervisorPageQueryPolicy.containsAnyIgnoreCase(haystack, "open", "navigate", "detail", "打开", "跳转", "详情")) {
                score -= 8;
            }
            if (score > selectedScore) {
                selected = target;
                selectedReference = reference;
                selectedScore = score;
            }
        }
        if (selected == null || selectedReference == null || selectedScore <= 0) {
            return null;
        }

        RuntimeControlCatalogClient.PageActionCatalogEntry catalogAction = safeCatalogPageAction(
                request, selectedReference);
        if (catalogAction == null) {
            return null;
        }
        String schemaJson = RuntimeWorkflowSchemaResolver.publishedInputSchemaJson(objectMapper, selected.version(), selected.tool().getInputSchemaOverrideJson());
        Map<String, Object> inputSchema = schema(schemaJson);
        Map<String, Object> catalogSchema = schemaFromObject(catalogAction.inputSchema());
        if (hasProperties(catalogSchema)) {
            inputSchema = catalogSchema;
        }
        Map<String, Object> args = SupervisorPageQueryPolicy.ruleFirstArgs(inputSchema, message);
        if (SupervisorPageQueryPolicy.hasMissingRequiredInput(inputSchema, args)
                || SupervisorPageQueryPolicy.hasUnresolvedSpecificFilter(inputSchema, args, message)
                || !SupervisorPageQueryPolicy.hasSafePageActionMapping(selectedReference.inputMapping(), inputSchema, args)) {
            return null;
        }
        return new Route(selected, args);
    }

    private RuntimeControlCatalogClient.PageActionCatalogEntry safeCatalogPageAction(
            SupervisorRequest request,
            PageActionReference reference) {
        if (controlCatalogClient == null) {
            return null;
        }
        String projectCode = firstText(
                textObj(request.input().get("projectCode")),
                request.agent() == null ? null : request.agent().projectCode());
        if (!StringUtils.hasText(projectCode)) {
            return null;
        }
        if (StringUtils.hasText(reference.projectCode())
                && !projectCode.equals(reference.projectCode())) {
            return null;
        }
        try {
            RuntimeControlCatalogClient.PageActionCatalogEntry action = controlCatalogClient.getPageAction(
                    projectCode, reference.pageKey(), reference.actionKey());
            String actionDescription = action == null ? null : firstText(
                    action.actionKey(), "") + " " + firstText(action.title(), "") + " "
                    + firstText(action.description(), "");
            if (action == null
                    || !projectCode.equals(action.projectCode())
                    || !reference.pageKey().equals(action.pageKey())
                    || !reference.actionKey().equals(action.actionKey())
                    || !"ACTIVE".equalsIgnoreCase(action.status())
                    || action.confirmRequired()
                    || SupervisorPageQueryPolicy.hasWriteIntent(actionDescription)
                    || SupervisorPageQueryPolicy.containsAnyIgnoreCase(action.actionKey(),
                    "delete", "remove", "update", "modify", "create", "submit", "cancel", "save")
                    || !("READ".equalsIgnoreCase(action.riskLevel())
                    || "READ_ONLY".equalsIgnoreCase(action.riskLevel()))) {
                return null;
            }
            return action;
        } catch (RuntimeException ex) {
            log.debug("Page action catalog unavailable for rule-first routing: {}/{}",
                    reference.pageKey(), reference.actionKey(), ex);
            return null;
        }
    }

    private PageActionReference pageActionReference(RuntimeResolvedWorkflowTarget target) {
        if (target == null || target.version() == null) {
            return null;
        }
        String graphJson = target.version().getGraphSpecSnapshotJson();
        if (!StringUtils.hasText(graphJson)) {
            return null;
        }
        try {
            Map<String, Object> graph = objectMapper.readValue(graphJson, new TypeReference<>() { });
            Object rawNodes = graph.get("nodes");
            if (!(rawNodes instanceof List<?> nodes)) {
                return null;
            }
            int pageActionCount = 0;
            PageActionReference reference = null;
            for (Object rawNode : nodes) {
                if (!(rawNode instanceof Map<?, ?> rawMap)) {
                    continue;
                }
                Map<String, Object> node = new LinkedHashMap<>();
                rawMap.forEach((key, value) -> node.put(String.valueOf(key), value));
                String nodeType = textObj(node.get("type"));
                if (!"PAGE_ACTION".equalsIgnoreCase(nodeType)) {
                    if (!"ANSWER".equalsIgnoreCase(nodeType)) {
                        return null;
                    }
                    continue;
                }
                pageActionCount++;
                Map<String, Object> config = schemaFromObject(node.get("config"));
                if (Boolean.TRUE.equals(config.get("confirm"))
                        || Boolean.TRUE.equals(config.get("confirmRequired"))) {
                    return null;
                }
                String projectCode = textObj(config.get("projectCode"));
                String pageKey = textObj(config.get("pageKey"));
                String actionKey = textObj(config.get("actionKey"));
                Map<String, Object> inputMapping = schemaFromObject(config.get("inputMapping"));
                if (inputMapping.isEmpty() && !schemaFromObject(config.get("args")).isEmpty()) {
                    return null;
                }
                if (StringUtils.hasText(pageKey) && StringUtils.hasText(actionKey)) {
                    reference = new PageActionReference(
                            projectCode,
                            pageKey,
                            actionKey,
                            Map.copyOf(inputMapping));
                }
            }
            return pageActionCount == 1 ? reference : null;
        } catch (Exception ex) {
            log.debug("Published Workflow GraphSpec cannot be inspected for page action schema", ex);
        }
        return null;
    }

    private Map<String, Object> schemaFromObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private boolean hasProperties(Map<String, Object> inputSchema) {
        Object properties = inputSchema == null ? null : inputSchema.get("properties");
        return properties instanceof Map<?, ?> map && !map.isEmpty();
    }

    private Map<String, Object> schema(String json) {
        if (StringUtils.hasText(json)) {
            try {
                return objectMapper.readValue(json, new TypeReference<>() {});
            } catch (Exception ignored) {
                // Published validation owns schema correctness; keep the runtime tool callable with a safe generic schema.
            }
        }
        return Map.of("type", "object", "additionalProperties", true);
    }

    private String textObj(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private String firstText(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
    }

    record Route(RuntimeResolvedWorkflowTarget target, Map<String, Object> args) { }

    private record PageActionReference(String projectCode,
                                       String pageKey,
                                       String actionKey,
                                       Map<String, Object> inputMapping) {
    }
}

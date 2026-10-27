package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.execution.context.WorkflowVariableNamespaces;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.doubleValue;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.firstPresent;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.firstText;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.hasRouteEdge;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.hasUnconditionalEdge;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.intValue;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.mapValue;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.routeCondition;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.routesEquivalent;
import static com.enterprise.ai.runtime.workflow.RuntimeWorkflowValidationSupport.text;

/** Validates node parameters and execution policies without resolving catalog resources. */
final class RuntimeWorkflowNodeConfigValidator {

    void validateOutputAlias(GraphSpec.Node node,
                             Map<String, String> aliases,
                             RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String alias = text(config.get("outputAlias"));
        if (!StringUtils.hasText(alias)) {
            return;
        }
        if (!WorkflowVariableNamespaces.isValidAlias(alias)) {
            report.error("GRAPH_OUTPUT_ALIAS_INVALID", node.getId(),
                    "outputAlias must be a simple identifier");
            return;
        }
        if (WorkflowVariableNamespaces.isReservedAlias(alias)) {
            // USER_INPUT may intentionally use params; allow only that reserved alias.
            String type = AgentGraphNodeType.normalize(node.getType());
            if (!("USER_INPUT".equals(type) && "params".equals(alias))) {
                report.error("GRAPH_OUTPUT_ALIAS_RESERVED", node.getId(),
                        "outputAlias must not use reserved name: " + alias);
                return;
            }
        }
        String previous = aliases.putIfAbsent(alias, node.getId());
        if (previous != null) {
            report.error("GRAPH_OUTPUT_ALIAS_DUPLICATE", node.getId(),
                    "Duplicate outputAlias '" + alias + "' also used by node " + previous);
        }
    }

    void validateErrorPolicy(GraphSpec.Node node,
                             Set<String> nodeIds,
                             RuntimeWorkflowReleaseValidationResult.Builder report) {
        GraphSpec.ErrorPolicy policy = node.getErrorPolicy();
        if (policy == null || !StringUtils.hasText(policy.getStrategy())) {
            return;
        }
        String strategy = policy.getStrategy().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("TERMINATE", "CONTINUE", "FALLBACK").contains(strategy)) {
            report.error("GRAPH_ERROR_POLICY_INVALID", node.getId(),
                    "ErrorPolicy.strategy must be TERMINATE, CONTINUE or FALLBACK");
            return;
        }
        if ("FALLBACK".equals(strategy)) {
            String fallback = text(policy.getFallbackNodeId());
            if (!StringUtils.hasText(fallback) || !nodeIds.contains(fallback)) {
                report.error("GRAPH_FALLBACK_NODE_MISSING", node.getId(),
                        "ErrorPolicy FALLBACK requires an existing fallbackNodeId");
            } else if (fallback.equals(node.getId())) {
                report.error("GRAPH_FALLBACK_SELF", node.getId(),
                        "ErrorPolicy FALLBACK must not point to the same node");
            }
        }
    }

    void validateRetryPolicy(GraphSpec.Node node,
                             RuntimeWorkflowReleaseValidationResult.Builder report) {
        GraphSpec.RetryPolicy retry = node.getRetry();
        if (retry == null || !Boolean.TRUE.equals(retry.getEnabled())) {
            return;
        }
        String nodeType = AgentGraphNodeType.normalize(node.getType());
        Optional<AgentGraphNodeType> type = AgentGraphNodeType.find(nodeType);
        if (type.isEmpty() || !type.get().retryable()) {
            report.error("GRAPH_RETRY_NOT_ALLOWED", node.getId(),
                    "RetryPolicy.enabled is not allowed for non-retryable node type: " + nodeType);
        }
        Integer maxAttempts = retry.getMaxAttempts();
        if (maxAttempts == null || maxAttempts < 1 || maxAttempts > 5) {
            report.error("GRAPH_RETRY_ATTEMPTS_INVALID", node.getId(),
                    "RetryPolicy.maxAttempts must be between 1 and 5");
        }
        Long backoff = retry.getBackoffMs();
        if (backoff != null && (backoff < 0 || backoff > 10_000L)) {
            report.error("GRAPH_RETRY_BACKOFF_INVALID", node.getId(),
                    "RetryPolicy.backoffMs must be between 0 and 10000");
        }
        if ("HTTP_REQUEST".equals(nodeType)) {
            Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
            Map<String, Object> nested = mapValue(config.get("httpConfig"));
            Map<String, Object> effective = nested.isEmpty() ? config : nested;
            String method = firstText(text(effective.get("method")), text(config.get("method")), "GET")
                    .toUpperCase(Locale.ROOT);
            boolean idempotent = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
            boolean allowNonIdempotent = Boolean.TRUE.equals(effective.get("retryAllowNonIdempotent"))
                    || Boolean.TRUE.equals(config.get("retryAllowNonIdempotent"));
            if (!idempotent && !allowNonIdempotent) {
                report.error("GRAPH_HTTP_RETRY_NON_IDEMPOTENT", node.getId(),
                        "HTTP_REQUEST retry for non-idempotent method requires retryAllowNonIdempotent=true");
            }
        }
    }

    void validateVariableAssign(GraphSpec.Node node,
                                RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> assignments = mapValue(config.get("assignments"));
        if (assignments.isEmpty()) {
            report.error("GRAPH_ASSIGNMENTS_REQUIRED", node.getId(),
                    "VARIABLE_ASSIGN requires non-empty assignments");
            return;
        }
        for (String target : assignments.keySet()) {
            try {
                WorkflowVariableNamespaces.normalizeBusinessWriteTarget(target);
            } catch (IllegalArgumentException ex) {
                report.error("GRAPH_ASSIGNMENT_TARGET_INVALID", node.getId(),
                        "Invalid assignment target '" + target + "': " + ex.getMessage());
            }
        }
    }

    void validateTemplateNode(GraphSpec.Node node,
                              RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String template = firstText(text(config.get("template")), text(config.get("content")));
        if (!StringUtils.hasText(template)) {
            report.error("GRAPH_TEMPLATE_REQUIRED", node.getId(), "TEMPLATE requires template");
        }
    }

    void validateVariableAggregator(GraphSpec.Node node,
                                    RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("aggregateConfig"));
        Map<String, Object> effective = nested.isEmpty() ? config : nested;
        String mode = firstText(text(effective.get("aggregateMode")), text(effective.get("mode")), "object")
                .toLowerCase(Locale.ROOT);
        if (!Set.of("object", "array", "text").contains(mode)) {
            report.error("GRAPH_AGGREGATE_MODE_INVALID", node.getId(),
                    "VARIABLE_AGGREGATOR mode must be object, array or text");
        }
        Object items = firstPresent(effective.get("items"), config.get("items"));
        if (!(items instanceof List<?> list) || list.isEmpty()) {
            report.error("GRAPH_AGGREGATE_ITEMS_REQUIRED", node.getId(),
                    "VARIABLE_AGGREGATOR requires items");
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        for (Object rawItem : list) {
            Map<String, Object> item = mapValue(rawItem);
            String source = firstText(text(item.get("source")), text(item.get("expression")));
            if (!StringUtils.hasText(source)) {
                report.error("GRAPH_AGGREGATE_ITEM_SOURCE_REQUIRED", node.getId(),
                        "VARIABLE_AGGREGATOR item.source is required");
            }
            if ("object".equals(mode)) {
                String name = text(item.get("name"));
                if (!StringUtils.hasText(name)) {
                    report.error("GRAPH_AGGREGATE_ITEM_NAME_REQUIRED", node.getId(),
                            "VARIABLE_AGGREGATOR object mode requires unique item.name");
                } else if (!names.add(name.trim().toLowerCase(Locale.ROOT))) {
                    report.error("GRAPH_AGGREGATE_ITEM_NAME_DUPLICATE", node.getId(),
                            "VARIABLE_AGGREGATOR duplicate item.name: " + name);
                }
            }
        }
    }

    void validateKnowledgeRetrieval(GraphSpec.Node node,
                                    Map<String, GraphSpec.Node> byId,
                                    List<GraphSpec.Edge> edges,
                                    RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("knowledgeConfig"));
        Map<String, Object> effective = nested.isEmpty() ? config : nested;
        Object codes = firstPresent(effective.get("knowledgeBaseCodes"), config.get("knowledgeBaseCodes"));
        boolean hasCodes = codes instanceof List<?> list && list.stream().anyMatch(item -> StringUtils.hasText(text(item)));
        if (!hasCodes) {
            report.error("GRAPH_KNOWLEDGE_BASE_REQUIRED", node.getId(),
                    "KNOWLEDGE_RETRIEVAL requires knowledgeBaseCodes");
        }
        String query = firstText(text(effective.get("query")), text(config.get("query")));
        if (!StringUtils.hasText(query)) {
            report.error("GRAPH_KNOWLEDGE_QUERY_REQUIRED", node.getId(),
                    "KNOWLEDGE_RETRIEVAL requires query");
        }
        int topK = intValue(firstPresent(effective.get("topK"), config.get("topK")), 5);
        if (topK < 1 || topK > 20) {
            report.error("GRAPH_KNOWLEDGE_TOPK_INVALID", node.getId(),
                    "KNOWLEDGE_RETRIEVAL topK must be between 1 and 20");
        }
        Object threshold = firstPresent(effective.get("similarityThreshold"), config.get("similarityThreshold"));
        if (threshold != null) {
            double value = doubleValue(threshold, 0.5D);
            if (value < 0D || value > 1D) {
                report.error("GRAPH_KNOWLEDGE_THRESHOLD_INVALID", node.getId(),
                        "KNOWLEDGE_RETRIEVAL similarityThreshold must be between 0 and 1");
            }
        }
        String searchMode = firstText(text(effective.get("searchMode")), text(config.get("searchMode")), "hybrid")
                .toLowerCase(Locale.ROOT);
        if (!Set.of("vector", "keyword", "hybrid").contains(searchMode)) {
            report.error("GRAPH_KNOWLEDGE_SEARCH_MODE_INVALID", node.getId(),
                    "KNOWLEDGE_RETRIEVAL searchMode must be vector, keyword or hybrid");
        }
        String rawEvidencePolicy = firstText(
                text(effective.get("evidencePolicy")),
                text(config.get("evidencePolicy")));
        String evidencePolicy = StringUtils.hasText(rawEvidencePolicy)
                ? rawEvidencePolicy.toUpperCase(Locale.ROOT)
                : "OPTIONAL";
        if (!Set.of("REQUIRED", "OPTIONAL").contains(evidencePolicy)) {
            report.error("GRAPH_KNOWLEDGE_EVIDENCE_POLICY_INVALID", node.getId(),
                    "KNOWLEDGE_RETRIEVAL evidencePolicy must be REQUIRED or OPTIONAL");
            return;
        }
        if (!StringUtils.hasText(rawEvidencePolicy)) {
            report.warn("GRAPH_KNOWLEDGE_EVIDENCE_POLICY_LEGACY", node.getId(),
                    "Legacy KNOWLEDGE_RETRIEVAL has no evidencePolicy and keeps OPTIONAL fallback semantics");
        }
        if ("REQUIRED".equals(evidencePolicy)) {
            if (!hasRouteEdge(edges, node.getId(), "evidence")) {
                report.error("GRAPH_KNOWLEDGE_EVIDENCE_ROUTE_MISSING", node.getId(),
                        "REQUIRED KNOWLEDGE_RETRIEVAL must have route:evidence outgoing edge");
            }
            if (!hasRouteEdge(edges, node.getId(), "no_evidence")) {
                report.error("GRAPH_KNOWLEDGE_NO_EVIDENCE_ROUTE_MISSING", node.getId(),
                        "REQUIRED KNOWLEDGE_RETRIEVAL must have route:no_evidence outgoing edge");
            }
            if (hasUnconditionalEdge(edges, node.getId())) {
                report.error("GRAPH_KNOWLEDGE_EVIDENCE_FALLBACK_UNSAFE", node.getId(),
                        "REQUIRED KNOWLEDGE_RETRIEVAL must not have always/success fallback edges");
            }
            edges.stream()
                    .filter(edge -> edge != null && node.getId().equals(text(edge.getFrom())))
                    .filter(edge -> routesEquivalent("no_evidence", routeCondition(edge.getCondition())))
                    .forEach(edge -> {
                        GraphSpec.Node target = byId.get(text(edge.getTo()));
                        if (target == null || !"ANSWER".equalsIgnoreCase(text(target.getType()))) {
                            report.error("GRAPH_KNOWLEDGE_NO_EVIDENCE_TARGET_UNSAFE", node.getId(),
                                    "route:no_evidence must target a deterministic ANSWER node");
                        }
                    });
        }
    }

    void validateHttpRequest(GraphSpec.Node node,
                             RuntimeWorkflowReleaseValidationResult.Builder report) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("httpConfig"));
        if (config.containsKey("marketRef") || nested.containsKey("marketRef")) {
            report.error("GRAPH_HTTP_API_MARKET_BINDING_REQUIRED", node.getId(),
                    "旧市场 HTTP_REQUEST 引用不能作为受控 API 绑定；请从市场显式接入项目，在 API 详情验证后通过 API 节点重新选择并映射");
        }
        Map<String, Object> effective = nested.isEmpty() ? config : nested;
        String url = firstText(text(effective.get("url")), text(config.get("url")));
        if (!StringUtils.hasText(url)) {
            report.error("GRAPH_HTTP_URL_REQUIRED", node.getId(), "HTTP_REQUEST requires url");
        }
        String method = firstText(text(effective.get("method")), text(config.get("method")), "GET")
                .toUpperCase(Locale.ROOT);
        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(method)) {
            report.error("GRAPH_HTTP_METHOD_INVALID", node.getId(),
                    "HTTP_REQUEST method is unsupported: " + method);
        }
        String bodyType = firstText(text(effective.get("bodyType")), text(config.get("bodyType")), "none")
                .toLowerCase(Locale.ROOT);
        if (!Set.of("none", "json", "text").contains(bodyType)) {
            report.error("GRAPH_HTTP_BODY_TYPE_INVALID", node.getId(),
                    "HTTP_REQUEST bodyType must be none, json or text");
        }
        int timeoutMs = intValue(firstPresent(effective.get("timeoutMs"), config.get("timeoutMs")),
                WorkflowHttpClient.DEFAULT_TIMEOUT_MS);
        if (timeoutMs < WorkflowHttpClient.MIN_TIMEOUT_MS || timeoutMs > WorkflowHttpClient.MAX_TIMEOUT_MS) {
            report.error("GRAPH_HTTP_TIMEOUT_INVALID", node.getId(),
                    "HTTP_REQUEST timeoutMs must be between "
                            + WorkflowHttpClient.MIN_TIMEOUT_MS + " and " + WorkflowHttpClient.MAX_TIMEOUT_MS);
        }
    }

    boolean classifierRequiresModelInstance(Map<String, Object> config) {
        String strategy = firstText(text(config.get("strategy")), "KEYWORD");
        return "LLM".equalsIgnoreCase(strategy) || "HYBRID".equalsIgnoreCase(strategy);
    }

    void validateConditionRoutes(GraphSpec.Node node,
                                 Map<String, Object> config,
                                 List<GraphSpec.Edge> edges,
                                 RuntimeWorkflowReleaseValidationResult.Builder report) {
        Object rawGroups = config.get("conditionGroups") != null
                ? config.get("conditionGroups")
                : config.get("groups");
        if (rawGroups instanceof List<?> groups) {
            for (Object rawGroup : groups) {
                String groupId = text(mapValue(rawGroup).get("id"));
                if (StringUtils.hasText(groupId) && !hasRouteEdge(edges, node.getId(), groupId)) {
                    report.error("GRAPH_CONDITION_GROUP_ROUTE_MISSING", node.getId(),
                            "IF_ELSE condition group has no matching outgoing edge: " + groupId);
                }
            }
        }
        String defaultRoute = firstText(text(config.get("defaultRoute")), "else");
        if (!hasRouteEdge(edges, node.getId(), defaultRoute)) {
            report.error("GRAPH_CONDITION_DEFAULT_ROUTE_MISSING", node.getId(),
                    "IF_ELSE default route has no matching outgoing edge: " + defaultRoute);
        }
    }

    void validateToolReference(GraphSpec.Node node,
                               String nodeType,
                               RuntimeWorkflowReleaseValidationResult.Builder report) {
        if (!StringUtils.hasText(resolveToolReference(node))) {
            report.error("GRAPH_TOOL_REF_REQUIRED", node.getId(),
                    nodeType + " requires ref.qualifiedName/name or a configured qualifiedName/ref/toolName");
        }
    }

    private String resolveToolReference(GraphSpec.Node node) {
        if (node.getRef() != null) {
            String reference = firstText(
                    text(node.getRef().getQualifiedName()),
                    text(node.getRef().getName()));
            if (StringUtils.hasText(reference)) {
                return reference;
            }
        }
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("toolConfig"));
        return firstText(
                text(config.get("qualifiedName")),
                configuredReference(config.get("ref")),
                text(config.get("toolName")),
                nested.isEmpty() ? null : text(nested.get("qualifiedName")),
                nested.isEmpty() ? null : configuredReference(nested.get("ref")),
                nested.isEmpty() ? null : text(nested.get("toolName")));
    }

    private String configuredReference(Object rawReference) {
        Map<String, Object> reference = mapValue(rawReference);
        if (!reference.isEmpty()) {
            return firstText(
                    text(reference.get("qualifiedName")),
                    text(reference.get("name")),
                    reference.get("ref") instanceof Map<?, ?>
                            ? configuredReference(reference.get("ref"))
                            : text(reference.get("ref")),
                    text(reference.get("toolName")));
        }
        return text(rawReference);
    }

    void validateParameterExtract(GraphSpec.Node node,
                                  Map<String, Object> config,
                                  RuntimeWorkflowReleaseValidationResult.Builder report) {
        Object rawFields = config.get("fields");
        if (!(rawFields instanceof List<?> fields) || fields.isEmpty()) {
            report.error("GRAPH_PARAMETER_FIELDS_REQUIRED", node.getId(),
                    "PARAMETER_EXTRACT requires at least one target field");
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        for (Object item : fields) {
            String name = text(mapValue(item).get("name"));
            if (!StringUtils.hasText(name)) {
                report.error("GRAPH_PARAMETER_FIELD_NAME_EMPTY", node.getId(),
                        "PARAMETER_EXTRACT field name is required");
            } else if (!names.add(name.toLowerCase(Locale.ROOT))) {
                report.error("GRAPH_PARAMETER_FIELD_NAME_DUPLICATE", node.getId(),
                        "Duplicate PARAMETER_EXTRACT field name: " + name);
            }
        }
        String mode = firstText(text(config.get("extractMode")), text(config.get("mode")), "expression");
        String inputExpression = firstText(text(config.get("inputExpression")), "input");
        String userPrompt = text(config.get("userPrompt"));
        if ("LLM".equalsIgnoreCase(mode)
                && inputExpression.startsWith("nodeOutput.")
                && StringUtils.hasText(userPrompt)
                && !compactWhitespace(userPrompt).contains(compactWhitespace(inputExpression))) {
            report.error("GRAPH_PARAMETER_USER_PROMPT_INPUT_MISSING", node.getId(),
                    "PARAMETER_EXTRACT userPrompt overrides inputExpression; include {{ "
                            + inputExpression + " }} in userPrompt or leave userPrompt empty");
        }
    }

    private String compactWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    void validateIntentClassifier(GraphSpec.Node node,
                                  Map<String, Object> config,
                                  List<GraphSpec.Edge> edges,
                                  RuntimeWorkflowReleaseValidationResult.Builder report) {
        Object rawClasses = config.get("classes");
        List<?> classes = rawClasses instanceof List<?> items ? items : List.of();
        if (classes.isEmpty()) {
            report.error("GRAPH_CLASSIFIER_CLASSES_REQUIRED", node.getId(),
                    "INTENT_CLASSIFIER requires at least one class");
        }

        Set<String> classIds = new LinkedHashSet<>();
        Set<String> normalizedClassIds = new LinkedHashSet<>();
        for (Object item : classes) {
            Map<String, Object> classConfig = mapValue(item);
            String classId = text(classConfig.get("id"));
            if (!StringUtils.hasText(classId)) {
                report.error("GRAPH_CLASSIFIER_CLASS_ID_EMPTY", node.getId(),
                        "INTENT_CLASSIFIER class id is required");
                continue;
            }
            if (!normalizedClassIds.add(classId.toLowerCase(Locale.ROOT))) {
                report.error("GRAPH_CLASSIFIER_CLASS_ID_DUPLICATE", node.getId(),
                        "Duplicate INTENT_CLASSIFIER class id: " + classId);
                continue;
            }
            classIds.add(classId);
        }

        for (String classId : classIds) {
            if (!hasRouteEdge(edges, node.getId(), classId)) {
                report.error("GRAPH_CLASSIFIER_CLASS_ROUTE_MISSING", node.getId(),
                        "INTENT_CLASSIFIER class has no matching outgoing edge: " + classId);
            }
        }
        String defaultRoute = firstText(text(config.get("defaultRoute")), "else");
        if (!hasRouteEdge(edges, node.getId(), defaultRoute)) {
            report.error("GRAPH_CLASSIFIER_DEFAULT_ROUTE_MISSING", node.getId(),
                    "INTENT_CLASSIFIER default route has no matching outgoing edge: " + defaultRoute);
        }
    }
}

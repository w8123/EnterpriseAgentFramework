package com.enterprise.ai.spring.registry;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LangGraph4j-like builder that emits the platform GraphSpec contract.
 */
public final class EafGraph {

    public static final String START = "START";
    public static final String END = "END";

    private EafGraph() {
    }

    public static Builder agent(String code) {
        return new Builder(code);
    }

    public static List<String> supportedNodeTypes() {
        return EafGraphNodeType.supportedTypes();
    }

    public static class Builder {
        private final String code;
        private String name;
        private String description;
        private String runtimeType = "LANGGRAPH4J";
        private String modelInstanceId;
        private String systemPrompt;
        private String visibility = "PROJECT";
        private final List<NodeDraft> nodes = new ArrayList<>();
        private final List<EdgeDraft> edges = new ArrayList<>();
        private final Map<String, Object> metadata = new LinkedHashMap<>();
        private final Map<String, Object> layout = new LinkedHashMap<>();
        private NodeDraft currentNode;
        private String explicitEntry;
        private final List<String> finish = new ArrayList<>();

        private Builder(String code) {
            if (!StringUtils.hasText(code)) {
                throw new IllegalArgumentException("agent graph code is required");
            }
            this.code = code.trim();
            this.name = this.code;
        }

        public Builder name(String name) {
            this.name = textOr(name, this.name);
            return this;
        }

        public Builder description(String description) {
            this.description = trimToNull(description);
            return this;
        }

        public Builder runtimeType(String runtimeType) {
            this.runtimeType = textOr(runtimeType, this.runtimeType).toUpperCase();
            return this;
        }

        public Builder modelInstanceId(String modelInstanceId) {
            this.modelInstanceId = trimToNull(modelInstanceId);
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = trimToNull(systemPrompt);
            return this;
        }

        public Builder visibility(String visibility) {
            this.visibility = textOr(visibility, this.visibility).toUpperCase();
            return this;
        }

        public Builder metadata(String key, Object value) {
            if (StringUtils.hasText(key) && value != null) {
                metadata.put(key.trim(), value);
            }
            return this;
        }

        public Builder layout(String direction) {
            layout.put("engine", "sdk");
            layout.put("direction", StringUtils.hasText(direction) ? direction.trim().toUpperCase() : "LR");
            return this;
        }

        public Builder llm(String id) {
            currentNode = addNode(id, EafGraphNodeType.LLM.type());
            if (modelInstanceId != null) {
                currentNode.config.put("modelInstanceId", modelInstanceId);
            }
            return this;
        }

        public Builder llmPrompt(String systemPrompt, String userPrompt) {
            NodeDraft node = requireCurrentNode();
            if (StringUtils.hasText(systemPrompt)) {
                node.config.put("systemPrompt", systemPrompt.trim());
            }
            if (StringUtils.hasText(userPrompt)) {
                node.config.put("userPrompt", userPrompt.trim());
            }
            return this;
        }

        @SuppressWarnings("unchecked")
        public Builder modelParam(String key, Object value) {
            if (!StringUtils.hasText(key) || value == null) {
                return this;
            }
            NodeDraft node = requireCurrentNode();
            Map<String, Object> params = (Map<String, Object>) node.config.computeIfAbsent(
                    "modelParams", ignored -> new LinkedHashMap<String, Object>());
            params.put(key.trim(), value);
            return this;
        }

        public Builder llmOutputFormat(String outputFormat) {
            if (StringUtils.hasText(outputFormat)) {
                requireCurrentNode().config.put("outputFormat", outputFormat.trim().toLowerCase());
            }
            return this;
        }

        @SuppressWarnings("unchecked")
        public Builder llmOutputField(String name, String type, boolean required, Object defaultValue) {
            if (!StringUtils.hasText(name)) {
                return this;
            }
            NodeDraft node = requireCurrentNode();
            node.config.put("outputFormat", "json");
            List<Map<String, Object>> fields = (List<Map<String, Object>>) node.config.computeIfAbsent(
                    "outputSchema", ignored -> new ArrayList<Map<String, Object>>());
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", name.trim());
            field.put("type", StringUtils.hasText(type) ? type.trim().toLowerCase() : "string");
            field.put("required", required);
            if (defaultValue != null) {
                field.put("defaultValue", defaultValue);
            }
            fields.add(field);
            return this;
        }

        public Builder tool(String id) {
            currentNode = addNode(id, EafGraphNodeType.TOOL.type());
            currentNode.ref.put("kind", "TOOL");
            currentNode.ref.put("name", id);
            return this;
        }

        public Builder capability(String id) {
            currentNode = addNode(id, EafGraphNodeType.CAPABILITY.type());
            currentNode.ref.put("kind", "CAPABILITY");
            currentNode.ref.put("name", id);
            return this;
        }

        public Builder ifElse(String id) {
            currentNode = addNode(id, EafGraphNodeType.IF_ELSE.type());
            return this;
        }

        public Builder variable(String id) {
            currentNode = addNode(id, EafGraphNodeType.VARIABLE_ASSIGN.type());
            return this;
        }

        public Builder template(String id, String template) {
            currentNode = addNode(id, EafGraphNodeType.TEMPLATE.type());
            if (StringUtils.hasText(template)) {
                currentNode.config.put("template", template);
                currentNode.config.put("writeToAnswer", true);
            }
            return this;
        }

        public Builder answer(String id, String template) {
            currentNode = addNode(id, EafGraphNodeType.ANSWER.type());
            currentNode.config.put("template", StringUtils.hasText(template) ? template : "{{ lastOutput }}");
            currentNode.config.put("writeToAnswer", true);
            return this;
        }

        public Builder code(String id) {
            currentNode = addNode(id, EafGraphNodeType.CODE.type());
            currentNode.config.put("language", "expression");
            return this;
        }

        public Builder intentClassifier(String id) {
            currentNode = addNode(id, EafGraphNodeType.INTENT_CLASSIFIER.type());
            currentNode.config.put("inputExpression", "input");
            currentNode.config.put("defaultRoute", "else");
            currentNode.output("else", "boolean", false);
            return this;
        }

        public Builder variableAggregator(String id) {
            currentNode = addNode(id, EafGraphNodeType.VARIABLE_AGGREGATOR.type());
            currentNode.config.put("aggregateMode", "object");
            return this;
        }

        public Builder humanApproval(String id, String prompt) {
            currentNode = addNode(id, EafGraphNodeType.HUMAN_APPROVAL.type());
            currentNode.config.put("title", "人工确认");
            currentNode.config.put("prompt", StringUtils.hasText(prompt) ? prompt : "{{ lastOutput }}");
            currentNode.config.put("defaultRoute", "approved");
            currentNode.output("approved", "boolean", false);
            currentNode.output("rejected", "boolean", false);
            return this;
        }

        public Builder loop(String id, int maxIterations) {
            currentNode = addNode(id, EafGraphNodeType.LOOP.type());
            currentNode.config.put("loopKey", id);
            currentNode.config.put("maxIterations", Math.max(1, maxIterations));
            currentNode.output("continue", "boolean", false);
            currentNode.output("done", "boolean", false);
            return this;
        }

        public Builder knowledgeWrite(String id, String knowledgeBaseCode) {
            currentNode = addNode(id, EafGraphNodeType.KNOWLEDGE_WRITE.type());
            currentNode.config.put("knowledgeBaseCode", trimToNull(knowledgeBaseCode));
            currentNode.config.put("titleExpression", "const:工作流写入");
            currentNode.config.put("contentExpression", "lastOutput");
            currentNode.config.put("writeMode", "draft");
            return this;
        }

        public Builder documentExtract(String id) {
            currentNode = addNode(id, EafGraphNodeType.DOCUMENT_EXTRACT.type());
            currentNode.config.put("sourceExpression", "lastOutput");
            currentNode.config.put("format", "text");
            return this;
        }

        public Builder mcpCall(String id, String toolName) {
            currentNode = addNode(id, EafGraphNodeType.MCP_CALL.type());
            currentNode.config.put("toolName", trimToNull(toolName));
            return this;
        }

        public Builder parameterExtract(String id) {
            currentNode = addNode(id, EafGraphNodeType.PARAMETER_EXTRACT.type());
            return this;
        }

        public Builder http(String id, String method, String url) {
            currentNode = addNode(id, EafGraphNodeType.HTTP_REQUEST.type());
            currentNode.config.put("method", StringUtils.hasText(method) ? method.trim().toUpperCase() : "GET");
            currentNode.config.put("url", StringUtils.hasText(url) ? url.trim() : "");
            return this;
        }

        public Builder knowledgeRetrieval(String id, String knowledgeBaseGroupId) {
            currentNode = addNode(id, EafGraphNodeType.KNOWLEDGE_RETRIEVAL.type());
            currentNode.config.put("knowledgeBaseGroupId", trimToNull(knowledgeBaseGroupId));
            if (StringUtils.hasText(knowledgeBaseGroupId)) {
                currentNode.config.put("knowledgeBaseCodes", List.of(knowledgeBaseGroupId.trim()));
            }
            currentNode.config.put("query", "input");
            currentNode.config.put("topK", 5);
            currentNode.config.put("searchMode", "hybrid");
            currentNode.config.put("rerankEnabled", true);
            return this;
        }

        public Builder label(String label) {
            requireCurrentNode().name = trimToNull(label);
            return this;
        }

        public Builder ref(String qualifiedName) {
            NodeDraft node = requireCurrentNode();
            if (!StringUtils.hasText(qualifiedName)) {
                return this;
            }
            String trimmed = qualifiedName.trim();
            String name = trimmed.contains(":") ? trimmed.substring(trimmed.lastIndexOf(':') + 1) : trimmed;
            node.ref.put("name", name);
            node.ref.put("qualifiedName", trimmed);
            if (trimmed.contains(":")) {
                node.ref.put("projectCode", trimmed.substring(0, trimmed.indexOf(':')));
            }
            return this;
        }

        public Builder input(String targetPath, String expression) {
            if (!StringUtils.hasText(targetPath) || !StringUtils.hasText(expression)) {
                return this;
            }
            NodeDraft node = requireCurrentNode();
            @SuppressWarnings("unchecked")
            Map<String, String> inputMapping = (Map<String, String>) node.config.computeIfAbsent(
                    "inputMapping", ignored -> new LinkedHashMap<String, String>());
            inputMapping.put(targetPath.trim(), expression.trim());
            return this;
        }

        public Builder outputAlias(String alias) {
            if (StringUtils.hasText(alias)) {
                requireCurrentNode().config.put("outputAlias", alias.trim());
                requireCurrentNode().output(alias.trim(), "any", false);
            }
            return this;
        }

        public Builder inputPort(String id, String type, boolean required) {
            if (StringUtils.hasText(id)) {
                requireCurrentNode().input(id.trim(), type, required);
            }
            return this;
        }

        public Builder outputPort(String id, String type, boolean required) {
            if (StringUtils.hasText(id)) {
                requireCurrentNode().output(id.trim(), type, required);
            }
            return this;
        }

        public Builder retry(int maxAttempts, long backoffMs) {
            NodeDraft node = requireCurrentNode();
            Map<String, Object> retry = new LinkedHashMap<>();
            retry.put("enabled", maxAttempts > 1);
            retry.put("maxAttempts", Math.max(1, maxAttempts));
            retry.put("backoffMs", Math.max(0L, backoffMs));
            node.retry = retry;
            return this;
        }

        public Builder onError(String strategy) {
            NodeDraft node = requireCurrentNode();
            Map<String, Object> policy = new LinkedHashMap<>();
            policy.put("strategy", StringUtils.hasText(strategy) ? strategy.trim().toUpperCase() : "TERMINATE");
            node.errorPolicy = policy;
            return this;
        }

        public Builder fallback(String fallbackNodeId) {
            if (!StringUtils.hasText(fallbackNodeId)) {
                return this;
            }
            NodeDraft node = requireCurrentNode();
            Map<String, Object> policy = node.errorPolicy == null ? new LinkedHashMap<>() : new LinkedHashMap<>(node.errorPolicy);
            policy.put("strategy", "FALLBACK");
            policy.put("fallbackNodeId", fallbackNodeId.trim());
            node.errorPolicy = policy;
            return this;
        }

        @SuppressWarnings("unchecked")
        public Builder assign(String variableName, String expression) {
            if (!StringUtils.hasText(variableName) || !StringUtils.hasText(expression)) {
                return this;
            }
            NodeDraft node = requireCurrentNode();
            Map<String, String> assignments = (Map<String, String>) node.config.computeIfAbsent(
                    "assignments", ignored -> new LinkedHashMap<String, String>());
            assignments.put(variableName.trim(), expression.trim());
            return this;
        }

        @SuppressWarnings("unchecked")
        public Builder parameter(String name, String expression) {
            if (!StringUtils.hasText(name) || !StringUtils.hasText(expression)) {
                return this;
            }
            NodeDraft node = requireCurrentNode();
            List<Map<String, Object>> fields = (List<Map<String, Object>>) node.config.computeIfAbsent(
                    "fields", ignored -> new ArrayList<Map<String, Object>>());
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", name.trim());
            field.put("type", "string");
            field.put("required", false);
            field.put("source", expression.trim());
            fields.add(field);
            node.config.putIfAbsent("extractMode", "expression");
            return this;
        }

        @SuppressWarnings("unchecked")
        public Builder output(String name, String expression) {
            if (!StringUtils.hasText(name) || !StringUtils.hasText(expression)) {
                return this;
            }
            NodeDraft node = requireCurrentNode();
            Map<String, String> outputs = (Map<String, String>) node.config.computeIfAbsent(
                    "outputs", ignored -> new LinkedHashMap<String, String>());
            outputs.put(name.trim(), expression.trim());
            node.output(name.trim(), "any", false);
            return this;
        }

        @SuppressWarnings("unchecked")
        public Builder intentClass(String id, String label, String... keywords) {
            if (!StringUtils.hasText(id)) {
                return this;
            }
            NodeDraft node = requireCurrentNode();
            List<Map<String, Object>> classes = (List<Map<String, Object>>) node.config.computeIfAbsent(
                    "classes", ignored -> new ArrayList<Map<String, Object>>());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id.trim());
            item.put("label", StringUtils.hasText(label) ? label.trim() : id.trim());
            item.put("keywords", keywords == null ? List.of() : java.util.Arrays.stream(keywords)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .toList());
            classes.add(item);
            node.output(id.trim(), "boolean", false);
            return this;
        }

        public Builder classifierInput(String expression) {
            if (StringUtils.hasText(expression)) {
                requireCurrentNode().config.put("inputExpression", expression.trim());
            }
            return this;
        }

        @SuppressWarnings("unchecked")
        public Builder aggregateItem(String name, String expression) {
            if (!StringUtils.hasText(name) || !StringUtils.hasText(expression)) {
                return this;
            }
            NodeDraft node = requireCurrentNode();
            List<Map<String, Object>> items = (List<Map<String, Object>>) node.config.computeIfAbsent(
                    "items", ignored -> new ArrayList<Map<String, Object>>());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name.trim());
            item.put("source", expression.trim());
            items.add(item);
            return this;
        }

        public Builder aggregateMode(String mode) {
            if (StringUtils.hasText(mode)) {
                requireCurrentNode().config.put("aggregateMode", mode.trim().toLowerCase());
            }
            return this;
        }

        public Builder approver(String approver) {
            if (!StringUtils.hasText(approver)) {
                return this;
            }
            NodeDraft node = requireCurrentNode();
            @SuppressWarnings("unchecked")
            List<String> approvers = (List<String>) node.config.computeIfAbsent("approvers", ignored -> new ArrayList<String>());
            approvers.add(approver.trim());
            return this;
        }

        public Builder loopKey(String loopKey) {
            if (StringUtils.hasText(loopKey)) {
                requireCurrentNode().config.put("loopKey", loopKey.trim());
            }
            return this;
        }

        public Builder breakWhen(String condition) {
            if (StringUtils.hasText(condition)) {
                requireCurrentNode().config.put("breakCondition", condition.trim());
            }
            return this;
        }

        public Builder knowledgeContent(String titleExpression, String contentExpression) {
            NodeDraft node = requireCurrentNode();
            if (StringUtils.hasText(titleExpression)) {
                node.config.put("titleExpression", titleExpression.trim());
            }
            if (StringUtils.hasText(contentExpression)) {
                node.config.put("contentExpression", contentExpression.trim());
            }
            return this;
        }

        public Builder knowledgeTag(String tag) {
            if (!StringUtils.hasText(tag)) {
                return this;
            }
            NodeDraft node = requireCurrentNode();
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) node.config.computeIfAbsent("tags", ignored -> new ArrayList<String>());
            tags.add(tag.trim());
            return this;
        }

        public Builder documentField(String name, String source) {
            if (!StringUtils.hasText(name)) {
                return this;
            }
            NodeDraft node = requireCurrentNode();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) node.config.computeIfAbsent(
                    "fields", ignored -> new ArrayList<Map<String, Object>>());
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", name.trim());
            field.put("type", "string");
            field.put("source", StringUtils.hasText(source) ? source.trim() : "");
            fields.add(field);
            return this;
        }

        public Builder mcpServer(String serverRef) {
            if (StringUtils.hasText(serverRef)) {
                requireCurrentNode().config.put("serverRef", serverRef.trim());
            }
            return this;
        }

        @SuppressWarnings("unchecked")
        public Builder parameterField(String name, String type, boolean required, String sourceExpression) {
            return parameterField(name, type, required, sourceExpression, null);
        }

        @SuppressWarnings("unchecked")
        public Builder parameterField(String name, String type, boolean required, String sourceExpression, Object defaultValue) {
            if (!StringUtils.hasText(name)) {
                return this;
            }
            NodeDraft node = requireCurrentNode();
            List<Map<String, Object>> fields = (List<Map<String, Object>>) node.config.computeIfAbsent(
                    "fields", ignored -> new ArrayList<Map<String, Object>>());
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", name.trim());
            field.put("type", StringUtils.hasText(type) ? type.trim().toLowerCase() : "string");
            field.put("required", required);
            if (StringUtils.hasText(sourceExpression)) {
                field.put("source", sourceExpression.trim());
            }
            if (defaultValue != null) {
                field.put("defaultValue", defaultValue);
            }
            fields.add(field);
            node.config.putIfAbsent("extractMode", "expression");
            return this;
        }

        public Builder extractMode(String mode) {
            if (StringUtils.hasText(mode)) {
                requireCurrentNode().config.put("extractMode", mode.trim().toLowerCase());
            }
            return this;
        }

        @SuppressWarnings("unchecked")
        public Builder header(String name, String value) {
            if (!StringUtils.hasText(name) || !StringUtils.hasText(value)) {
                return this;
            }
            NodeDraft node = requireCurrentNode();
            Map<String, String> headers = (Map<String, String>) node.config.computeIfAbsent(
                    "headers", ignored -> new LinkedHashMap<String, String>());
            headers.put(name.trim(), value.trim());
            return this;
        }

        public Builder body(String body) {
            requireCurrentNode().config.put("body", body == null ? "" : body);
            return this;
        }

        public Builder bodyType(String bodyType) {
            if (StringUtils.hasText(bodyType)) {
                requireCurrentNode().config.put("bodyType", bodyType.trim().toLowerCase());
            }
            return this;
        }

        @SuppressWarnings("unchecked")
        public Builder queryParam(String name, String value) {
            if (!StringUtils.hasText(name) || !StringUtils.hasText(value)) {
                return this;
            }
            NodeDraft node = requireCurrentNode();
            Map<String, String> params = (Map<String, String>) node.config.computeIfAbsent(
                    "queryParams", ignored -> new LinkedHashMap<String, String>());
            params.put(name.trim(), value.trim());
            return this;
        }

        public Builder timeoutMs(long timeoutMs) {
            requireCurrentNode().config.put("timeoutMs", Math.max(1L, timeoutMs));
            return this;
        }

        public Builder credentialRef(String credentialRef) {
            if (StringUtils.hasText(credentialRef)) {
                requireCurrentNode().config.put("credentialRef", credentialRef.trim());
            }
            return this;
        }

        public Builder query(String expression) {
            if (StringUtils.hasText(expression)) {
                requireCurrentNode().config.put("query", expression.trim());
            }
            return this;
        }

        public Builder topK(int topK) {
            requireCurrentNode().config.put("topK", Math.max(1, topK));
            return this;
        }

        public Builder searchMode(String searchMode) {
            if (StringUtils.hasText(searchMode)) {
                requireCurrentNode().config.put("searchMode", searchMode.trim());
            }
            return this;
        }

        public Builder rerankEnabled(boolean rerankEnabled) {
            requireCurrentNode().config.put("rerankEnabled", rerankEnabled);
            return this;
        }

        public Builder similarityThreshold(double threshold) {
            requireCurrentNode().config.put("similarityThreshold", Math.max(0D, Math.min(1D, threshold)));
            return this;
        }

        public Builder directReturn(boolean enabled, double threshold) {
            NodeDraft node = requireCurrentNode();
            node.config.put("directReturnEnabled", enabled);
            node.config.put("directReturnThreshold", Math.max(0D, Math.min(1D, threshold)));
            return this;
        }

        @SuppressWarnings("unchecked")
        public Builder conditionGroup(String id, String logic) {
            if (!StringUtils.hasText(id)) {
                return this;
            }
            NodeDraft node = requireCurrentNode();
            List<Map<String, Object>> groups = (List<Map<String, Object>>) node.config.computeIfAbsent(
                    "conditionGroups", ignored -> new ArrayList<Map<String, Object>>());
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("id", id.trim());
            group.put("logic", StringUtils.hasText(logic) ? logic.trim().toUpperCase() : "AND");
            group.put("conditions", new ArrayList<Map<String, Object>>());
            groups.add(group);
            return this;
        }

        @SuppressWarnings("unchecked")
        public Builder condition(String groupId, String left, String operator, String right) {
            if (!StringUtils.hasText(groupId) || !StringUtils.hasText(left)) {
                return this;
            }
            NodeDraft node = requireCurrentNode();
            List<Map<String, Object>> groups = (List<Map<String, Object>>) node.config.computeIfAbsent(
                    "conditionGroups", ignored -> new ArrayList<Map<String, Object>>());
            Map<String, Object> group = groups.stream()
                    .filter(item -> groupId.trim().equals(item.get("id")))
                    .findFirst()
                    .orElseGet(() -> {
                        Map<String, Object> created = new LinkedHashMap<>();
                        created.put("id", groupId.trim());
                        created.put("logic", "AND");
                        created.put("conditions", new ArrayList<Map<String, Object>>());
                        groups.add(created);
                        return created;
                    });
            List<Map<String, Object>> conditions = (List<Map<String, Object>>) group.computeIfAbsent(
                    "conditions", ignored -> new ArrayList<Map<String, Object>>());
            Map<String, Object> condition = new LinkedHashMap<>();
            condition.put("left", left.trim());
            condition.put("operator", StringUtils.hasText(operator) ? operator.trim().toLowerCase() : "exists");
            condition.put("right", right == null ? "" : right.trim());
            conditions.add(condition);
            return this;
        }

        public Builder defaultRoute(String route) {
            if (StringUtils.hasText(route)) {
                NodeDraft node = requireCurrentNode();
                String trimmed = route.trim();
                node.config.put("defaultRoute", trimmed);
                if (EafGraphNodeType.INTENT_CLASSIFIER.type().equals(node.type)) {
                    node.output(trimmed, "boolean", false);
                }
            }
            return this;
        }

        public Builder writeTemplateToAnswer(boolean writeToAnswer) {
            requireCurrentNode().config.put("writeToAnswer", writeToAnswer);
            return this;
        }

        public Builder nodeDescription(String description) {
            if (StringUtils.hasText(description)) {
                requireCurrentNode().config.put("description", description.trim());
            }
            return this;
        }

        @SuppressWarnings("unchecked")
        public Builder position(int x, int y) {
            NodeDraft node = requireCurrentNode();
            node.layout.put("x", x);
            node.layout.put("y", y);
            Map<String, Object> ui = (Map<String, Object>) node.config.computeIfAbsent(
                    "ui", ignored -> new LinkedHashMap<String, Object>());
            ui.put("position", Map.of("x", x, "y", y));
            return this;
        }

        @SuppressWarnings("unchecked")
        public Builder ui(String key, Object value) {
            if (!StringUtils.hasText(key) || value == null) {
                return this;
            }
            NodeDraft node = requireCurrentNode();
            Map<String, Object> ui = (Map<String, Object>) node.config.computeIfAbsent(
                    "ui", ignored -> new LinkedHashMap<String, Object>());
            ui.put(key.trim(), value);
            return this;
        }

        public Builder config(String key, Object value) {
            if (StringUtils.hasText(key) && value != null) {
                requireCurrentNode().config.put(key.trim(), value);
            }
            return this;
        }

        public Builder entry(String nodeId) {
            this.explicitEntry = normalizeEndpoint(nodeId);
            return this;
        }

        public Builder finish(String nodeId) {
            String normalized = normalizeEndpoint(nodeId);
            if (!END.equals(normalized)) {
                finish.add(normalized);
            }
            return this;
        }

        public EdgeBuilder edge(String from, String to) {
            EdgeDraft edge = new EdgeDraft(normalizeEndpoint(from), normalizeEndpoint(to), "always");
            edges.add(edge);
            return new EdgeBuilder(this, edge);
        }

        public EafAgentGraph build() {
            validate();
            normalizeNodes();
            List<EdgeDraft> normalizedEdges = new ArrayList<>(edges);
            String entry = resolveEntry(normalizedEdges);
            ensureStartEdge(normalizedEdges, entry);
            ensureFinishEdge(normalizedEdges);
            normalizeClassifierEdges(normalizedEdges);
            Map<String, Object> graphSpec = new LinkedHashMap<>();
            graphSpec.put("code", code);
            graphSpec.put("name", name);
            graphSpec.put("mode", "WORKFLOW");
            graphSpec.put("runtimeHint", runtimeType);
            graphSpec.put("layout", layout.isEmpty() ? Map.of("engine", "sdk", "direction", "LR") : Map.copyOf(layout));
            graphSpec.put("nodes", nodes.stream().map(NodeDraft::toMap).toList());
            graphSpec.put("edges", normalizedEdges.stream().map(EdgeDraft::toMap).toList());
            graphSpec.put("entry", entry);
            graphSpec.put("finish", finish.isEmpty() ? List.of(lastExecutableNodeId()) : List.copyOf(finish));
            return new EafAgentGraph(
                    code,
                    name,
                    description,
                    runtimeType,
                    modelInstanceId,
                    systemPrompt,
                    visibility,
                    graphSpec,
                    Map.copyOf(metadata));
        }

        private NodeDraft addNode(String id, String type) {
            if (!StringUtils.hasText(id)) {
                throw new IllegalArgumentException("node id is required");
            }
            NodeDraft node = new NodeDraft(id.trim(), type);
            nodes.add(node);
            return node;
        }

        private NodeDraft requireCurrentNode() {
            if (currentNode == null) {
                throw new IllegalStateException("No current node. Call a node builder method before configuring node details.");
            }
            return currentNode;
        }

        private void validate() {
            Set<String> ids = new LinkedHashSet<>();
            boolean hasLlm = false;
            for (NodeDraft node : nodes) {
                if (!ids.add(node.id)) {
                    throw new IllegalArgumentException("duplicate graph node id: " + node.id);
                }
                hasLlm = hasLlm || EafGraphNodeType.LLM.type().equals(node.type);
            }
            if (nodes.isEmpty()) {
                throw new IllegalArgumentException("agent graph must declare at least one node");
            }
            if (!hasLlm) {
                throw new IllegalArgumentException("agent graph must declare at least one LLM node");
            }
            for (EdgeDraft edge : edges) {
                requireEndpoint(ids, edge.from);
                requireEndpoint(ids, edge.to);
            }
        }

        private void normalizeNodes() {
            if (!StringUtils.hasText(modelInstanceId)) {
                return;
            }
            for (NodeDraft node : nodes) {
                if (EafGraphNodeType.LLM.type().equals(node.type)) {
                    node.config.putIfAbsent("modelInstanceId", modelInstanceId);
                }
            }
        }

        private void normalizeClassifierEdges(List<EdgeDraft> normalizedEdges) {
            for (EdgeDraft edge : normalizedEdges) {
                NodeDraft source = findNode(edge.from);
                if (source == null || !EafGraphNodeType.INTENT_CLASSIFIER.type().equals(source.type)) {
                    continue;
                }
                if (!StringUtils.hasText(edge.sourceHandle)) {
                    edge.sourceHandle = routeHandle(edge.condition);
                }
                if (!StringUtils.hasText(edge.sourceHandle)) {
                    throw new IllegalArgumentException("intent classifier edge must use a class route: " + edge.from + " -> " + edge.to);
                }
                if (!StringUtils.hasText(edge.condition) || "always".equalsIgnoreCase(edge.condition)) {
                    edge.condition = "else".equalsIgnoreCase(edge.sourceHandle) || "default".equalsIgnoreCase(edge.sourceHandle)
                            ? "else"
                            : "route:" + edge.sourceHandle;
                }
            }
        }

        private NodeDraft findNode(String nodeId) {
            if (!StringUtils.hasText(nodeId)) {
                return null;
            }
            return nodes.stream()
                    .filter(node -> nodeId.equals(node.id))
                    .findFirst()
                    .orElse(null);
        }

        private String routeHandle(String condition) {
            if (!StringUtils.hasText(condition)) {
                return null;
            }
            String trimmed = condition.trim();
            String normalized = trimmed.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("route:")) {
                String handle = trimmed.substring("route:".length()).trim();
                return StringUtils.hasText(handle) ? handle : null;
            }
            if ("else".equals(normalized) || "default".equals(normalized)) {
                return normalized;
            }
            return null;
        }

        private void requireEndpoint(Set<String> ids, String endpoint) {
            if (!START.equals(endpoint) && !END.equals(endpoint) && !ids.contains(endpoint)) {
                throw new IllegalArgumentException("edge endpoint does not exist: " + endpoint);
            }
        }

        private String resolveEntry(List<EdgeDraft> normalizedEdges) {
            if (StringUtils.hasText(explicitEntry)) {
                return explicitEntry;
            }
            return normalizedEdges.stream()
                    .filter(edge -> START.equals(edge.from))
                    .map(edge -> edge.to)
                    .filter(to -> !END.equals(to))
                    .findFirst()
                    .orElse(nodes.get(0).id);
        }

        private void ensureStartEdge(List<EdgeDraft> normalizedEdges, String entry) {
            boolean exists = normalizedEdges.stream().anyMatch(edge -> START.equals(edge.from));
            if (!exists) {
                normalizedEdges.add(0, new EdgeDraft(START, entry, "always"));
            }
        }

        private void ensureFinishEdge(List<EdgeDraft> normalizedEdges) {
            boolean hasEnd = normalizedEdges.stream().anyMatch(edge -> END.equals(edge.to));
            if (!hasEnd) {
                normalizedEdges.add(new EdgeDraft(lastExecutableNodeId(), END, "always"));
            }
        }

        private String lastExecutableNodeId() {
            return nodes.get(nodes.size() - 1).id;
        }

        private String normalizeEndpoint(String id) {
            if (!StringUtils.hasText(id)) {
                throw new IllegalArgumentException("edge endpoint is required");
            }
            String trimmed = id.trim();
            if ("start".equalsIgnoreCase(trimmed)) {
                return START;
            }
            if ("end".equalsIgnoreCase(trimmed)) {
                return END;
            }
            return trimmed;
        }

        private String textOr(String value, String fallback) {
            return StringUtils.hasText(value) ? value.trim() : fallback;
        }

        private String trimToNull(String value) {
            return StringUtils.hasText(value) ? value.trim() : null;
        }
    }

    public static class EdgeBuilder {
        private final Builder parent;
        private final EdgeDraft edge;

        private EdgeBuilder(Builder parent, EdgeDraft edge) {
            this.parent = parent;
            this.edge = edge;
        }

        public Builder always() {
            edge.condition = "always";
            return parent;
        }

        public Builder when(String condition) {
            edge.condition = StringUtils.hasText(condition) ? condition.trim() : "always";
            if (!StringUtils.hasText(edge.sourceHandle)) {
                edge.sourceHandle = routeHandle(edge.condition);
            }
            return parent;
        }

        public EdgeBuilder label(String label) {
            if (StringUtils.hasText(label)) {
                edge.layout.put("label", label.trim());
            }
            return this;
        }

        public EdgeBuilder handles(String sourceHandle, String targetHandle) {
            edge.sourceHandle = StringUtils.hasText(sourceHandle) ? sourceHandle.trim() : null;
            edge.targetHandle = StringUtils.hasText(targetHandle) ? targetHandle.trim() : null;
            return this;
        }

        private String routeHandle(String condition) {
            if (!StringUtils.hasText(condition)) {
                return null;
            }
            String trimmed = condition.trim();
            String normalized = trimmed.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("route:")) {
                String handle = trimmed.substring("route:".length()).trim();
                return StringUtils.hasText(handle) ? handle : null;
            }
            if ("else".equals(normalized) || "default".equals(normalized)) {
                return normalized;
            }
            return null;
        }

        public EdgeBuilder priority(int priority) {
            edge.priority = priority;
            return this;
        }

        public EafAgentGraph build() {
            return parent.build();
        }
    }

    private static class NodeDraft {
        private final String id;
        private final String type;
        private String name;
        private final Map<String, Object> ref = new LinkedHashMap<>();
        private final Map<String, Object> config = new LinkedHashMap<>();
        private final List<Map<String, Object>> inputs = new ArrayList<>();
        private final List<Map<String, Object>> outputs = new ArrayList<>();
        private Map<String, Object> retry;
        private Map<String, Object> errorPolicy;
        private final Map<String, Object> layout = new LinkedHashMap<>();

        private NodeDraft(String id, String type) {
            this.id = id;
            this.type = type;
            this.name = id;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("type", type);
            out.put("name", name);
            Object description = config.get("description");
            if (description != null) {
                out.put("description", description);
            }
            if (!ref.isEmpty()) {
                out.put("ref", ref);
            }
            if (!inputs.isEmpty()) {
                out.put("inputs", inputs);
            }
            if (!outputs.isEmpty()) {
                out.put("outputs", outputs);
            }
            if (retry != null && !retry.isEmpty()) {
                out.put("retry", retry);
            }
            if (errorPolicy != null && !errorPolicy.isEmpty()) {
                out.put("errorPolicy", errorPolicy);
            }
            if (!layout.isEmpty()) {
                out.put("layout", layout);
            }
            if (!config.isEmpty()) {
                out.put("config", config);
            }
            return out;
        }

        private void input(String id, String type, boolean required) {
            inputs.add(port(id, type, required));
        }

        private void output(String id, String type, boolean required) {
            outputs.removeIf(port -> id.equals(port.get("id")));
            outputs.add(port(id, type, required));
        }

        private Map<String, Object> port(String id, String type, boolean required) {
            Map<String, Object> port = new LinkedHashMap<>();
            port.put("id", id);
            port.put("name", id);
            port.put("type", StringUtils.hasText(type) ? type.trim().toLowerCase() : "any");
            port.put("required", required);
            return port;
        }
    }

    private static class EdgeDraft {
        private final String from;
        private final String to;
        private String condition;
        private String sourceHandle;
        private String targetHandle;
        private Integer priority;
        private final Map<String, Object> layout = new LinkedHashMap<>();

        private EdgeDraft(String from, String to, String condition) {
            this.from = from;
            this.to = to;
            this.condition = condition;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", "e-" + from + "-" + to);
            out.put("from", from);
            out.put("to", to);
            out.put("condition", condition);
            if (StringUtils.hasText(sourceHandle)) {
                out.put("sourceHandle", sourceHandle);
            }
            if (StringUtils.hasText(targetHandle)) {
                out.put("targetHandle", targetHandle);
            }
            if (priority != null) {
                out.put("priority", priority);
            }
            if (!layout.isEmpty()) {
                out.put("layout", layout);
            }
            return out;
        }
    }
}

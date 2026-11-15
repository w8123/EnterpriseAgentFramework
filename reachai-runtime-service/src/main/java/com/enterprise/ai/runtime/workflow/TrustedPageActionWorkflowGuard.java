package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.common.page.TrustedPageDraftInputContract;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A conservative structural gate, not an attestation of browser code. Declarations must come
 * exclusively from the authenticated Control internal API, which rechecks the administrator's
 * review and current catalog fingerprint on every lookup. Never construct them from client input.
 */
public final class TrustedPageActionWorkflowGuard {
    private static final Set<String> ACTION_CONFIG = Set.of("projectCode", "pageKey", "actionKey",
            "route", "routePattern", "inputMapping", "args", "confirm", "confirmRequired");
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public record TrustedActionDeclaration(Long approvalId, long revision, Long pageId, Long actionId,
                                           String projectCode, String pageKey, String actionKey,
                                           String effect, String catalogHash, String implementationVersion,
                                           String targetAlias, String actionTitle, String actionDescription,
                                           String routePattern, String inputSchemaJson, Map<String, String> fieldLabels, boolean active) { }

    public record Pin(String workflowId, Long workflowVersionId, String workflowVersion,
                      String graphHash, TrustedActionDeclaration declaration) { }

    /** A lookup hint only; this is not an authorization or an attestation. */
    public record Candidate(String projectCode, String pageKey, String actionKey) { }

    public Optional<Candidate> candidate(RuntimeTrustedWorkflowTarget target) {
        if (target == null || target.workflow() == null || target.version() == null
                || !"ACTIVE".equalsIgnoreCase(target.version().getStatus())
                || !hasText(target.version().getGraphSpecSnapshotJson())
                || !literal(target.workflow().getProjectCode())) return Optional.empty();
        try {
            GraphSpec graph = mapper.readValue(target.version().getGraphSpecSnapshotJson(), GraphSpec.class);
            GraphSpec.Node action = null;
            if (graph.getNodes() == null) return Optional.empty();
            for (GraphSpec.Node node : graph.getNodes()) {
                if (node == null) return Optional.empty();
                if ("PAGE_ACTION".equals(node.getType()) && action == null) action = node;
                else if (!"ANSWER".equals(node.getType()) && !"USER_INPUT".equals(node.getType())) return Optional.empty();
            }
            if (action == null || action.getConfig() == null) return Optional.empty();
            Map<String, Object> config = action.getConfig();
            if (!(config.get("pageKey") instanceof String page) || !literal(page)
                    || !(config.get("actionKey") instanceof String key) || !literal(key)) return Optional.empty();
            String project = target.workflow().getProjectCode();
            if (config.containsKey("projectCode") && !sameLiteral(config.get("projectCode"), project)) return Optional.empty();
            return Optional.of(new Candidate(project, page, key));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /** Checks exact approved arguments; callers must also bind these same values to execution. */
    public boolean argumentsMatch(Pin pin, Map<String, Object> args) {
        if (pin == null || args == null || !validDeclaration(pin.declaration())) return false;
        try {
            JsonNode schema = mapper.readTree(pin.declaration().inputSchemaJson());
            if (!safeInputSchema(schema) || args.isEmpty()
                    || args.size() < schema.path("minProperties").asInt(0)
                    || args.size() > schema.path("maxProperties").asInt(Integer.MAX_VALUE)) return false;
            Set<String> approvedFields = new java.util.HashSet<>();
            schema.path("properties").fieldNames().forEachRemaining(approvedFields::add);
            if (!approvedFields.containsAll(args.keySet())) return false;
            for (JsonNode required : schema.path("required")) if (!args.containsKey(required.textValue())) return false;
            var properties = schema.path("properties").fields();
            while (properties.hasNext()) {
                var property = properties.next();
                String name = property.getKey();
                if (!TrustedPageDraftInputContract.validFieldName(name)
                        || args.containsKey(name) && (args.get(name) == null
                        || args.get(name) instanceof String s && s.isBlank()
                        || !scalarMatches(property.getValue().path("type").asText(), args.get(name))
                        || !stringLengthMatches(property.getValue(), args.get(name)))) return false;
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean stringLengthMatches(JsonNode property, Object value) {
        if (!(value instanceof String s)) return true;
        int length = s.codePointCount(0, s.length());
        return length >= property.path("minLength").asInt(0)
                && length <= property.path("maxLength").asInt(Integer.MAX_VALUE);
    }

    private boolean scalarMatches(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long || value instanceof java.math.BigInteger;
            case "number" -> value instanceof Number n && Double.isFinite(n.doubleValue());
            default -> false;
        };
    }

    /** Returns empty for any ambiguous, malformed, dynamic, unreviewed or unsupported graph. */
    public Optional<Pin> pin(RuntimeTrustedWorkflowTarget target, TrustedActionDeclaration declaration) {
        if (!validDeclaration(declaration) || target == null || target.workflow() == null
                || target.version() == null) return Optional.empty();
        RuntimeWorkflowPublishedVersionView version = target.version();
        String workflowId = target.workflow().getId();
        String graphJson = version.getGraphSpecSnapshotJson();
        if (!hasText(workflowId) || version.getId() == null || !hasText(version.getVersion())
                || !workflowId.equals(version.getWorkflowId())
                || !"ACTIVE".equalsIgnoreCase(version.getStatus()) || !hasText(graphJson)
                || !declaration.projectCode().equals(target.workflow().getProjectCode())) return Optional.empty();
        try {
            JsonNode raw = mapper.readTree(graphJson);
            if (raw == null || !raw.isObject() || dynamicSchema(raw.get("inputSchema"))
                    || dynamicSchema(raw.get("stateSchema"))) return Optional.empty();
            if (!raw.path("env").path("reachaiTrustedPageDraft").isBoolean()
                    || !raw.path("env").path("reachaiTrustedPageDraft").booleanValue()) return Optional.empty();
            JsonNode approvedSchema = mapper.readTree(declaration.inputSchemaJson());
            if (!safeInputSchema(approvedSchema) || !approvedSchema.equals(raw.get("inputSchema"))) return Optional.empty();
            GraphSpec graph = mapper.treeToValue(raw, GraphSpec.class);
            List<GraphSpec.Node> nodes = graph.getNodes();
            List<GraphSpec.Edge> edges = graph.getEdges();
            if (nodes == null || nodes.isEmpty() || nodes.size() > 3) return Optional.empty();
            GraphSpec.Node action = null;
            GraphSpec.Node answer = null;
            GraphSpec.Node input = null;
            for (GraphSpec.Node node : nodes) {
                if (node == null || !literal(node.getId()) || node.getRef() != null
                        || node.getRetry() != null || node.getErrorPolicy() != null) return Optional.empty();
                if ("PAGE_ACTION".equals(node.getType()) && action == null) action = node;
                else if ("ANSWER".equals(node.getType()) && answer == null) answer = node;
                else if ("USER_INPUT".equals(node.getType()) && input == null) input = node;
                else return Optional.empty();
            }
            if (action == null || !(input == null ? action : input).getId().equals(graph.getEntryNodeId())
                    || !validAction(action, declaration, approvedSchema)
                    || input != null && !validInput(input, approvedSchema)
                    || !validTopology(input, action, answer, edges)) return Optional.empty();
            if (answer != null && answer.getId().equals(action.getId())
                    || input != null && (input.getId().equals(action.getId()) || answer != null && input.getId().equals(answer.getId()))) return Optional.empty();
            return Optional.of(new Pin(workflowId, version.getId(), version.getVersion(), sha256(graphJson), declaration));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /** The declaration must be fetched afresh from authenticated Control after user confirmation. */
    public boolean matches(Pin original, RuntimeTrustedWorkflowTarget current, TrustedActionDeclaration currentDeclaration) {
        return original != null && pin(current, currentDeclaration).filter(original::equals).isPresent();
    }

    private boolean validAction(GraphSpec.Node node, TrustedActionDeclaration d, JsonNode schema) {
        Map<String, Object> config = node.getConfig();
        if (config == null || !ACTION_CONFIG.containsAll(config.keySet())) return false;
        if (node.getInputs() != null && !node.getInputs().isEmpty()) return false;
        if (!sameLiteral(config.get("pageKey"), d.pageKey()) || !sameLiteral(config.get("actionKey"), d.actionKey())) return false;
        if (config.containsKey("projectCode") && !sameLiteral(config.get("projectCode"), d.projectCode())) return false;
        if (config.containsKey("route") && !sameLiteral(config.get("route"), d.routePattern())) return false;
        if (config.containsKey("routePattern") && !sameLiteral(config.get("routePattern"), d.routePattern())) return false;
        if (config.containsKey("route") && config.containsKey("routePattern")
                && !config.get("route").equals(config.get("routePattern"))) return false;
        if (!(config.get("inputMapping") instanceof Map<?, ?> mapping)) return false;
        if (config.containsKey("args") && (!(config.get("args") instanceof Map<?, ?> args) || !args.isEmpty())) return false;
        JsonNode properties = schema.get("properties");
        if (mapping.size() != properties.size()) return false;
        var names = properties.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (!TrustedPageDraftInputContract.validFieldName(field)
                    || !("params." + field).equals(mapping.get(field))) return false;
        }
        if (config.containsKey("confirm") && !(config.get("confirm") instanceof Boolean)) return false;
        if (config.containsKey("confirmRequired") && !(config.get("confirmRequired") instanceof Boolean)) return false;
        return !dynamicSchema(mapper.valueToTree(node.getInputSchema()))
                && !dynamicSchema(mapper.valueToTree(node.getOutputSchema()));
    }

    private boolean validInput(GraphSpec.Node input, JsonNode schema) {
        if (input.getInputs() != null && !input.getInputs().isEmpty()) return false;
        Map<String, Object> config = input.getConfig();
        if (config == null || !Set.of("outputAlias", "fields").equals(config.keySet())
                || !"params".equals(config.get("outputAlias")) || !(config.get("fields") instanceof List<?> fields)
                || fields.size() != schema.path("properties").size()) return false;
        Set<String> seen = new java.util.HashSet<>();
        Set<String> required = new java.util.HashSet<>();
        schema.path("required").forEach(item -> required.add(item.asText()));
        for (Object value : fields) {
            if (!(value instanceof Map<?, ?> field) || !Set.of("name", "type", "required", "source").equals(field.keySet())
                    || !(field.get("name") instanceof String name) || !seen.add(name)
                    || !schema.path("properties").has(name) || !schema.path("properties").path(name).path("type").asText().equals(field.get("type"))
                    || !(field.get("required") instanceof Boolean r) || r != required.contains(name)
                    || !("input." + name).equals(field.get("source"))) return false;
        }
        return true;
    }

    private boolean validTopology(GraphSpec.Node input, GraphSpec.Node action, GraphSpec.Node answer, List<GraphSpec.Edge> edges) {
        int count = (input == null ? 0 : 1) + (answer == null ? 0 : 1);
        if (count == 0) return edges == null || edges.isEmpty();
        if (edges == null || edges.size() != count) return false;
        boolean inputEdge = input == null;
        boolean answerEdge = answer == null;
        for (GraphSpec.Edge edge : edges) {
            if (edge == null || hasText(edge.getCondition()) || hasText(edge.getSourceHandle()) || hasText(edge.getTargetHandle())) return false;
            if (input != null && input.getId().equals(edge.getFrom()) && action.getId().equals(edge.getTo()) && !inputEdge) inputEdge = true;
            else if (answer != null && action.getId().equals(edge.getFrom()) && answer.getId().equals(edge.getTo()) && !answerEdge) answerEdge = true;
            else return false;
        }
        return inputEdge && answerEdge;
    }

    private boolean validDeclaration(TrustedActionDeclaration d) {
        return d != null && d.active() && d.approvalId() != null && d.approvalId() > 0 && d.revision() > 0
                && d.pageId() != null && d.pageId() > 0 && d.actionId() != null && d.actionId() > 0
                && "PAGE_LOCAL_DRAFT".equals(d.effect()) && literal(d.projectCode()) && literal(d.pageKey())
                && literal(d.actionKey()) && literal(d.implementationVersion()) && literal(d.targetAlias())
                && literal(d.actionTitle()) && hasText(d.inputSchemaJson()) && validLabels(d)
                && d.catalogHash() != null && d.catalogHash().matches("[0-9a-f]{64}")
                && (d.routePattern() == null || literal(d.routePattern()));
    }

    private boolean validLabels(TrustedActionDeclaration d) {
        try {
            JsonNode properties = mapper.readTree(d.inputSchemaJson()).path("properties");
            if (!properties.isObject() || properties.isEmpty() || d.fieldLabels() == null) return false;
            Set<String> fields = new java.util.HashSet<>();
            properties.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(d.fieldLabels().keySet())) return false;
            Set<String> normalized = new java.util.HashSet<>();
            for (String label : d.fieldLabels().values()) {
                if (label == null || label.isBlank() || !label.equals(label.trim())
                        || label.codePointCount(0, label.length()) < 2 || label.codePointCount(0, label.length()) > 40
                        || label.codePoints().anyMatch(Character::isISOControl)) return false;
                String value = Normalizer.normalize(label, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
                for (String existing : normalized) if (existing.contains(value) || value.contains(existing)) return false;
                normalized.add(value);
            }
            return true;
        } catch (Exception malformed) { return false; }
    }

    private boolean safeInputSchema(JsonNode schema) {
        return TrustedPageDraftInputContract.validSchema(schema);
    }

    private boolean dynamicSchema(JsonNode node) {
        if (node == null || node.isNull()) return false;
        if (node.isObject()) {
            var it = node.fields();
            while (it.hasNext()) {
                var entry = it.next();
                if (entry.getKey().equals("$ref") || entry.getKey().equals("$dynamicRef") || entry.getKey().equals("default")
                        || dynamicSchema(entry.getValue())) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) if (dynamicSchema(child)) return true;
        } else if (node.isTextual() && !literal(node.textValue())) return true;
        return false;
    }

    private boolean sameLiteral(Object value, String expected) {
        return value instanceof String s && literal(s) && s.equals(expected);
    }

    private boolean literal(String s) {
        return hasText(s) && s.equals(s.trim()) && !s.contains("${") && !s.contains("#{")
                && !s.contains("{{") && !s.contains("}}") && !s.contains("*");
    }

    private boolean hasText(String s) { return s != null && !s.isBlank(); }

    private String sha256(String value) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}

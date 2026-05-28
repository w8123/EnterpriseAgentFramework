package com.enterprise.ai.agent.runtime;

import com.enterprise.ai.agent.capability.CapabilityAssetService;
import com.enterprise.ai.agent.capability.CompositionDefinitionEntity;
import com.enterprise.ai.agent.capability.InteractionSessionEntity;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CompositionRuntimeExecutor {

    private static final String START = "START";
    private static final String END = "END";
    private static final String LAST_OUTPUT = "lastOutput";
    private static final String LAST_SUCCESS = "lastSuccess";
    private static final String LAST_ERROR = "lastError";
    private static final String ANSWER = "answer";
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*}}");
    private static final int MAX_STEPS = 64;
    private static final int MAX_DEPTH = 4;
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private final CapabilityAssetService assetService;
    private final ToolRuntimeExecutor toolRuntimeExecutor;
    private final InteractionRuntimeExecutor interactionRuntimeExecutor;
    private final ObjectMapper objectMapper;

    public CompositionRuntimeExecutor(CapabilityAssetService assetService,
                                      ToolRuntimeExecutor toolRuntimeExecutor,
                                      InteractionRuntimeExecutor interactionRuntimeExecutor,
                                      ObjectMapper objectMapper) {
        this.assetService = assetService;
        this.toolRuntimeExecutor = toolRuntimeExecutor;
        this.interactionRuntimeExecutor = interactionRuntimeExecutor;
        this.objectMapper = objectMapper;
    }

    public CapabilityRuntimeResult execute(CapabilityRuntimeRequest request) {
        String qualifiedName = request == null ? null : request.qualifiedName();
        int depth = DEPTH.get();
        if (depth >= MAX_DEPTH) {
            return CapabilityRuntimeResult.failure(qualifiedName, "composition recursion depth exceeded", Map.of("depth", depth));
        }
        DEPTH.set(depth + 1);
        try {
            CompositionDefinitionEntity composition = assetService.findCompositionByQualifiedName(qualifiedName)
                    .orElseThrow(() -> new IllegalArgumentException("composition not found: " + qualifiedName));
            if (!Boolean.TRUE.equals(composition.getEnabled())) {
                return CapabilityRuntimeResult.failure(qualifiedName, "composition disabled", Map.of());
            }
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("params", request.params() == null ? Map.of() : request.params());
            state.put("runId", java.util.UUID.randomUUID().toString());
            state.put(LAST_SUCCESS, true);
            state.put(LAST_ERROR, "");
            GraphSpec spec = objectMapper.readValue(composition.getGraphSpecJson(), GraphSpec.class);
            return executeGraph(composition, spec, state, entry(spec), request);
        } catch (InteractionSuspendException ex) {
            return ex.result();
        } catch (Exception ex) {
            return CapabilityRuntimeResult.failure(qualifiedName, ex.getMessage(), Map.of("assetType", "COMPOSITION"));
        } finally {
            DEPTH.set(depth);
        }
    }

    public CapabilityRuntimeResult resumeInteraction(String sessionId,
                                                     Map<String, Object> submittedPayload,
                                                     Map<String, Object> context) {
        try {
            InteractionSessionEntity session = interactionRuntimeExecutor.requireWaitingSession(sessionId);
            interactionRuntimeExecutor.markSubmitted(sessionId, submittedPayload);
            CompositionDefinitionEntity composition = assetService.findCompositionByQualifiedName(session.getCompositionQualifiedName())
                    .orElseThrow(() -> new IllegalArgumentException("composition not found: " + session.getCompositionQualifiedName()));
            GraphSpec spec = objectMapper.readValue(composition.getGraphSpecJson(), GraphSpec.class);
            Map<String, Object> state = new LinkedHashMap<>(interactionRuntimeExecutor.stateFromSession(session));
            state.put("submittedPayload", submittedPayload == null ? Map.of() : submittedPayload);
            CapabilityRuntimeResult result = executeGraph(composition, spec, state, session.getNodeId(),
                    CapabilityRuntimeRequest.builder()
                            .qualifiedName(composition.getQualifiedName())
                            .params(submittedPayload == null ? Map.of() : submittedPayload)
                            .context(context == null ? Map.of() : context)
                            .build());
            if (!"WAITING_USER".equals(result.status())) {
                interactionRuntimeExecutor.markCompleted(sessionId, result.output());
            }
            return result;
        } catch (InteractionSuspendException ex) {
            return ex.result();
        } catch (Exception ex) {
            return CapabilityRuntimeResult.failure(null, ex.getMessage(), Map.of("assetType", "INTERACTION"));
        }
    }

    private CapabilityRuntimeResult executeGraph(CompositionDefinitionEntity composition,
                                                 GraphSpec spec,
                                                 Map<String, Object> state,
                                                 String current,
                                                 CapabilityRuntimeRequest request) {
        List<String> path = new ArrayList<>();
        String lastNodeId = null;
        for (int i = 0; i < MAX_STEPS && current != null && !END.equals(current); i++) {
            String nodeId = current;
            GraphSpec.Node node = node(spec, nodeId)
                    .orElseThrow(() -> new IllegalArgumentException("graph node not found: " + nodeId));
            Map<String, Object> metadata = metadata(composition, path, node.getId());
            Map<String, Object> update = executeNode(node, state, request, metadata);
            state.putAll(update);
            lastNodeId = node.getId();
            path.add(lastNodeId);
            current = next(spec, node.getId(), state);
        }
        Object output = state.containsKey(ANSWER) ? state.get(ANSWER) : state.get(LAST_OUTPUT);
        Map<String, Object> metadata = metadata(composition, path, lastNodeId);
        metadata.put("lastNodeId", lastNodeId);
        return CapabilityRuntimeResult.success(composition.getQualifiedName(), output, metadata);
    }

    private Map<String, Object> executeNode(GraphSpec.Node node,
                                            Map<String, Object> state,
                                            CapabilityRuntimeRequest request,
                                            Map<String, Object> metadata) {
        return switch (normalize(node.getType())) {
            case "USER_INPUT" -> executeUserInput(node, state);
            case "INTERACTION" -> interactionRuntimeExecutor.execute(node, state, request, request.qualifiedName(), metadata);
            case "TEMPLATE" -> executeTemplate(node, state);
            case "ANSWER" -> executeAnswer(node, state);
            case "TOOL" -> executeTool(node, state, request);
            case "COMPOSITION" -> executeNestedComposition(node, state, request);
            case "LLM" -> executeTemplate(node, state);
            default -> throw new IllegalArgumentException("unsupported composition node: " + node.getType());
        };
    }

    private Map<String, Object> executeUserInput(GraphSpec.Node node, Map<String, Object> state) {
        Map<String, Object> config = safeMap(node.getConfig());
        Map<String, Object> incoming = safeMap(state.get("params"));
        Map<String, Object> params = new LinkedHashMap<>();
        Object rawFields = config.get("fields");
        if (rawFields instanceof List<?> fields) {
            for (Object raw : fields) {
                Map<String, Object> field = safeMap(raw);
                String name = asString(field.get("name"));
                if (name.isBlank()) {
                    continue;
                }
                Object value = incoming.get(name);
                if (Boolean.TRUE.equals(field.get("required")) && isBlank(value)) {
                    throw new IllegalArgumentException("required parameter missing: " + name);
                }
                params.put(name, value);
            }
        }
        incoming.forEach(params::putIfAbsent);
        Map<String, Object> update = baseSuccess(params);
        update.put("params", params);
        writeAlias(node, update, params);
        return update;
    }

    private Map<String, Object> executeTemplate(GraphSpec.Node node, Map<String, Object> state) {
        String template = asString(safeMap(node.getConfig()).get("template"));
        String rendered = render(template, state);
        Map<String, Object> update = baseSuccess(rendered);
        writeAlias(node, update, rendered);
        return update;
    }

    private Map<String, Object> executeAnswer(GraphSpec.Node node, Map<String, Object> state) {
        String template = asString(safeMap(node.getConfig()).getOrDefault("template", "{{ lastOutput }}"));
        String rendered = render(template, state);
        Map<String, Object> update = baseSuccess(rendered);
        update.put(ANSWER, rendered);
        writeAlias(node, update, rendered);
        return update;
    }

    private Map<String, Object> executeTool(GraphSpec.Node node,
                                            Map<String, Object> state,
                                            CapabilityRuntimeRequest request) {
        Map<String, Object> config = safeMap(node.getConfig());
        String qualifiedName = firstNonBlank(asString(config.get("qualifiedName")),
                node.getRef() == null ? "" : firstNonBlank(node.getRef().getQualifiedName(), node.getRef().getName()));
        Map<String, Object> args = resolveInputMapping(config.get("inputMapping"), state);
        ToolRuntimeResult result = toolRuntimeExecutor.execute(ToolRuntimeRequest.builder()
                .qualifiedName(qualifiedName)
                .args(args)
                .context(request.context())
                .build());
        if (!result.success()) {
            Map<String, Object> update = new LinkedHashMap<>();
            update.put(LAST_SUCCESS, false);
            update.put(LAST_ERROR, result.errorMessage());
            update.put(LAST_OUTPUT, null);
            return update;
        }
        Map<String, Object> update = baseSuccess(result.output());
        writeAlias(node, update, result.output());
        return update;
    }

    private Map<String, Object> executeNestedComposition(GraphSpec.Node node,
                                                         Map<String, Object> state,
                                                         CapabilityRuntimeRequest request) {
        Map<String, Object> config = safeMap(node.getConfig());
        String qualifiedName = firstNonBlank(asString(config.get("qualifiedName")),
                node.getRef() == null ? "" : firstNonBlank(node.getRef().getQualifiedName(), node.getRef().getName()));
        CapabilityRuntimeResult result = execute(CapabilityRuntimeRequest.builder()
                .qualifiedName(qualifiedName)
                .params(resolveInputMapping(config.get("inputMapping"), state))
                .context(request.context())
                .build());
        if (!result.success()) {
            Map<String, Object> update = new LinkedHashMap<>();
            update.put(LAST_SUCCESS, false);
            update.put(LAST_ERROR, result.errorMessage());
            update.put(LAST_OUTPUT, null);
            return update;
        }
        Map<String, Object> update = baseSuccess(result.output());
        writeAlias(node, update, result.output());
        return update;
    }

    private Map<String, Object> metadata(CompositionDefinitionEntity composition, List<String> path, String nodeId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("path", List.copyOf(path));
        metadata.put("nodeId", nodeId);
        metadata.put("lastNodeId", nodeId);
        metadata.put("capabilityCode", composition.getCapabilityCode());
        metadata.put("assetType", "COMPOSITION");
        metadata.put("qualifiedName", composition.getQualifiedName());
        return metadata;
    }

    private String entry(GraphSpec spec) {
        if (spec.getEntry() != null && !spec.getEntry().isBlank()) {
            return spec.getEntry();
        }
        if (spec.getEdges() != null) {
            for (GraphSpec.Edge edge : spec.getEdges()) {
                if (START.equals(edge.getFrom())) {
                    return edge.getTo();
                }
            }
        }
        return null;
    }

    private Optional<GraphSpec.Node> node(GraphSpec spec, String id) {
        if (spec.getNodes() == null) {
            return Optional.empty();
        }
        return spec.getNodes().stream().filter(n -> Objects.equals(n.getId(), id)).findFirst();
    }

    private String next(GraphSpec spec, String current, Map<String, Object> state) {
        if (spec.getEdges() == null) {
            return null;
        }
        for (GraphSpec.Edge edge : spec.getEdges()) {
            if (Objects.equals(edge.getFrom(), current) && matches(edge.getCondition(), state)) {
                return edge.getTo();
            }
        }
        return null;
    }

    private boolean matches(String condition, Map<String, Object> state) {
        String c = condition == null ? "always" : condition.toLowerCase(Locale.ROOT);
        if (c.isBlank() || "always".equals(c) || "default".equals(c)) {
            return true;
        }
        boolean success = Boolean.TRUE.equals(state.get(LAST_SUCCESS));
        return ("success".equals(c) && success) || (("error".equals(c) || "failure".equals(c)) && !success);
    }

    private Map<String, Object> resolveInputMapping(Object raw, Map<String, Object> state) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> mapping) {
            mapping.forEach((key, expression) -> args.put(String.valueOf(key), resolveExpression(String.valueOf(expression), state)));
        }
        return args;
    }

    private Object resolveExpression(String expression, Map<String, Object> state) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        Object current = state;
        for (String part : expression.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    private String render(String template, Map<String, Object> state) {
        Matcher matcher = TEMPLATE_VAR.matcher(template == null ? "" : template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            Object value = resolveExpression(matcher.group(1).trim(), state);
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Map<String, Object> baseSuccess(Object output) {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(LAST_OUTPUT, output);
        update.put(LAST_SUCCESS, true);
        update.put(LAST_ERROR, "");
        return update;
    }

    private static void writeAlias(GraphSpec.Node node, Map<String, Object> update, Object value) {
        String alias = asString(safeMap(node.getConfig()).get("outputAlias"));
        if (!alias.isBlank()) {
            update.put(alias, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> safeMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }
}

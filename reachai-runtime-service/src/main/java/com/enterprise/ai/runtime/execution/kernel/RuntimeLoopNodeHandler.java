package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.context.WorkflowOutputAliasWriter;
import com.enterprise.ai.runtime.execution.context.WorkflowVariableNamespaces;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Executes LOOP configuration and iteration while delegating nested graph driving to the kernel. */
final class RuntimeLoopNodeHandler {

    static final int DEFAULT_MAX_ITERATIONS = 100;
    static final int HARD_MAX_ITERATIONS = 1000;

    private final RuntimeNodeValueResolver valueResolver;
    private final LoopBodyExecutor bodyExecutor;

    RuntimeLoopNodeHandler(RuntimeNodeValueResolver valueResolver, LoopBodyExecutor bodyExecutor) {
        this.valueResolver = valueResolver;
        this.bodyExecutor = bodyExecutor;
    }

    RuntimeGraphSpecExecutionResult executeLoop(GraphSpec.Node node, RuntimeNodeExecutionContext execution) {
        Map<String, Object> context = execution.variables();
        GraphSpec graph = execution.graph();
        RuntimeGraphSpecExecutionEventSink eventSink = execution.eventSink();
        RuntimeGraphSpecExecutionCancellation cancel = execution.cancellation();
        Map<String, GraphSpec.Node> nodesById = new LinkedHashMap<>();
        if (graph != null && graph.getNodes() != null) {
            for (GraphSpec.Node graphNode : graph.getNodes()) {
                if (graphNode != null && StringUtils.hasText(graphNode.getId())) {
                    nodesById.put(graphNode.getId().trim(), graphNode);
                }
            }
        }
        Map<String, Object> config = loopConfigOf(node);
        String mode = firstText(text(config.get("mode")), "FOREACH");
        if (!"FOREACH".equalsIgnoreCase(mode)) {
            return failure("RUNTIME_GRAPH_LOOP_MODE_UNSUPPORTED",
                    "LOOP v1 only supports FOREACH mode", node.getId(), "LOOP");
        }
        String collectionExpr = firstText(text(config.get("collection")), text(config.get("itemExpression")));
        String itemAlias = firstText(text(config.get("itemAlias")), "item");
        String indexAlias = firstText(text(config.get("indexAlias")), "index");
        String outputAlias = firstText(
                text(config.get("outputAlias")), text(config.get("loopKey")), "loop_results");
        String bodyOutputExpr = firstText(text(config.get("bodyOutput")), "lastOutput");
        String bodyEntry = text(config.get("bodyEntry"));
        String bodyExit = firstText(text(config.get("bodyExit")), bodyEntry);
        Integer maxIterations = parseMaxIterations(config.get("maxIterations"));
        if (maxIterations == null) {
            return failure("RUNTIME_GRAPH_LOOP_MAX_ITERATIONS_INVALID",
                    "LOOP maxIterations must be between 1 and " + HARD_MAX_ITERATIONS
                            + " (missing defaults to " + DEFAULT_MAX_ITERATIONS + ")",
                    node.getId(), "LOOP");
        }
        Set<String> bodyNodeIds = loopBodyNodeIds(config, bodyEntry, bodyExit);
        if (!StringUtils.hasText(collectionExpr)) {
            return failure("RUNTIME_GRAPH_LOOP_COLLECTION_REQUIRED",
                    "LOOP requires collection expression", node.getId(), "LOOP");
        }
        if (!WorkflowVariableNamespaces.isValidAlias(itemAlias)
                || WorkflowVariableNamespaces.isReservedAlias(itemAlias)
                || !WorkflowVariableNamespaces.isValidAlias(indexAlias)
                || WorkflowVariableNamespaces.isReservedAlias(indexAlias)
                || !WorkflowVariableNamespaces.isValidAlias(outputAlias)
                || WorkflowVariableNamespaces.isReservedAlias(outputAlias)) {
            return failure("RUNTIME_GRAPH_LOOP_ALIAS_INVALID",
                    "LOOP itemAlias/indexAlias/outputAlias must be valid non-reserved aliases",
                    node.getId(), "LOOP");
        }
        if (!StringUtils.hasText(bodyEntry) || !nodesById.containsKey(bodyEntry)
                || !StringUtils.hasText(bodyExit) || !nodesById.containsKey(bodyExit)
                || bodyNodeIds.isEmpty() || !bodyNodeIds.contains(bodyEntry) || !bodyNodeIds.contains(bodyExit)) {
            return failure("RUNTIME_GRAPH_LOOP_BODY_INVALID",
                    "LOOP requires bodyEntry/bodyExit inside bodyNodeIds", node.getId(), "LOOP");
        }
        for (String bodyId : bodyNodeIds) {
            GraphSpec.Node bodyNode = nodesById.get(bodyId);
            if (bodyNode == null) {
                return failure("RUNTIME_GRAPH_LOOP_BODY_INVALID",
                        "LOOP body node missing: " + bodyId, node.getId(), "LOOP");
            }
            String bodyType = AgentGraphNodeType.normalize(bodyNode.getType());
            if ("LOOP".equals(bodyType)) {
                return failure("RUNTIME_GRAPH_LOOP_NESTED",
                        "LOOP nesting is not supported in v1", node.getId(), "LOOP");
            }
            if ("INTERACTION".equals(bodyType) || "HUMAN_APPROVAL".equals(bodyType)) {
                return failure("RUNTIME_GRAPH_LOOP_BODY_FORBIDDEN",
                        "LOOP body cannot include " + bodyType, node.getId(), "LOOP");
            }
        }
        Object collectionValue = valueResolver.resolveContextValue(collectionExpr, context);
        if (collectionValue == null) {
            return failure("RUNTIME_GRAPH_LOOP_COLLECTION_NULL",
                    "LOOP collection resolved to null", node.getId(), "LOOP");
        }
        List<?> items;
        if (collectionValue instanceof List<?> list) {
            items = list;
        } else if (collectionValue.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(collectionValue);
            List<Object> converted = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                converted.add(java.lang.reflect.Array.get(collectionValue, index));
            }
            items = converted;
        } else {
            return failure("RUNTIME_GRAPH_LOOP_COLLECTION_TYPE",
                    "LOOP collection must resolve to an array/list", node.getId(), "LOOP");
        }
        if (items.size() > maxIterations) {
            return failure("RUNTIME_GRAPH_LOOP_MAX_ITERATIONS",
                    "LOOP collection size " + items.size() + " exceeds maxIterations " + maxIterations,
                    node.getId(), "LOOP");
        }

        List<Object> collected = new ArrayList<>();
        List<Map<String, Object>> iterationSummaries = new ArrayList<>();
        List<Map<String, Object>> bodySteps = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), "LOOP");
            }
            Map<String, Object> iterationContext = copyExecutionContext(context);
            WorkflowOutputAliasWriter.writeAlias(iterationContext, itemAlias, items.get(index));
            WorkflowOutputAliasWriter.writeAlias(iterationContext, indexAlias, index);
            RuntimeGraphSpecExecutionResult bodyResult = bodyExecutor.execute(
                    graph, nodesById, bodyNodeIds, bodyEntry, bodyExit,
                    iterationContext, eventSink, cancel);
            bodySteps.addAll(bodyResult.steps());
            Map<String, Object> iterationSummary = new LinkedHashMap<>();
            iterationSummary.put("index", index);
            iterationSummary.put("status", bodyResult.success() ? "SUCCESS" : bodyResult.code());
            iterationSummaries.add(iterationSummary);
            if (cancel.isCancelled() || "RUNTIME_GRAPH_CANCELLED".equals(bodyResult.code())) {
                return cancelled(node.getId(), "LOOP");
            }
            if (!bodyResult.success()) {
                Map<String, Object> metadata = nodeMetadata(node, "LOOP");
                metadata.put("traceSummary",
                        loopTraceSummary(items.size(), maxIterations, index, iterationSummaries));
                metadata.put("failureIterationIndex", index);
                return new RuntimeGraphSpecExecutionResult(
                        false,
                        firstText(bodyResult.code(), "RUNTIME_GRAPH_LOOP_BODY_FAILED"),
                        firstText(bodyResult.answer(), "LOOP body failed at iteration " + index),
                        node.getId(), "LOOP", bodySteps, metadata);
            }
            Object piece = valueResolver.resolveContextValue(bodyOutputExpr, iterationContext);
            if (piece == null && bodyResult.metadata() != null) {
                piece = bodyResult.metadata().get("structuredOutput");
            }
            if (piece == null) {
                piece = bodyResult.answer();
            }
            collected.add(piece);
        }
        WorkflowOutputAliasWriter.writeAlias(context, outputAlias, collected);
        Map<String, Object> metadata = nodeMetadata(node, "LOOP");
        metadata.put("structuredOutput", collected);
        metadata.put("traceSummary",
                loopTraceSummary(items.size(), maxIterations, collected.size(), iterationSummaries));
        return new RuntimeGraphSpecExecutionResult(
                true, "RUNTIME_GRAPH_EXECUTED", String.valueOf(collected.size()),
                node.getId(), "LOOP",
                bodySteps.isEmpty() ? List.of(step("execute-node", node.getId())) : bodySteps,
                metadata);
    }

    static Integer parseMaxIterations(Object raw) {
        if (raw == null) return DEFAULT_MAX_ITERATIONS;
        if (raw instanceof String text && !StringUtils.hasText(text)) return DEFAULT_MAX_ITERATIONS;
        int value;
        if (raw instanceof Number number) {
            if (raw instanceof Double || raw instanceof Float) {
                double decimal = number.doubleValue();
                if (!Double.isFinite(decimal) || Math.floor(decimal) != decimal) return null;
            }
            value = number.intValue();
        } else {
            try {
                value = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return value < 1 || value > HARD_MAX_ITERATIONS ? null : value;
    }

    private Map<String, Object> copyExecutionContext(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source == null) return copy;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> nested = new LinkedHashMap<>();
                map.forEach((key, item) -> nested.put(String.valueOf(key), item));
                copy.put(entry.getKey(), nested);
            } else if (value instanceof List<?> list) {
                copy.put(entry.getKey(), new ArrayList<>(list));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
    }

    private Map<String, Object> loopConfigOf(GraphSpec.Node node) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        Map<String, Object> nested = mapValue(config.get("loopConfig"));
        if (nested == null || nested.isEmpty()) return config;
        Map<String, Object> merged = new LinkedHashMap<>(nested);
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            if (!"loopConfig".equals(entry.getKey())) {
                merged.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    private static Set<String> loopBodyNodeIds(Map<String, Object> config,
                                               String bodyEntry,
                                               String bodyExit) {
        Set<String> ids = new LinkedHashSet<>();
        Object raw = config.get("bodyNodeIds");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && StringUtils.hasText(String.valueOf(item))) {
                    ids.add(String.valueOf(item).trim());
                }
            }
        }
        if (StringUtils.hasText(bodyEntry)) ids.add(bodyEntry.trim());
        if (StringUtils.hasText(bodyExit)) ids.add(bodyExit.trim());
        return ids;
    }

    private static Map<String, Object> loopTraceSummary(int collectionSize,
                                                        int maxIterations,
                                                        int completedOrFailedIndex,
                                                        List<Map<String, Object>> iterations) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("collectionSize", collectionSize);
        summary.put("configuredMaxIterations", maxIterations);
        summary.put("completedIterations", Math.min(completedOrFailedIndex, collectionSize));
        summary.put("iterationCount", iterations == null ? 0 : iterations.size());
        // Never include item values; Trace receives index and status only.
        summary.put("iterations", iterations == null ? List.of() : iterations);
        return summary;
    }

    private RuntimeGraphSpecExecutionResult cancelled(String nodeId, String nodeType) {
        return failure("RUNTIME_GRAPH_CANCELLED", "Workflow execution cancelled", nodeId, nodeType);
    }

    private RuntimeGraphSpecExecutionResult failure(String code, String answer, String nodeId, String nodeType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (StringUtils.hasText(nodeId)) metadata.put("nodeId", nodeId);
        if (StringUtils.hasText(nodeType)) metadata.put("nodeType", nodeType);
        return new RuntimeGraphSpecExecutionResult(false, code, answer, nodeId, nodeType, List.of(), metadata);
    }

    private Map<String, Object> nodeMetadata(GraphSpec.Node node, String nodeType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeId", node.getId());
        metadata.put("nodeType", nodeType);
        return metadata;
    }

    private Map<String, Object> step(String name, String detail) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("detail", detail);
        return step;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }

    @FunctionalInterface
    interface LoopBodyExecutor {
        RuntimeGraphSpecExecutionResult execute(GraphSpec graph,
                                                Map<String, GraphSpec.Node> nodesById,
                                                Set<String> bodyNodeIds,
                                                String bodyEntry,
                                                String bodyExit,
                                                Map<String, Object> context,
                                                RuntimeGraphSpecExecutionEventSink eventSink,
                                                RuntimeGraphSpecExecutionCancellation cancellation);
    }
}

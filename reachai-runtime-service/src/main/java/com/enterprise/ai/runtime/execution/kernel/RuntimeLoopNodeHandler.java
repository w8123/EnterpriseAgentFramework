package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.graph.GraphSpecLoopConfig;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.context.WorkflowOutputAliasWriter;
import com.enterprise.ai.runtime.execution.context.WorkflowVariableNamespaces;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executes LOOP configuration and iteration while delegating nested graph driving to the kernel. */
final class RuntimeLoopNodeHandler {

    /** Body node types that pause, need a page, or fork again; a loop round must run to completion on its own. */
    static final Set<String> FORBIDDEN_BODY_TYPES = Set.of("INTERACTION", "HUMAN_APPROVAL", "PARALLEL");

    private final RuntimeNodeValueResolver valueResolver;
    private final LoopBodyExecutor bodyExecutor;
    private final RuntimeConcurrentNodeRunner runner;
    private final RuntimeDeterministicNodeHandlers conditions;

    RuntimeLoopNodeHandler(RuntimeNodeValueResolver valueResolver, LoopBodyExecutor bodyExecutor) {
        this(valueResolver, bodyExecutor, new RuntimeConcurrentNodeRunner(List.of()), null);
    }

    RuntimeLoopNodeHandler(RuntimeNodeValueResolver valueResolver,
                           LoopBodyExecutor bodyExecutor,
                           RuntimeConcurrentNodeRunner runner,
                           RuntimeDeterministicNodeHandlers conditions) {
        this.valueResolver = valueResolver;
        this.bodyExecutor = bodyExecutor;
        this.runner = runner;
        this.conditions = conditions;
    }

    RuntimeGraphSpecExecutionResult executeLoop(GraphSpec.Node node, RuntimeNodeExecutionContext execution) {
        GraphSpec graph = execution.graph();
        Map<String, GraphSpec.Node> nodesById = new LinkedHashMap<>();
        if (graph != null && graph.getNodes() != null) {
            for (GraphSpec.Node graphNode : graph.getNodes()) {
                if (graphNode != null && StringUtils.hasText(graphNode.getId())) {
                    nodesById.put(graphNode.getId().trim(), graphNode);
                }
            }
        }
        GraphSpecLoopConfig loop = GraphSpecLoopConfig.from(node);
        if (!loop.foreachMode() && !loop.whileMode()) {
            return failure("RUNTIME_GRAPH_LOOP_MODE_UNSUPPORTED",
                    "LOOP supports FOREACH and WHILE modes", node.getId(), "LOOP");
        }
        String itemAlias = loop.itemAlias();
        String indexAlias = loop.indexAlias();
        String outputAlias = loop.outputAlias();
        String bodyEntry = loop.bodyEntry();
        String bodyExit = loop.bodyExit();
        Integer maxIterations = loop.maxIterations();
        if (maxIterations == null) {
            return failure("RUNTIME_GRAPH_LOOP_MAX_ITERATIONS_INVALID",
                    "LOOP maxIterations must be between 1 and " + GraphSpecLoopConfig.HARD_MAX_ITERATIONS
                            + " (missing defaults to " + GraphSpecLoopConfig.DEFAULT_MAX_ITERATIONS + ")",
                    node.getId(), "LOOP");
        }
        Set<String> bodyNodeIds = loop.bodyNodeIds();
        if (loop.foreachMode() && !StringUtils.hasText(loop.collection())) {
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
        if (itemAlias.equals(indexAlias) || itemAlias.equals(outputAlias) || indexAlias.equals(outputAlias)) {
            return failure("RUNTIME_GRAPH_LOOP_ALIAS_CONFLICT",
                    "LOOP itemAlias/indexAlias/outputAlias must be distinct", node.getId(), "LOOP");
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
            if (FORBIDDEN_BODY_TYPES.contains(bodyType)) {
                return failure("RUNTIME_GRAPH_LOOP_BODY_FORBIDDEN",
                        "LOOP body cannot include " + bodyType, node.getId(), "LOOP");
            }
        }
        if (!GraphSpecLoopConfig.ERROR_MODES.contains(loop.errorMode())) {
            return failure("RUNTIME_GRAPH_LOOP_ERROR_MODE_INVALID",
                    "LOOP errorMode must be TERMINATE, SKIP or KEEP", node.getId(), "LOOP");
        }
        LoopShape shape = new LoopShape(graph, nodesById, bodyNodeIds, bodyEntry, bodyExit);
        return loop.whileMode()
                ? executeWhile(node, loop, shape, execution)
                : executeForeach(node, loop, shape, execution);
    }

    private record LoopShape(GraphSpec graph, Map<String, GraphSpec.Node> nodesById, Set<String> bodyNodeIds,
                             String bodyEntry, String bodyExit) {
    }

    private record IterationOutcome(int index, RuntimeGraphSpecExecutionResult result, Object piece, boolean skipped) {
    }

    private RuntimeGraphSpecExecutionResult executeForeach(GraphSpec.Node node,
                                                           GraphSpecLoopConfig loop,
                                                           LoopShape shape,
                                                           RuntimeNodeExecutionContext execution) {
        Map<String, Object> context = execution.variables();
        RuntimeGraphSpecExecutionCancellation cancel = execution.cancellation();
        Integer parallelism = loop.parallelism();
        if (parallelism == null) {
            return failure("RUNTIME_GRAPH_LOOP_PARALLELISM_INVALID",
                    "LOOP parallelism must be between 1 and " + GraphSpecLoopConfig.MAX_PARALLELISM,
                    node.getId(), "LOOP");
        }
        Object collectionValue = valueResolver.resolveContextValue(loop.collection(), context);
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
        int maxIterations = loop.maxIterations();
        if (items.size() > maxIterations) {
            return failure("RUNTIME_GRAPH_LOOP_MAX_ITERATIONS",
                    "LOOP collection size " + items.size() + " exceeds maxIterations " + maxIterations,
                    node.getId(), "LOOP");
        }
        boolean terminate = GraphSpecLoopConfig.ERROR_TERMINATE.equals(loop.errorMode());
        AtomicBoolean stop = new AtomicBoolean(false);
        // Body nodes do not emit their own node events today; serialise any sink use from parallel iterations.
        Object sinkLock = new Object();
        List<Callable<IterationOutcome>> tasks = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            final int iteration = index;
            final Object item = items.get(index);
            tasks.add(() -> {
                if (stop.get() || cancel.isCancelled()) {
                    return new IterationOutcome(iteration, null, null, true);
                }
                Map<String, Object> iterationContext = RuntimeContextCopies.copy(context);
                iterationContext.put(RuntimeInvocationScope.KEY,
                        RuntimeInvocationScope.from(context).child("loop:" + node.getId() + ":" + iteration));
                WorkflowOutputAliasWriter.writeAlias(iterationContext, loop.itemAlias(), item);
                WorkflowOutputAliasWriter.writeAlias(iterationContext, loop.indexAlias(), iteration);
                RuntimeConcurrentNodeRunner.GuardedEventSink sink = new RuntimeConcurrentNodeRunner.GuardedEventSink(
                        execution.eventSink(), Map.of("loopNodeId", node.getId(), "iterationIndex", iteration), sinkLock);
                RuntimeGraphSpecExecutionResult bodyResult = bodyExecutor.execute(
                        shape.graph(), shape.nodesById(), shape.bodyNodeIds(), shape.bodyEntry(), shape.bodyExit(),
                        iterationContext, sink, cancel);
                if (!bodyResult.success() && (terminate || unknownWrite(bodyResult))) stop.set(true);
                Object piece = null;
                if (bodyResult.success()) {
                    piece = valueResolver.resolveContextValue(loop.bodyOutput(), iterationContext);
                    if (piece == null && bodyResult.metadata() != null) {
                        piece = bodyResult.metadata().get("structuredOutput");
                    }
                    if (piece == null) piece = bodyResult.answer();
                }
                return new IterationOutcome(iteration, bodyResult, piece, false);
            });
        }
        List<IterationOutcome> outcomes;
        try {
            outcomes = runner.runAll(tasks, parallelism);
        } catch (IllegalStateException ex) {
            if ("RUNTIME_GRAPH_CANCELLED".equals(ex.getMessage()) || cancel.isCancelled()) {
                return cancelled(node.getId(), "LOOP");
            }
            throw ex;
        }

        // An earlier ordinary failure must not hide a later in-flight write of unknown outcome.
        for (IterationOutcome outcome : outcomes) {
            if (!outcome.skipped() && unknownWrite(outcome.result())) {
                RuntimeGraphSpecExecutionResult result = outcome.result();
                Map<String, Object> metadata = nodeMetadata(node, "LOOP");
                copyUnknownWrite(result, metadata);
                metadata.put("failureIterationIndex", outcome.index());
                return new RuntimeGraphSpecExecutionResult(false, result.code(), result.answer(), node.getId(),
                        "LOOP", result.steps(), metadata);
            }
        }

        List<Object> collected = new ArrayList<>();
        List<Integer> failedIndexes = new ArrayList<>();
        List<Map<String, Object>> iterationSummaries = new ArrayList<>();
        List<Map<String, Object>> bodySteps = new ArrayList<>();
        for (IterationOutcome outcome : outcomes) {
            if (outcome.skipped()) {
                if (cancel.isCancelled()) return cancelled(node.getId(), "LOOP");
                continue;
            }
            RuntimeGraphSpecExecutionResult bodyResult = outcome.result();
            bodySteps.addAll(bodyResult.steps());
            Map<String, Object> iterationSummary = new LinkedHashMap<>();
            iterationSummary.put("index", outcome.index());
            iterationSummary.put("status", bodyResult.success() ? "SUCCESS" : bodyResult.code());
            iterationSummaries.add(iterationSummary);
            if (cancel.isCancelled() || "RUNTIME_GRAPH_CANCELLED".equals(bodyResult.code())) {
                return cancelled(node.getId(), "LOOP");
            }
            if (!bodyResult.success()) {
                failedIndexes.add(outcome.index());
                if (terminate || unknownWrite(bodyResult)) {
                    Map<String, Object> metadata = nodeMetadata(node, "LOOP");
                    copyUnknownWrite(bodyResult, metadata);
                    metadata.put("traceSummary", loopTraceSummary(items.size(), maxIterations,
                            outcome.index(), iterationSummaries, loop, failedIndexes));
                    metadata.put("failureIterationIndex", outcome.index());
                    return new RuntimeGraphSpecExecutionResult(
                            false,
                            firstText(bodyResult.code(), "RUNTIME_GRAPH_LOOP_BODY_FAILED"),
                            firstText(bodyResult.answer(), "LOOP body failed at iteration " + outcome.index()),
                            node.getId(), "LOOP", bodySteps, metadata);
                }
                if (GraphSpecLoopConfig.ERROR_KEEP.equals(loop.errorMode())) {
                    Map<String, Object> placeholder = new LinkedHashMap<>();
                    placeholder.put("index", outcome.index());
                    placeholder.put("error", firstText(bodyResult.code(), "RUNTIME_GRAPH_LOOP_BODY_FAILED"));
                    placeholder.put("message", RuntimeNodeFailureReasons.safeReason(bodyResult.code(), bodyResult.answer()));
                    collected.add(placeholder);
                }
                continue;
            }
            collected.add(outcome.piece());
        }
        WorkflowOutputAliasWriter.writeAlias(context, loop.outputAlias(), collected);
        Map<String, Object> metadata = nodeMetadata(node, "LOOP");
        metadata.put("structuredOutput", collected);
        metadata.put("traceSummary", loopTraceSummary(items.size(), maxIterations,
                items.size() - failedIndexes.size(), iterationSummaries, loop, failedIndexes));
        return new RuntimeGraphSpecExecutionResult(
                true, "RUNTIME_GRAPH_EXECUTED", String.valueOf(collected.size()),
                node.getId(), "LOOP",
                bodySteps.isEmpty() ? List.of(step("execute-node", node.getId())) : bodySteps,
                metadata);
    }

    /**
     * WHILE: the body runs on one shared loop scope, loop variables carry across rounds, and the break
     * conditions are checked after every round (do-while). The round limit is capped tighter than FOREACH.
     */
    private RuntimeGraphSpecExecutionResult executeWhile(GraphSpec.Node node,
                                                         GraphSpecLoopConfig loop,
                                                         LoopShape shape,
                                                         RuntimeNodeExecutionContext execution) {
        Map<String, Object> context = execution.variables();
        RuntimeGraphSpecExecutionCancellation cancel = execution.cancellation();
        if (loop.breakConditions().isEmpty() || conditions == null) {
            return failure("RUNTIME_GRAPH_LOOP_BREAK_CONDITION_REQUIRED",
                    "LOOP WHILE requires at least one break condition", node.getId(), "LOOP");
        }
        int maxRounds = Math.min(loop.maxIterations(), GraphSpecLoopConfig.WHILE_HARD_MAX_ITERATIONS);
        Map<String, Object> loopContext = RuntimeContextCopies.copy(context);
        Map<String, String> variablePaths = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Object> variable : loop.loopVariables().entrySet()) {
                String path = WorkflowVariableNamespaces.normalizeBusinessWriteTarget(variable.getKey());
                variablePaths.put(variable.getKey(), path);
                WorkflowOutputAliasWriter.writeBusinessPath(loopContext, path,
                        valueResolver.renderInputValue(variable.getValue(), loopContext));
            }
        } catch (IllegalArgumentException ex) {
            return failure("RUNTIME_GRAPH_LOOP_VARIABLE_INVALID",
                    "LOOP variable is invalid: " + ex.getMessage(), node.getId(), "LOOP");
        }
        List<Object> collected = new ArrayList<>();
        List<Map<String, Object>> iterationSummaries = new ArrayList<>();
        List<Map<String, Object>> bodySteps = new ArrayList<>();
        String stoppedBy = "MAX_ITERATIONS";
        for (int round = 0; round < maxRounds; round++) {
            if (cancel.isCancelled()) {
                return cancelled(node.getId(), "LOOP");
            }
            WorkflowOutputAliasWriter.writeAlias(loopContext, loop.indexAlias(), round);
            loopContext.put(RuntimeInvocationScope.KEY,
                    RuntimeInvocationScope.from(context).child("loop:" + node.getId() + ":" + round));
            RuntimeGraphSpecExecutionResult bodyResult = bodyExecutor.execute(
                    shape.graph(), shape.nodesById(), shape.bodyNodeIds(), shape.bodyEntry(), shape.bodyExit(),
                    loopContext, execution.eventSink(), cancel);
            bodySteps.addAll(bodyResult.steps());
            Map<String, Object> iterationSummary = new LinkedHashMap<>();
            iterationSummary.put("index", round);
            iterationSummary.put("status", bodyResult.success() ? "SUCCESS" : bodyResult.code());
            iterationSummaries.add(iterationSummary);
            if (cancel.isCancelled() || "RUNTIME_GRAPH_CANCELLED".equals(bodyResult.code())) {
                return cancelled(node.getId(), "LOOP");
            }
            if (!bodyResult.success()) {
                Map<String, Object> metadata = nodeMetadata(node, "LOOP");
                copyUnknownWrite(bodyResult, metadata);
                metadata.put("traceSummary", loopTraceSummary(maxRounds, maxRounds, round, iterationSummaries,
                        loop, List.of(round)));
                metadata.put("failureIterationIndex", round);
                return new RuntimeGraphSpecExecutionResult(
                        false,
                        firstText(bodyResult.code(), "RUNTIME_GRAPH_LOOP_BODY_FAILED"),
                        firstText(bodyResult.answer(), "LOOP body failed at round " + round),
                        node.getId(), "LOOP", bodySteps, metadata);
            }
            Object piece = valueResolver.resolveContextValue(loop.bodyOutput(), loopContext);
            if (piece == null && bodyResult.metadata() != null) piece = bodyResult.metadata().get("structuredOutput");
            if (piece == null) piece = bodyResult.answer();
            collected.add(piece);
            if (conditions.evaluateConditionGroup(loop.breakConditions(), loop.breakLogic(), loopContext)) {
                stoppedBy = "CONDITION";
                break;
            }
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        variablePaths.forEach((name, path) -> variables.put(name,
                valueResolver.resolveContextValue(path, loopContext)));
        // Loop variables and the last round's node outputs become visible downstream.
        RuntimeParallelNodeHandler.merge(context, loopContext);
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("iterations", collected.size());
        structured.put("outputs", collected);
        structured.put("variables", variables);
        structured.put("stoppedBy", stoppedBy);
        WorkflowOutputAliasWriter.writeAlias(context, loop.outputAlias(), structured);
        Map<String, Object> metadata = nodeMetadata(node, "LOOP");
        metadata.put("structuredOutput", structured);
        Map<String, Object> summary = loopTraceSummary(maxRounds, maxRounds, collected.size(), iterationSummaries,
                loop, List.of());
        summary.put("stoppedBy", stoppedBy);
        metadata.put("traceSummary", summary);
        return new RuntimeGraphSpecExecutionResult(
                true, "RUNTIME_GRAPH_EXECUTED", String.valueOf(collected.size()),
                node.getId(), "LOOP",
                bodySteps.isEmpty() ? List.of(step("execute-node", node.getId())) : bodySteps,
                metadata);
    }

    private static Map<String, Object> loopTraceSummary(int collectionSize,
                                                        int maxIterations,
                                                        int completedOrFailedIndex,
                                                        List<Map<String, Object>> iterations,
                                                        GraphSpecLoopConfig loop,
                                                        List<Integer> failedIndexes) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mode", loop.whileMode() ? GraphSpecLoopConfig.MODE_WHILE : GraphSpecLoopConfig.MODE_FOREACH);
        summary.put("parallelism", loop.parallelism() == null ? 1 : loop.parallelism());
        summary.put("errorMode", loop.errorMode());
        summary.put("failedIndexes", failedIndexes == null ? List.of() : List.copyOf(failedIndexes));
        summary.put("collectionSize", collectionSize);
        summary.put("configuredMaxIterations", maxIterations);
        summary.put("completedIterations", Math.min(completedOrFailedIndex, collectionSize));
        summary.put("iterationCount", iterations == null ? 0 : iterations.size());
        // Never include item values; Trace receives index and status only.
        summary.put("iterations", iterations == null ? List.of() : iterations);
        return summary;
    }

    private static boolean unknownWrite(RuntimeGraphSpecExecutionResult result) {
        return result.metadata() != null && Boolean.TRUE.equals(result.metadata().get("nonRecoverableWrite"));
    }

    private static void copyUnknownWrite(RuntimeGraphSpecExecutionResult result, Map<String, Object> metadata) {
        if (unknownWrite(result)) {
            metadata.put("nonRecoverableWrite", true);
            metadata.put("dispatchStage", "UNKNOWN");
        }
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

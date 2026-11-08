package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.graph.GraphSpecParallelPlan;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Executes a PARALLEL fork: every branch runs on its own copy of the variables (concurrently, bounded), then the
 * values each branch produced are merged back in edge order and execution continues at the join node.
 */
final class RuntimeParallelNodeHandler {

    /** Node types that cannot run inside a branch: they pause, answer, need a page, or fork again. */
    static final Set<String> FORBIDDEN_BRANCH_TYPES = Set.of(
            "PARALLEL", "ANSWER", "INTERACTION", "HUMAN_APPROVAL", "PAGE_ACTION", "USER_INPUT");
    /** Positional or per-step variables owned by the main path; branches never overwrite them on merge. */
    private static final Set<String> PATH_LOCAL_KEYS = Set.of(
            "lastOutput", "previousOutput", "route", "lastRoute", "input", "userInput", "message");

    private final BranchExecutor branchExecutor;
    private final RuntimeConcurrentNodeRunner runner;

    RuntimeParallelNodeHandler(BranchExecutor branchExecutor, RuntimeConcurrentNodeRunner runner) {
        this.branchExecutor = branchExecutor;
        this.runner = runner;
    }

    RuntimeGraphSpecExecutionResult executeParallel(GraphSpec.Node node, RuntimeNodeExecutionContext execution) {
        GraphSpec graph = execution.graph();
        Map<String, GraphSpec.Node> nodesById = new LinkedHashMap<>();
        if (graph != null && graph.getNodes() != null) {
            for (GraphSpec.Node graphNode : graph.getNodes()) {
                if (graphNode != null && StringUtils.hasText(graphNode.getId())) {
                    nodesById.put(graphNode.getId().trim(), graphNode);
                }
            }
        }
        GraphSpecParallelPlan plan = GraphSpecParallelPlan.from(node, nodesById,
                graph == null || graph.getEdges() == null ? List.of() : graph.getEdges());
        if (!plan.valid()) {
            GraphSpecParallelPlan.Problem problem = plan.problems().get(0);
            return failure("RUNTIME_" + problem.code(), problem.message(), node.getId());
        }
        Set<String> exits = graph.getExitNodeIds() == null ? Set.of() : Set.copyOf(graph.getExitNodeIds());
        for (String member : plan.allBranchNodeIds()) {
            GraphSpec.Node branchNode = nodesById.get(member);
            String type = branchNode == null ? "" : AgentGraphNodeType.normalize(branchNode.getType());
            if (FORBIDDEN_BRANCH_TYPES.contains(type) || exits.contains(member)) {
                return failure("RUNTIME_GRAPH_PARALLEL_BRANCH_FORBIDDEN",
                        "PARALLEL branch cannot contain " + (exits.contains(member) ? "an exit node" : type)
                                + ": " + member, node.getId());
            }
        }

        Map<String, Object> context = execution.variables();
        Map<String, Object> baseline = RuntimeContextCopies.copy(context);
        Object sinkLock = new Object();
        List<Map<String, Object>> branchContexts = new ArrayList<>();
        List<RuntimeConcurrentNodeRunner.GuardedEventSink> sinks = new ArrayList<>();
        List<Callable<RuntimeGraphSpecExecutionResult>> tasks = new ArrayList<>();
        for (int index = 0; index < plan.branchEntries().size(); index++) {
            Map<String, Object> branchContext = RuntimeContextCopies.copy(context);
            branchContext.put(RuntimeInvocationScope.KEY,
                    RuntimeInvocationScope.from(context).child("parallel:" + node.getId() + ":" + index));
            branchContexts.add(branchContext);
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("parallelNodeId", node.getId());
            marker.put("branchIndex", index);
            RuntimeConcurrentNodeRunner.GuardedEventSink sink =
                    new RuntimeConcurrentNodeRunner.GuardedEventSink(execution.eventSink(), marker, sinkLock);
            sinks.add(sink);
            String entry = plan.branchEntries().get(index);
            Set<String> members = plan.branchNodeIds().get(index);
            tasks.add(() -> branchExecutor.execute(graph, nodesById, members, entry, plan.joinNodeId(),
                    branchContext, sink, execution.cancellation()));
        }
        List<RuntimeGraphSpecExecutionResult> results;
        try {
            results = runner.runAll(tasks, plan.maxConcurrency());
        } catch (IllegalStateException ex) {
            if ("RUNTIME_GRAPH_CANCELLED".equals(ex.getMessage()) || execution.cancellation().isCancelled()) {
                return failure("RUNTIME_GRAPH_CANCELLED", "Workflow execution cancelled", node.getId());
            }
            throw ex;
        } finally {
            sinks.forEach(RuntimeConcurrentNodeRunner.GuardedEventSink::close);
        }

        List<Map<String, Object>> branchSummaries = new ArrayList<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Object> branchTraces = new ArrayList<>();
        RuntimeGraphSpecExecutionResult firstFailure = null;
        int failureIndex = -1;
        int succeeded = 0;
        for (int index = 0; index < results.size(); index++) {
            RuntimeGraphSpecExecutionResult result = results.get(index);
            if (result.steps() != null) steps.addAll(result.steps());
            if (result.metadata() != null && result.metadata().get("workflowNodeTraces") instanceof List<?> traces) {
                branchTraces.addAll(traces);
            }
            if ("RUNTIME_GRAPH_CANCELLED".equals(result.code()) || execution.cancellation().isCancelled()) {
                return failure("RUNTIME_GRAPH_CANCELLED", "Workflow execution cancelled", node.getId());
            }
            boolean waiting = result.isWaitingUser() || WorkflowInteractionCodes.WAITING.equals(result.code());
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("index", index);
            summary.put("entryNodeId", plan.branchEntries().get(index));
            if (result.success() && !waiting) {
                succeeded++;
                summary.put("status", "SUCCESS");
                summary.put("output", branchOutput(result, branchContexts.get(index)));
                merge(context, baseline, branchContexts.get(index));
            } else {
                String code = waiting ? "RUNTIME_GRAPH_PARALLEL_BRANCH_WAITING" : result.code();
                summary.put("status", "FAILED");
                summary.put("code", code);
                summary.put("message", RuntimeNodeFailureReasons.safeReason(code, result.answer()));
                summary.put("output", null);
                if (firstFailure == null) {
                    firstFailure = waiting
                            ? failure(code, "PARALLEL branch cannot pause for user input", node.getId())
                            : result;
                    failureIndex = index;
                }
                if (result.metadata() != null && Boolean.TRUE.equals(result.metadata().get("nonRecoverableWrite"))) {
                    Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
                    metadata.put("failureBranchIndex", index);
                    return new RuntimeGraphSpecExecutionResult(false, result.code(), result.answer(),
                            node.getId(), "PARALLEL", steps, metadata);
                }
            }
            branchSummaries.add(summary);
        }

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("branches", branchSummaries);
        structured.put("succeeded", succeeded);
        structured.put("failed", results.size() - succeeded);
        Map<String, Object> traceSummary = new LinkedHashMap<>();
        traceSummary.put("branchCount", results.size());
        traceSummary.put("joinNodeId", plan.joinNodeId());
        traceSummary.put("maxConcurrency", plan.maxConcurrency());
        traceSummary.put("failureMode", plan.failureMode());
        traceSummary.put("branches", branchSummaries.stream().map(item -> {
            Map<String, Object> safe = new LinkedHashMap<>(item);
            safe.remove("output");
            return safe;
        }).toList());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeId", node.getId());
        metadata.put("nodeType", "PARALLEL");
        metadata.put("traceSummary", traceSummary);
        metadata.put("parallelBranchTraces", branchTraces);
        if (firstFailure != null && GraphSpecParallelPlan.FAIL_FAST.equals(plan.failureMode())) {
            metadata.put("failureBranchIndex", failureIndex);
            metadata.put("structuredOutput", structured);
            return new RuntimeGraphSpecExecutionResult(false,
                    firstText(firstFailure.code(), "RUNTIME_GRAPH_PARALLEL_BRANCH_FAILED"),
                    firstText(firstFailure.answer(), "PARALLEL branch " + failureIndex + " failed"),
                    node.getId(), "PARALLEL", steps, metadata);
        }
        metadata.put("structuredOutput", structured);
        metadata.put("forceNextNodeId", plan.joinNodeId());
        if (steps.isEmpty()) steps.add(step(node.getId()));
        return new RuntimeGraphSpecExecutionResult(true, "RUNTIME_GRAPH_EXECUTED",
                succeeded + "/" + results.size(), node.getId(), "PARALLEL", steps, metadata);
    }

    private static Object branchOutput(RuntimeGraphSpecExecutionResult result, Map<String, Object> branchContext) {
        if (result.metadata() != null && result.metadata().containsKey("structuredOutput")) {
            return result.metadata().get("structuredOutput");
        }
        Object last = branchContext.get("lastOutput");
        return last != null ? last : result.answer();
    }

    /** Copies values the branch created or changed back into the main variables. */
    @SuppressWarnings("unchecked")
    static void merge(Map<String, Object> target, Map<String, Object> branch) {
        merge(target, RuntimeContextCopies.copy(target), branch);
    }

    @SuppressWarnings("unchecked")
    private static void merge(Map<String, Object> target, Map<String, Object> original, Map<String, Object> branch) {
        for (Map.Entry<String, Object> entry : branch.entrySet()) {
            String key = entry.getKey();
            if (key == null || PATH_LOCAL_KEYS.contains(key) || key.startsWith("__")) continue;
            Object value = entry.getValue();
            Object before = original.get(key);
            if (original.containsKey(key) && Objects.equals(before, value)) continue;
            if (value instanceof Map<?, ?> branchMap
                    && ("nodeOutput".equals(key) || "var".equals(key) || "params".equals(key))) {
                Map<?, ?> beforeMap = before instanceof Map<?, ?> map ? map : Map.of();
                if (before instanceof Map<?, ?> && !(target.get(key) instanceof Map<?, ?>)) {
                    target.put(key, value);
                    continue;
                }
                Map<String, Object> current = target.get(key) instanceof Map<?, ?> live
                        ? new LinkedHashMap<>((Map<String, Object>) live) : new LinkedHashMap<>();
                mergeChangedFields(current, beforeMap, branchMap);
                target.put(key, current);
                continue;
            }
            target.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static void mergeChangedFields(Map<String, Object> target, Map<?, ?> baseline, Map<?, ?> branch) {
        branch.forEach((key, value) -> {
            String name = String.valueOf(key);
            Object before = baseline.get(name);
            if (baseline.containsKey(name) && Objects.equals(before, value)) return;
            if (value instanceof Map<?, ?> nested
                    && (!(before instanceof Map<?, ?>) || target.get(name) instanceof Map<?, ?>)) {
                Map<?, ?> previous = before instanceof Map<?, ?> map ? map : Map.of();
                Map<String, Object> copy = target.get(name) instanceof Map<?, ?> current
                        ? new LinkedHashMap<>((Map<String, Object>) current) : new LinkedHashMap<>();
                mergeChangedFields(copy, previous, nested);
                target.put(name, copy);
            } else {
                target.put(name, value);
            }
        });
    }

    private RuntimeGraphSpecExecutionResult failure(String code, String answer, String nodeId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeId", nodeId);
        metadata.put("nodeType", "PARALLEL");
        return new RuntimeGraphSpecExecutionResult(false, code, answer, nodeId, "PARALLEL", List.of(), metadata);
    }

    private static Map<String, Object> step(String nodeId) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", "execute-node");
        step.put("detail", nodeId);
        return step;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }

    @FunctionalInterface
    interface BranchExecutor {
        RuntimeGraphSpecExecutionResult execute(GraphSpec graph,
                                                Map<String, GraphSpec.Node> nodesById,
                                                Set<String> branchNodeIds,
                                                String branchEntry,
                                                String joinNodeId,
                                                Map<String, Object> context,
                                                RuntimeGraphSpecExecutionEventSink eventSink,
                                                RuntimeGraphSpecExecutionCancellation cancellation);
    }
}

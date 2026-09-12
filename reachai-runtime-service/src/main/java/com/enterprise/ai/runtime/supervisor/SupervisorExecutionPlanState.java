package com.enterprise.ai.runtime.supervisor;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Per-run plan admission, ordered reservations and continuation state. No execution or audit I/O. */
final class SupervisorExecutionPlanState {
    private final Set<String> permittedToolNames;
    private final int maxReplans;
    private final int maxPlanSteps;
    private final int maxToolCalls;
    private final Set<String> completed = new LinkedHashSet<>();
    private final Set<String> blocked = new LinkedHashSet<>();
    private final Set<String> inFlight = new LinkedHashSet<>();
    private int planCount;
    private int failureAtPlanNo = -1;
    private int cursor;
    private List<String> planned = List.of();
    private Map<String, Object> recordedPlan;

    SupervisorExecutionPlanState(Set<String> permittedToolNames, int maxReplans, int maxPlanSteps, int maxToolCalls) {
        this.permittedToolNames = Set.copyOf(permittedToolNames);
        this.maxReplans = maxReplans;
        this.maxPlanSteps = maxPlanSteps;
        this.maxToolCalls = maxToolCalls;
    }

    synchronized PlanDecision record(Map<String, Object> plan, int usedToolCalls) {
        if (!inFlight.isEmpty()) {
            return PlanDecision.rejected("Cannot replace the structured plan while a Workflow tool is running");
        }
        if (hasUnfinished() && !failed()) {
            return PlanDecision.rejected("Continue the saved structured plan; replan is allowed only after a Workflow failure");
        }
        Object rawNames = plan == null ? null : plan.get("workflowToolNames");
        List<String> names = toolNames(rawNames);
        int rawCount = rawNames instanceof List<?> list ? list.size() : 0;
        boolean abandoned = names.isEmpty() && rawCount == 0 && planCount > 0 && failed();
        if (names.size() != rawCount || names.isEmpty() && !abandoned) {
            return PlanDecision.rejected("workflowToolNames must contain exact non-empty Workflow tool names; "
                    + "an empty list is allowed only when revising a failed plan");
        }
        if (new LinkedHashSet<>(names).size() != names.size()) {
            return PlanDecision.rejected("workflowToolNames must not contain duplicates");
        }
        if (!permittedToolNames.containsAll(names)) {
            return PlanDecision.rejected("workflowToolNames contains a tool outside the permitted published execution-tool allowlist");
        }
        if (names.stream().anyMatch(name -> completed.contains(name) || blocked.contains(name))) {
            return PlanDecision.rejected("A revised plan must not include a Workflow tool already completed or marked non-retryable in this run");
        }
        if ((long) usedToolCalls + names.size() > maxToolCalls) {
            return PlanDecision.rejected("Structured plan exceeds the remaining execution-tool call limit");
        }
        if ((long) planCount + 1 > (long) maxReplans + 1) {
            return PlanDecision.rejected("Supervisor replan limit exceeded");
        }
        Object rawSteps = plan == null ? null : plan.get("steps");
        int stepCount = rawSteps instanceof List<?> list ? list.size() : 0;
        if (stepCount == 0 || stepCount > maxPlanSteps) {
            return PlanDecision.rejected("Plan must contain 1 to " + maxPlanSteps + " steps");
        }
        int nextPlanNo = planCount + 1;
        Map<String, Object> normalized = new LinkedHashMap<>(plan);
        normalized.put("workflowToolNames", List.copyOf(names));
        normalized.put("planNo", nextPlanNo);
        normalized.put("kind", nextPlanNo == 1 ? "PLAN" : "REPLAN");
        Map<String, Object> immutable;
        try {
            // Stage the complete immutable value before changing any admission counters or cursor.
            immutable = immutableMap(Map.copyOf(normalized));
        } catch (NullPointerException invalid) {
            return PlanDecision.rejected("Plan fields must not be null");
        }
        planCount = nextPlanNo;
        recordedPlan = immutable;
        planned = List.copyOf(names);
        cursor = 0;
        failureAtPlanNo = -1;
        return new PlanDecision(null, immutable, nextPlanNo, abandoned);
    }

    synchronized String reserve(String toolName) {
        if (failed()) return "The previous execution tool failed; record a revised plan before continuing";
        if (completed.contains(toolName)) return "Execution tool already completed in this run; do not re-execute: " + toolName;
        if (blocked.contains(toolName)) return "Execution tool is non-retryable after failure in this run: " + toolName;
        if (planned.isEmpty() || cursor >= planned.size()) {
            return "No remaining structured execution-tool plan step permits: " + toolName;
        }
        int index = planned.indexOf(toolName);
        if (index < 0) return "Execution tool is not present in the structured plan: " + toolName;
        if (index < cursor) return "Execution tool already completed in the structured plan: " + toolName;
        for (int predecessor = cursor; predecessor < index; predecessor++) {
            String name = planned.get(predecessor);
            if (!completed.contains(name) && !inFlight.contains(name)) {
                return "Execution tool is out of order; next required tool is: " + name;
            }
        }
        if (!inFlight.add(toolName)) return "Execution tool is already running: " + toolName;
        return null;
    }

    synchronized void release(String toolName) {
        if (StringUtils.hasText(toolName)) inFlight.remove(toolName);
    }

    synchronized void complete(String toolName) {
        int index = planned.indexOf(toolName);
        if (index < cursor || index < 0) {
            throw new IllegalStateException("Workflow completion does not match the structured plan cursor");
        }
        completed.add(toolName);
        blocked.add(toolName);
        while (cursor < planned.size() && completed.contains(planned.get(cursor))) cursor++;
    }

    synchronized void fail(String nonRetryableToolName) {
        failureAtPlanNo = planCount;
        if (StringUtils.hasText(nonRetryableToolName)) blocked.add(nonRetryableToolName);
    }

    synchronized boolean failed() { return failureAtPlanNo == planCount; }
    synchronized boolean isCompleted(String toolName) { return completed.contains(toolName); }
    synchronized boolean isBlocked(String toolName) { return blocked.contains(toolName); }
    synchronized int planCount() { return planCount; }
    synchronized boolean hasUnfinished() { return !planned.isEmpty() && cursor < planned.size(); }

    synchronized String finalAnswerBlockReason() {
        return hasUnfinished()
                ? "Complete the remaining structured execution-tool plan first; next required tool: " + planned.get(cursor)
                : null;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(planCount, cursor, planned, List.copyOf(completed), recordedPlan);
    }

    /** Replace incoming continuity markers with the execution owner's current plan. */
    synchronized Map<String, Object> approvalInput(Map<String, Object> input, Map<String, Integer> callCounts) {
        Map<String, Object> saved = new LinkedHashMap<>(input == null ? Map.of() : input);
        Map<String, Object> continuation = new LinkedHashMap<>();
        continuation.put("planNo", planCount);
        continuation.put("recordedPlan", recordedPlan);
        continuation.put("plannedWorkflowToolNames", List.copyOf(planned));
        continuation.put("plannedWorkflowCursor", cursor);
        continuation.put("completedWorkflowToolNames", List.copyOf(completed));
        continuation.put("executionCallCounts", Map.copyOf(callCounts));
        saved.put("__supervisorContinuation", immutableMap(continuation));
        saved.put("__blockedWorkflowTools", List.copyOf(blocked));
        return saved;
    }

    synchronized String approvedStepError(String toolName) {
        if (planCount <= 0 || recordedPlan == null || planned.isEmpty()
                || cursor < 0 || cursor >= planned.size()
                || !planned.equals(toolNames(recordedPlan.get("workflowToolNames")))
                || !permittedToolNames.containsAll(planned)
                || new LinkedHashSet<>(planned).size() != planned.size()) {
            return "Approval requires a valid saved execution plan";
        }
        if (!planned.get(cursor).equals(toolName) || completed.contains(toolName)
                || blocked.contains(toolName) || failed() || !inFlight.isEmpty()
                || !completed.containsAll(planned.subList(0, cursor))) {
            return "Approved tool must be the next unfinished saved plan step";
        }
        return null;
    }

    /** Restore the existing owner-validated continuation contract before the run is published to other threads. */
    synchronized void restore(Map<String, Object> input) {
        if (input == null || input.isEmpty()) return;
        for (String name : names(input.get("__blockedWorkflowTools"), false)) {
            blocked.add(name);
            if (!(input.get("__supervisorContinuation") instanceof Map<?, ?>)) completed.add(name);
        }
        if (!(input.get("__supervisorContinuation") instanceof Map<?, ?> continuation)) return;
        if (continuation.get("planNo") instanceof Number number && number.intValue() > 0) planCount = number.intValue();
        if (continuation.get("recordedPlan") instanceof Map<?, ?> plan) {
            recordedPlan = immutableMap(Map.copyOf(plan));
            if (planCount == 0) {
                Object nested = plan.get("planNo");
                planCount = nested instanceof Number number && number.intValue() > 0 ? number.intValue() : 1;
            }
        }
        List<String> names = toolNames(continuation.get("plannedWorkflowToolNames"));
        if (!names.isEmpty() && continuation.get("plannedWorkflowCursor") instanceof Number value) {
            planned = List.copyOf(names);
            cursor = value.intValue();
        }
        for (String name : names(continuation.get("completedWorkflowToolNames"), false)) {
            completed.add(name);
            blocked.add(name);
        }
    }

    static List<String> toolNames(Object value) {
        return names(value, true);
    }

    private static List<String> names(Object value, boolean ignoreNullLiteral) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> names = new ArrayList<>();
        for (Object item : list) {
            String name = item == null ? null : String.valueOf(item).trim();
            if (StringUtils.hasText(name) && (!ignoreNullLiteral || !"null".equalsIgnoreCase(name))) names.add(name);
        }
        return names;
    }

    private static Map<String, Object> immutableMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put((String) key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) return immutableMap(map);
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    record PlanDecision(String error, Map<String, Object> plan, int planNo, boolean abandonedFailedPath) {
        static PlanDecision rejected(String error) { return new PlanDecision(error, null, 0, false); }
    }

    record Snapshot(int planCount, int cursor, List<String> plannedToolNames,
                    List<String> completedToolNames, Map<String, Object> recordedPlan) {}
}

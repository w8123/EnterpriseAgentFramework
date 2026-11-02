package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot;
import org.springframework.util.StringUtils;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import static com.enterprise.ai.common.util.Texts.firstText;

/** 已验证的 Workflow 续跑顺序；数值字段不能通过截断或溢出变为合法位置。 */
record SupervisorWorkflowContinuation(List<String> plannedWorkflowToolNames,
                                      int plannedWorkflowCursor,
                                      String waitingToolName,
                                      List<String> completedWorkflowToolNames,
                                      List<Integer> completedPlanPositions,
                                      SupervisorExecutionCallCounts executionCallCounts) {
    static final int SCHEMA_VERSION = 3;
    static final int POSITION_SCHEMA_VERSION = 4;

    static SupervisorWorkflowContinuation parse(
            Map<String, Object> continuation,
            List<RuntimeAgentWorkflowToolSnapshot> workflowTools) {
        return parse(continuation, workflowTools, Set.of());
    }

    static SupervisorWorkflowContinuation parse(
            Map<String, Object> continuation,
            List<RuntimeAgentWorkflowToolSnapshot> workflowTools,
            Set<String> repeatableToolNames) {
        Object rawVersion = continuation.get("continuationSchemaVersion");
        int version = integer(rawVersion, "unsupported continuation schema version");
        if (version != SCHEMA_VERSION && version != POSITION_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported continuation schema version");
        }
        List<String> planned = stringList(continuation.get("plannedWorkflowToolNames"));
        if (planned.isEmpty()) {
            throw new IllegalArgumentException("plannedWorkflowToolNames is required");
        }
        Set<String> allowed = new LinkedHashSet<>();
        Set<String> nonRead = new LinkedHashSet<>();
        if (workflowTools != null) {
            for (RuntimeAgentWorkflowToolSnapshot tool : workflowTools) {
                if (tool != null && StringUtils.hasText(tool.getToolName())) {
                    String name = tool.getToolName().trim();
                    allowed.add(name);
                    if (!Boolean.TRUE.equals(tool.getReadOnly())
                            || !("READ".equalsIgnoreCase(tool.getRiskLevel())
                                || "READ_ONLY".equalsIgnoreCase(tool.getRiskLevel()))) nonRead.add(name);
                }
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String name : planned) {
            if (!seen.add(name) && (version == 3 || !repeatableToolNames.contains(name) || nonRead.contains(name))) {
                throw new IllegalArgumentException("only verified read-only Workflow tools may repeat");
            }
        }
        if (!allowed.containsAll(planned)) {
            throw new IllegalArgumentException("plan contains a Workflow tool outside the published allowlist");
        }
        Object rawCursor = continuation.get("plannedWorkflowCursor");
        if (!(rawCursor instanceof Number cursorNumber)) {
            throw new IllegalArgumentException("plannedWorkflowCursor is required");
        }
        int cursor = integer(cursorNumber, "plannedWorkflowCursor must be an integer");
        if (cursor < 0 || cursor >= planned.size()) {
            throw new IllegalArgumentException("plannedWorkflowCursor is out of range");
        }
        String waitingTool = firstText(
                textObj(continuation.get("waitingToolName")),
                textObj(continuation.get("toolName")));
        if (!planned.get(cursor).equals(waitingTool)) {
            throw new IllegalArgumentException("waiting Workflow tool does not match the saved plan cursor");
        }
        Map<String, Object> recordedPlan = asMap(continuation.get("recordedPlan"));
        if (!planned.equals(stringList(recordedPlan.get("workflowToolNames")))) {
            throw new IllegalArgumentException("recorded plan does not match the structured Workflow order");
        }
        List<String> completed = stringList(continuation.get("completedWorkflowToolNames"));
        Set<String> completedSet = new LinkedHashSet<>(completed);
        if (completedSet.size() != completed.size() || !allowed.containsAll(completedSet)) {
            throw new IllegalArgumentException("completed Workflow tools are invalid");
        }
        Set<Integer> positions = new LinkedHashSet<>();
        if (version == 3) {
            for (int index = 0; index < planned.size(); index++) {
                if (completedSet.contains(planned.get(index))) positions.add(index);
            }
        } else {
            if (!(continuation.get("completedPlanPositions") instanceof List<?> rawPositions)) {
                throw new IllegalArgumentException("completed plan positions are required");
            }
            for (Object rawPosition : rawPositions) {
                int index = integer(rawPosition, "completed plan position must be an integer");
                if (index < 0 || index >= planned.size() || !positions.add(index)
                        || !completedSet.contains(planned.get(index))) {
                    throw new IllegalArgumentException("completed plan position is invalid");
                }
            }
        }
        for (int index = 0; index < cursor; index++) {
            if (!positions.contains(index)) {
                throw new IllegalArgumentException("saved plan prefix is incomplete");
            }
        }
        if (positions.contains(cursor) || completedSet.contains(waitingTool) && !repeatableToolNames.contains(waitingTool)) {
            throw new IllegalArgumentException("waiting Workflow tool was already completed");
        }
        var counts = SupervisorExecutionCallCounts.parse(continuation.get("executionCallCounts"));
        if (counts.workflow() < Math.max(completed.size(), positions.size()) + 1) {
            throw new IllegalArgumentException("saved Workflow call count does not cover the completed and waiting calls");
        }
        return new SupervisorWorkflowContinuation(List.copyOf(planned), cursor, waitingTool,
                List.copyOf(completed), List.copyOf(new ArrayList<>(positions)), counts);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return new LinkedHashMap<>();
    }

    private static List<String> stringList(Object value) {
        return SupervisorExecutionPlanState.toolNames(value);
    }

    private static String textObj(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static int integer(Object value, String message) {
        if (!(value instanceof Number)) throw new IllegalArgumentException(message);
        try {
            return new BigDecimal(value.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException(message);
        }
    }
}

package com.enterprise.ai.control.aicoding.domain;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ExecutionStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class AiCodingTaskStateMachine {

    private static final Map<ExecutionStatus, Set<ExecutionStatus>> TRANSITIONS = transitions();

    private AiCodingTaskStateMachine() {
    }

    public static void requireTransition(ExecutionStatus current, ExecutionStatus next) {
        if (current == next) {
            return;
        }
        if (current.terminal() || !TRANSITIONS.getOrDefault(current, Set.of()).contains(next)) {
            throw new IllegalStateException(
                    "AI Coding task cannot transition from " + current + " to " + next);
        }
    }

    private static Map<ExecutionStatus, Set<ExecutionStatus>> transitions() {
        Map<ExecutionStatus, Set<ExecutionStatus>> values = new EnumMap<>(ExecutionStatus.class);
        values.put(ExecutionStatus.READY, EnumSet.of(
                ExecutionStatus.RUNNING,
                ExecutionStatus.FAILED,
                ExecutionStatus.CANCELLED));
        values.put(ExecutionStatus.RUNNING, EnumSet.of(
                ExecutionStatus.WAITING_USER,
                ExecutionStatus.RESULT_SUBMITTED,
                ExecutionStatus.FAILED,
                ExecutionStatus.CANCELLED));
        values.put(ExecutionStatus.WAITING_USER, EnumSet.of(
                ExecutionStatus.RUNNING,
                ExecutionStatus.FAILED,
                ExecutionStatus.CANCELLED));
        values.put(ExecutionStatus.RESULT_SUBMITTED, EnumSet.of(
                ExecutionStatus.RUNNING,
                ExecutionStatus.RESULT_APPLIED,
                ExecutionStatus.FAILED,
                ExecutionStatus.CANCELLED));
        values.put(ExecutionStatus.RESULT_APPLIED, EnumSet.of(
                ExecutionStatus.ACCEPTANCE_READY,
                ExecutionStatus.COMPLETED,
                ExecutionStatus.FAILED,
                ExecutionStatus.CANCELLED));
        values.put(ExecutionStatus.ACCEPTANCE_READY, EnumSet.of(
                ExecutionStatus.COMPLETED,
                ExecutionStatus.FAILED,
                ExecutionStatus.CANCELLED));
        return Map.copyOf(values);
    }
}

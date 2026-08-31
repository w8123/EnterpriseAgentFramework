package com.enterprise.ai.control.a2a.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Single source of truth for persisted A2A Task state transitions. */
public final class A2aTaskLifecycle {

    private static final Map<A2aTaskState, Set<A2aTaskState>> TRANSITIONS = transitions();

    public static boolean canTransition(A2aTaskState current, A2aTaskState next) {
        requirePersistable(current, "current");
        requirePersistable(next, "next");
        return current == next || TRANSITIONS.getOrDefault(current, Set.of()).contains(next);
    }

    public static void requireTransition(A2aTaskState current, A2aTaskState next) {
        if (!canTransition(current, next)) {
            throw new A2aDomainException(
                    "A2A_TASK_STATE_TRANSITION_INVALID",
                    "task state cannot transition from " + current + " to " + next);
        }
    }

    public static Set<A2aTaskState> allowedNext(A2aTaskState current) {
        requirePersistable(current, "current");
        return Set.copyOf(TRANSITIONS.getOrDefault(current, Set.of()));
    }

    private static Map<A2aTaskState, Set<A2aTaskState>> transitions() {
        EnumMap<A2aTaskState, Set<A2aTaskState>> values = new EnumMap<>(A2aTaskState.class);
        values.put(A2aTaskState.TASK_STATE_SUBMITTED, EnumSet.of(
                A2aTaskState.TASK_STATE_WORKING,
                A2aTaskState.TASK_STATE_INPUT_REQUIRED,
                A2aTaskState.TASK_STATE_AUTH_REQUIRED,
                A2aTaskState.TASK_STATE_COMPLETED,
                A2aTaskState.TASK_STATE_REJECTED,
                A2aTaskState.TASK_STATE_CANCELED,
                A2aTaskState.TASK_STATE_FAILED));
        values.put(A2aTaskState.TASK_STATE_WORKING, EnumSet.of(
                A2aTaskState.TASK_STATE_INPUT_REQUIRED,
                A2aTaskState.TASK_STATE_AUTH_REQUIRED,
                A2aTaskState.TASK_STATE_COMPLETED,
                A2aTaskState.TASK_STATE_FAILED,
                A2aTaskState.TASK_STATE_CANCELED,
                A2aTaskState.TASK_STATE_REJECTED));
        values.put(A2aTaskState.TASK_STATE_INPUT_REQUIRED, EnumSet.of(
                A2aTaskState.TASK_STATE_WORKING,
                A2aTaskState.TASK_STATE_CANCELED,
                A2aTaskState.TASK_STATE_FAILED,
                A2aTaskState.TASK_STATE_REJECTED));
        values.put(A2aTaskState.TASK_STATE_AUTH_REQUIRED, EnumSet.of(
                A2aTaskState.TASK_STATE_WORKING,
                A2aTaskState.TASK_STATE_CANCELED,
                A2aTaskState.TASK_STATE_FAILED,
                A2aTaskState.TASK_STATE_REJECTED));
        return Map.copyOf(values);
    }

    private static void requirePersistable(A2aTaskState state, String field) {
        if (state == null || !state.persistable()) {
            throw new A2aDomainException("A2A_TASK_STATE_INVALID", field + " task state is not persistable");
        }
    }

    private A2aTaskLifecycle() {
    }
}

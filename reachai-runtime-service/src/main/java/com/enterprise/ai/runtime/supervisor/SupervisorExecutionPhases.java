package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionEventSink;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-run phase state and ordered delivery of supervisor.step updates. */
final class SupervisorExecutionPhases {
    private final Object monitor = new Object();
    private final RuntimeAgentExecutionEventSink sink;
    private final Map<String, Map<String, Object>> phases = new LinkedHashMap<>();
    private final ArrayDeque<Map<String, Object>> pending = new ArrayDeque<>();
    private int sequence;
    private boolean draining;

    SupervisorExecutionPhases(RuntimeAgentExecutionEventSink sink) {
        this.sink = sink == null ? RuntimeAgentExecutionEventSink.NOOP : sink;
    }

    void emit(String stepId, String name, String state, String source, String title, String detail) {
        boolean deliver;
        synchronized (monitor) {
            record(stepId, name, state, source, title, detail);
            deliver = claimDelivery();
        }
        if (deliver) drain();
    }

    void complete(String stepId, String name, String source, String title, String detail) {
        boolean deliver;
        synchronized (monitor) {
            if (!phases.containsKey(stepId) && !"final_answer".equals(name)) return;
            record(stepId, name, "completed", source, title, detail);
            deliver = claimDelivery();
        }
        if (deliver) drain();
    }

    void terminateActive(String terminalState, String detail) {
        boolean deliver;
        synchronized (monitor) {
            for (Map<String, Object> step : new ArrayList<>(phases.values())) {
                Object state = step.get("state");
                if (!"started".equals(state) && !"waiting".equals(state) && !"active".equals(state)) continue;
                String stepId = String.valueOf(step.get("stepId"));
                String name = String.valueOf(step.getOrDefault("name", "supervisor"));
                String source = String.valueOf(step.getOrDefault("source", "runtime_lifecycle"));
                String title = String.valueOf(step.getOrDefault("title", name));
                record(stepId, name, terminalState, source, title,
                        StringUtils.hasText(detail) ? detail : terminalState);
            }
            deliver = claimDelivery();
        }
        if (deliver) drain();
    }

    List<Map<String, Object>> snapshot() {
        synchronized (monitor) {
            List<Map<String, Object>> snapshot = new ArrayList<>(phases.size());
            for (Map<String, Object> phase : phases.values()) snapshot.add(new LinkedHashMap<>(phase));
            return List.copyOf(snapshot);
        }
    }

    private void record(String stepId, String name, String state, String source, String title, String detail) {
        Map<String, Object> previous = phases.get(stepId);
        Map<String, Object> phase = new LinkedHashMap<>();
        phase.put("stepId", stepId);
        phase.put("name", name);
        phase.put("state", state);
        phase.put("source", source);
        phase.put("title", title);
        if (StringUtils.hasText(detail)) phase.put("detail", detail);
        phase.put("timestamp", OffsetDateTime.now().toString());
        phase.put("sequence", previous == null ? ++sequence : previous.get("sequence"));
        Map<String, Object> stored = Collections.unmodifiableMap(phase);
        phases.put(stepId, stored);
        pending.addLast(stored);
    }

    private boolean claimDelivery() {
        if (draining || pending.isEmpty()) return false;
        draining = true;
        return true;
    }

    private void drain() {
        RuntimeException firstFailure = null;
        while (true) {
            Map<String, Object> phase;
            synchronized (monitor) {
                phase = pending.pollFirst();
                if (phase == null) {
                    draining = false;
                    if (firstFailure != null) throw firstFailure;
                    return;
                }
            }
            try {
                // One caller drains in commit order. Other callers may update state while I/O is slow.
                // A detached payload also lets a transport decorate an event without changing state.
                sink.emit("supervisor.step", new LinkedHashMap<>(phase));
            } catch (RuntimeException failure) {
                // Do not retry an ambiguously delivered event or strand events already queued by peers.
                if (firstFailure == null) firstFailure = failure;
                else if (firstFailure != failure) firstFailure.addSuppressed(failure);
            } catch (Error fatal) {
                synchronized (monitor) { draining = false; }
                throw fatal;
            }
        }
    }
}

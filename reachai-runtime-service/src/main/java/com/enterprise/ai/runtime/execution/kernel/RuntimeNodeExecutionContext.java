package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;

import java.util.Map;

/**
 * Runtime-owned context passed to one GraphSpec node handler.
 *
 * <p>The trusted execution identity remains inside the server-owned execution state. Handlers must
 * never construct or promote it from node configuration, model arguments, or public request maps.</p>
 */
public record RuntimeNodeExecutionContext(
        GraphSpec graph,
        Map<String, Object> variables,
        RuntimeGraphSpecExecutionEventSink eventSink,
        RuntimeGraphSpecExecutionCancellation cancellation
) {

    public RuntimeNodeExecutionContext {
        variables = variables == null ? Map.of() : variables;
        eventSink = eventSink == null ? RuntimeGraphSpecExecutionEventSink.NOOP : eventSink;
        cancellation = cancellation == null
                ? RuntimeGraphSpecExecutionCancellation.none()
                : cancellation;
    }
}

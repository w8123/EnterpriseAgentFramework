package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;

/** Executes exactly one canonical GraphSpec node type. */
public interface RuntimeNodeHandler {

    String nodeType();

    RuntimeGraphSpecExecutionResult execute(GraphSpec.Node node, RuntimeNodeExecutionContext context);
}

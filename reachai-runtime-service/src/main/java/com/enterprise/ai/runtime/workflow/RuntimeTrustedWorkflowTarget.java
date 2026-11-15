package com.enterprise.ai.runtime.workflow;

/** Published Workflow data required by the trusted page-action contract, independent of Agent execution. */
public interface RuntimeTrustedWorkflowTarget {
    RuntimeWorkflowExecutionView workflow();

    RuntimeWorkflowPublishedVersionView version();

    record Snapshot(RuntimeWorkflowExecutionView workflow, RuntimeWorkflowPublishedVersionView version)
            implements RuntimeTrustedWorkflowTarget { }
}

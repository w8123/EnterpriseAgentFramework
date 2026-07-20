package com.enterprise.ai.runtime.workflow.node;

/**
 * Product maturity for a Workflow GraphSpec node type.
 */
public enum WorkflowNodeMaturity {
    STABLE,
    BETA,
    /** Runtime handler exists, but product openness is blocked pending code-gate tests. */
    CODE_GATE_PENDING,
    PLANNED
}

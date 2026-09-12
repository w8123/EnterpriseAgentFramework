package com.enterprise.ai.runtime.execution.interaction;

public final class WorkflowInteractionCodes {

    public static final String WAITING = "RUNTIME_GRAPH_INTERACTION_WAITING";
    public static final String VALIDATION_FAILED = "RUNTIME_GRAPH_INTERACTION_VALIDATION_FAILED";
    public static final String PAYLOAD_MISMATCH = "RUNTIME_GRAPH_INTERACTION_PAYLOAD_MISMATCH";
    public static final String UNSUPPORTED_CUSTOM = "RUNTIME_GRAPH_INTERACTION_CUSTOM_UNSUPPORTED";
    public static final String RESUME_TIMEOUT = "RUNTIME_INTERACTION_RESUME_TIMEOUT";
    public static final String RESUME_TIMEOUT_MESSAGE = "Workflow 恢复期限已过，执行结果未确认。请核对业务系统中的处理结果，勿重复提交。";
    public static final String ID_PREFIX = "wfi_";
    public static final String RESUME_CONTEXT_KEY = "__interactionResume";
    public static final String PENDING_INTERACTION_ID_KEY = "__pendingInteractionId";
    public static final String PENDING_INTERACTION_NODE_KEY = "__pendingInteractionNodeId";
    public static final String PROTOCOL_VERSION = "1.0";

    private WorkflowInteractionCodes() {
    }
}

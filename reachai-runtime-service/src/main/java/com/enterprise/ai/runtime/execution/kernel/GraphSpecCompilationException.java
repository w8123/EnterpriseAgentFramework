package com.enterprise.ai.runtime.execution.kernel;

/** Deterministic GraphSpec compilation failure safe to map to a Runtime result. */
public final class GraphSpecCompilationException extends RuntimeException {

    private final String code;
    private final String nodeId;
    private final String nodeType;

    public GraphSpecCompilationException(String code, String message, String nodeId, String nodeType) {
        super(message);
        this.code = code;
        this.nodeId = nodeId;
        this.nodeType = nodeType;
    }

    public String code() {
        return code;
    }

    public String nodeId() {
        return nodeId;
    }

    public String nodeType() {
        return nodeType;
    }
}

package com.enterprise.ai.pipeline.document.job;

/** Persisted lifecycle for a source document, not the transient Docling task. */
public enum DocumentImportJobStatus {
    QUEUED,
    PARSING,
    PARSED,
    INDEXING,
    COMPLETED,
    RETRY_WAIT,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}

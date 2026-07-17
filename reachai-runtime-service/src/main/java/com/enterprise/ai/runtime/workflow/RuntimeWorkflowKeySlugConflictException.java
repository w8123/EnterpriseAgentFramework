package com.enterprise.ai.runtime.workflow;

/** Raised when a Workflow stable key is already owned by another Workflow. */
public class RuntimeWorkflowKeySlugConflictException extends RuntimeException {

    private final String keySlug;

    public RuntimeWorkflowKeySlugConflictException(String keySlug) {
        super("workflow keySlug already exists: " + keySlug);
        this.keySlug = keySlug;
    }

    public String getKeySlug() {
        return keySlug;
    }
}

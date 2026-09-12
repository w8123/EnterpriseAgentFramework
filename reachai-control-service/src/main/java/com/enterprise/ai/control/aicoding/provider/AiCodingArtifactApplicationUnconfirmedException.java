package com.enterprise.ai.control.aicoding.provider;

/**
 * A remote application cannot be confirmed and may already have committed.
 * Propagate through the Artifact transaction so its original identity remains retryable;
 * this must not be converted to an immutable domain rejection at the Provider savepoint.
 */
public final class AiCodingArtifactApplicationUnconfirmedException extends RuntimeException {
    public AiCodingArtifactApplicationUnconfirmedException(String message) {
        super(message);
    }
}

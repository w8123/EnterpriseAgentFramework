package com.enterprise.ai.pipeline.document.artifact;

/** Raised when the durable source or parse-artifact store cannot be used. */
public class DocumentArtifactException extends RuntimeException {

    public DocumentArtifactException(String message) {
        super(message);
    }

    public DocumentArtifactException(String message, Throwable cause) {
        super(message, cause);
    }
}

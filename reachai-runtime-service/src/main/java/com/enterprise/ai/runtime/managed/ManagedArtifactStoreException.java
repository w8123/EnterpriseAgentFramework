package com.enterprise.ai.runtime.managed;

public class ManagedArtifactStoreException extends RuntimeException {

    public ManagedArtifactStoreException(String message) {
        super(message);
    }

    public ManagedArtifactStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}

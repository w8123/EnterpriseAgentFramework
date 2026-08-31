package com.enterprise.ai.runtime.managed;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
@ConditionalOnProperty(prefix = "reachai.runtime.managed-executor.artifact-store", name = "type",
        havingValue = "disabled", matchIfMissing = true)
public class DisabledManagedArtifactStore implements ManagedArtifactStore {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public void put(String objectKey, InputStream content, long contentLength, String mediaType) {
        throw unavailable();
    }

    @Override
    public InputStream open(String objectKey) {
        throw unavailable();
    }

    @Override
    public void delete(String objectKey) {
        throw unavailable();
    }

    private ManagedArtifactStoreException unavailable() {
        return new ManagedArtifactStoreException("Managed Executor artifact store is disabled");
    }
}

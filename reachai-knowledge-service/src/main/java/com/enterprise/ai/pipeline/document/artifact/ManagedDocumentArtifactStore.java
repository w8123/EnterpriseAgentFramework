package com.enterprise.ai.pipeline.document.artifact;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;

@Component
public class ManagedDocumentArtifactStore implements DocumentArtifactStore {
    private final DocumentArtifactBackend backend;
    private final DocumentArtifactLifecycleStore lifecycle;
    private final TransactionTemplate suspended;

    public ManagedDocumentArtifactStore(DocumentArtifactBackend backend, DocumentArtifactLifecycleStore lifecycle,
                                        PlatformTransactionManager manager) {
        this.backend = backend;
        this.lifecycle = lifecycle;
        suspended = new TransactionTemplate(manager);
        suspended.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    @Override
    public void put(String key, InputStream content, long contentLength, String contentType) {
        suspended.executeWithoutResult(status -> {
            String storage = backend.storageId();
            lifecycle.register(storage, key);
            try {
                backend.put(key, content, contentLength, contentType);
                lifecycle.acknowledge(storage, key);
            } catch (RuntimeException failure) {
                try {
                    lifecycle.retire(storage, key);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        });
    }

    @Override
    public InputStream open(String key) {
        return backend.open(DocumentArtifactIdentity.key(key));
    }

    @Override
    public void retain(String key) {
        lifecycle.retain(backend.storageId(), key);
    }

    @Override
    public void retire(String key) {
        lifecycle.retire(backend.storageId(), key);
    }
}

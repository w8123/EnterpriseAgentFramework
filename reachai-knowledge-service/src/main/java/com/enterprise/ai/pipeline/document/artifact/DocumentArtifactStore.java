package com.enterprise.ai.pipeline.document.artifact;

import java.io.InputStream;

/**
 * Service-owned artifact lifecycle. Writes are registered before IO, reference
 * publication retains them in its transaction, and retirement is durable.
 */
public interface DocumentArtifactStore {

    void put(String objectKey, InputStream content, long contentLength, String contentType);

    InputStream open(String objectKey);

    /** Must join the transaction that publishes the first job reference to a new object. */
    void retain(String objectKey);

    /** Persist cleanup intent in the caller's metadata transaction; do not perform remote IO here. */
    void retire(String objectKey);
}

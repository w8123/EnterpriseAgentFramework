package com.enterprise.ai.pipeline.document.artifact;

import java.io.InputStream;

/**
 * Durable, service-owned storage boundary.  Providers receive a fresh stream
 * from this interface so parsing can be retried without asking a browser to
 * upload the source again.
 */
public interface DocumentArtifactStore {

    void put(String objectKey, InputStream content, long contentLength, String contentType);

    InputStream open(String objectKey);

    void delete(String objectKey);
}

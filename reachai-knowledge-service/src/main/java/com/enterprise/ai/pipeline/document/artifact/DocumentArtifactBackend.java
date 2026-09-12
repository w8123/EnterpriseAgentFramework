package com.enterprise.ai.pipeline.document.artifact;

import java.io.InputStream;

/** Raw object IO. Business code uses DocumentArtifactStore to preserve lifecycle records. */
public interface DocumentArtifactBackend {
    String storageId();
    void put(String objectKey, InputStream content, long contentLength, String contentType);
    InputStream open(String objectKey);
    void delete(String objectKey);
}

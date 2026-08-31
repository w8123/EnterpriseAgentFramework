package com.enterprise.ai.runtime.managed;

import java.io.InputStream;

/**
 * Runtime-owned durable storage boundary for Managed Executor evidence.
 * Worker credentials never reach the backing object store.
 */
public interface ManagedArtifactStore {

    boolean available();

    void put(String objectKey, InputStream content, long contentLength, String mediaType);

    InputStream open(String objectKey);

    void delete(String objectKey);
}

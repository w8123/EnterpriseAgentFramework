package com.enterprise.ai.runtime.managed;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reachai.runtime.managed-executor.artifact-store", name = "type",
        havingValue = "s3")
public class S3ManagedArtifactStore implements ManagedArtifactStore {

    private final ManagedArtifactStoreProperties properties;
    private MinioClient client;
    private volatile boolean bucketReady;

    @PostConstruct
    void initialize() {
        requireText(properties.getEndpoint(), "REACHAI_MANAGED_EXECUTOR_ARTIFACT_S3_ENDPOINT");
        requireText(properties.getAccessKey(), "REACHAI_MANAGED_EXECUTOR_ARTIFACT_S3_ACCESS_KEY");
        requireText(properties.getSecretKey(), "REACHAI_MANAGED_EXECUTOR_ARTIFACT_S3_SECRET_KEY");
        requireText(properties.getBucket(), "REACHAI_MANAGED_EXECUTOR_ARTIFACT_S3_BUCKET");
        client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .region(properties.getRegion())
                .build();
        ensureBucket();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public void put(String objectKey, InputStream content, long contentLength, String mediaType) {
        if (contentLength < 0) throw new ManagedArtifactStoreException("Artifact content length is required");
        try {
            ensureBucket();
            client.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .stream(content, contentLength, -1)
                    .contentType(mediaType)
                    .build());
        } catch (ManagedArtifactStoreException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ManagedArtifactStoreException("Cannot write Managed Executor S3 artifact", failure);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception failure) {
            throw new ManagedArtifactStoreException("Cannot read Managed Executor S3 artifact", failure);
        }
    }

    @Override
    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return;
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception failure) {
            throw new ManagedArtifactStoreException("Cannot delete Managed Executor S3 artifact", failure);
        }
    }

    private synchronized void ensureBucket() {
        if (bucketReady) return;
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder()
                    .bucket(properties.getBucket()).build());
            if (!exists) {
                if (!properties.isAutoCreateBucket()) {
                    throw new ManagedArtifactStoreException("Managed Executor S3 bucket does not exist");
                }
                try {
                    client.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
                } catch (Exception createFailure) {
                    if (!client.bucketExists(BucketExistsArgs.builder()
                            .bucket(properties.getBucket()).build())) {
                        throw createFailure;
                    }
                }
            }
            bucketReady = true;
        } catch (ManagedArtifactStoreException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ManagedArtifactStoreException("Cannot verify Managed Executor S3 bucket", failure);
        }
    }

    private static void requireText(String value, String environmentVariable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing Managed Executor artifact store setting: "
                    + environmentVariable);
        }
    }
}

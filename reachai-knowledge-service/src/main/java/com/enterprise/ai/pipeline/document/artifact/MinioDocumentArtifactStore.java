package com.enterprise.ai.pipeline.document.artifact;

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

/** Production S3-compatible implementation, tested against MinIO. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reachai.knowledge.document-artifact-store", name = "type", havingValue = "s3")
public class MinioDocumentArtifactStore implements DocumentArtifactStore {

    private final DocumentArtifactProperties properties;
    private MinioClient client;
    private volatile boolean bucketReady;

    @PostConstruct
    void initialize() {
        requireText(properties.getEndpoint(), "REACHAI_KNOWLEDGE_ARTIFACT_S3_ENDPOINT");
        requireText(properties.getAccessKey(), "REACHAI_KNOWLEDGE_ARTIFACT_S3_ACCESS_KEY");
        requireText(properties.getSecretKey(), "REACHAI_KNOWLEDGE_ARTIFACT_S3_SECRET_KEY");
        requireText(properties.getBucket(), "REACHAI_KNOWLEDGE_ARTIFACT_S3_BUCKET");
        client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .region(properties.getRegion())
                .build();
        ensureBucket();
    }

    @Override
    public void put(String objectKey, InputStream content, long contentLength, String contentType) {
        if (contentLength < 0) {
            throw new DocumentArtifactException("S3 文档工件必须提供明确长度: " + objectKey);
        }
        try {
            ensureBucket();
            client.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .stream(content, contentLength, -1)
                    .contentType(contentType == null || contentType.isBlank()
                            ? "application/octet-stream" : contentType)
                    .build());
        } catch (DocumentArtifactException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentArtifactException("无法写入 S3 文档工件: " + objectKey, e);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new DocumentArtifactException("无法读取 S3 文档工件: " + objectKey, e);
        }
    }

    @Override
    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new DocumentArtifactException("无法删除 S3 文档工件: " + objectKey, e);
        }
    }

    private synchronized void ensureBucket() {
        if (bucketReady) {
            return;
        }
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build());
            if (!exists) {
                if (!properties.isAutoCreateBucket()) {
                    throw new DocumentArtifactException("S3 bucket 不存在: " + properties.getBucket());
                }
                try {
                    client.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
                } catch (Exception createFailure) {
                    // A second pod may have won the create race. Recheck before
                    // treating it as an initialization failure.
                    if (!client.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build())) {
                        throw createFailure;
                    }
                }
            }
            bucketReady = true;
        } catch (DocumentArtifactException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentArtifactException("无法确认 S3 文档工件 bucket: " + properties.getBucket(), e);
        }
    }

    private static void requireText(String value, String environmentVariable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少 S3 文档工件存储配置: " + environmentVariable);
        }
    }
}

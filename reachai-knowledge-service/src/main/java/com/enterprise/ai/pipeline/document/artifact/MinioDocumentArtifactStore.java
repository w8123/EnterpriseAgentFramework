package com.enterprise.ai.pipeline.document.artifact;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioAsyncClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.messages.Item;
import io.minio.messages.ListVersionsResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** S3-compatible implementation using the MinIO client. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reachai.knowledge.document-artifact-store", name = "type", havingValue = "s3")
public class MinioDocumentArtifactStore implements DocumentArtifactBackend {

    private static final int CLEANUP_PAGE_LIMIT = 8;
    private static final int CLEANUP_PAGE_SIZE = 100;
    private final DocumentArtifactProperties properties;
    private ArtifactS3Client client;
    private String storageId;
    private volatile boolean bucketReady;

    @PostConstruct
    void initialize() {
        requireText(properties.getEndpoint(), "REACHAI_KNOWLEDGE_ARTIFACT_S3_ENDPOINT");
        requireText(properties.getAccessKey(), "REACHAI_KNOWLEDGE_ARTIFACT_S3_ACCESS_KEY");
        requireText(properties.getSecretKey(), "REACHAI_KNOWLEDGE_ARTIFACT_S3_SECRET_KEY");
        requireText(properties.getBucket(), "REACHAI_KNOWLEDGE_ARTIFACT_S3_BUCKET");
        long timeout = TimeUnit.MINUTES.toMillis(5);
        // Preserve MinIO 8.5.17's default HTTP setup and timeouts, disabling retries for byte[]
        // multipart completion and DELETE as well as the SDK's existing streamed PUT safeguard.
        var http = io.minio.http.HttpUtils.newDefaultHttpClient(timeout, timeout, timeout)
                .newBuilder().retryOnConnectionFailure(false).build();
        client = new ArtifactS3Client(MinioAsyncClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .region(properties.getRegion())
                .httpClient(http, true)
                .build());
        storageId = DocumentArtifactIdentity.hash("s3\u0000" + properties.getEndpoint().trim() + "\u0000" + properties.getBucket());
        ensureBucket();
    }

    @Override
    public String storageId() {
        return storageId;
    }

    @Override
    public void put(String objectKey, InputStream content, long contentLength, String contentType) {
        if (contentLength < 0) {
            throw new DocumentArtifactException("S3 文档工件必须提供明确长度: " + objectKey);
        }
        try {
            ensureBucket();
            await(client.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .stream(content, contentLength, -1)
                    .contentType(contentType == null || contentType.isBlank()
                            ? "application/octet-stream" : contentType)
                    .build()));
        } catch (DocumentArtifactException e) {
            throw e;
        } catch (Exception e) {
            throw new DocumentArtifactException("无法写入 S3 文档工件: " + objectKey, e);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return await(client.getObject(GetObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build()));
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
            DocumentArtifactIdentity.key(objectKey);
            abortIncompleteWrites(objectKey);
            removeObjectVersions(objectKey);
        } catch (Exception e) {
            throw new DocumentArtifactException("无法删除 S3 文档工件: " + objectKey, e);
        }
    }

    private synchronized void ensureBucket() {
        if (bucketReady) {
            return;
        }
        try {
            boolean exists = await(client.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build()));
            if (!exists) {
                if (!properties.isAutoCreateBucket()) {
                    throw new DocumentArtifactException("S3 bucket 不存在: " + properties.getBucket());
                }
                try {
                    await(client.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build()));
                } catch (Exception createFailure) {
                    // A second pod may have won the create race. Recheck before
                    // treating it as an initialization failure.
                    if (!await(client.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build()))) {
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

    private void removeObjectVersions(String key) throws Exception {
        String keyMarker = null;
        String versionMarker = null;
        for (int page = 0; page < CLEANUP_PAGE_LIMIT; page++) {
            var result = client.objectVersions(properties.getBucket(), properties.getRegion(), key, keyMarker, versionMarker);
            boolean versionsRemoved = removeVersionEntries(key, result.contents());
            boolean markersRemoved = removeVersionEntries(key, result.deleteMarkers());
            if (versionsRemoved || markersRemoved) {
                // A cursor's version may have just been deleted. Start again and require a
                // subsequent listing to confirm absence instead of skipping older versions.
                keyMarker = null;
                versionMarker = null;
                continue;
            }
            if (!result.isTruncated()) return;
            if (result.nextKeyMarker() == null || result.nextKeyMarker().isEmpty()
                    || (Objects.equals(keyMarker, result.nextKeyMarker())
                    && Objects.equals(versionMarker, result.nextVersionIdMarker()))) {
                throw new DocumentArtifactException("S3 版本回收分页没有推进");
            }
            keyMarker = result.nextKeyMarker();
            versionMarker = result.nextVersionIdMarker();
        }
        throw new DocumentArtifactException("S3 版本回收尚未确认完成，保留下一轮重试");
    }

    private boolean removeVersionEntries(String key, List<? extends Item> entries) throws Exception {
        boolean removed = false;
        for (var entry : entries) {
            if (!key.equals(entry.objectName())) continue;
            String version = entry.versionId();
            if (version == null || version.isBlank()) {
                throw new DocumentArtifactException("S3 版本回收响应缺少版本身份");
            }
            // The literal "null" identifies an unversioned/suspended bucket's null version.
            // Omitting versionId would create a new delete marker in a versioned bucket.
            await(client.removeObject(RemoveObjectArgs.builder().bucket(properties.getBucket())
                    .object(key).versionId(version).build()));
            removed = true;
        }
        return removed;
    }

    private void abortIncompleteWrites(String key) throws Exception {
        String keyMarker = null;
        String uploadMarker = null;
        for (int page = 0; page < CLEANUP_PAGE_LIMIT; page++) {
            var result = client.incompleteUploads(properties.getBucket(), properties.getRegion(),
                    key, keyMarker, uploadMarker);
            for (var upload : result.uploads()) {
                if (key.equals(upload.objectName())) {
                    await(client.abortMultipartUploadAsync(properties.getBucket(), properties.getRegion(),
                            key, upload.uploadId(), null, null));
                }
            }
            if (!result.isTruncated()) return;
            if (Objects.equals(keyMarker, result.nextKeyMarker())
                    && Objects.equals(uploadMarker, result.nextUploadIdMarker())) {
                throw new DocumentArtifactException("S3 分片回收分页没有推进");
            }
            keyMarker = result.nextKeyMarker();
            uploadMarker = result.nextUploadIdMarker();
        }
        throw new DocumentArtifactException("S3 分片回收尚未完成，保留下一轮重试");
    }

    /** Exposes one SDK response so empty/truncated pages cannot trigger unbounded lazy iteration. */
    private static final class ArtifactS3Client extends MinioAsyncClient {
        private ArtifactS3Client(MinioAsyncClient client) { super(client); }

        private S3MultipartUploads incompleteUploads(String bucket, String region, String key,
                                                     String keyMarker, String uploadMarker) throws Exception {
            var query = newMultimap("uploads", "", "prefix", key,
                    "max-uploads", Integer.toString(CLEANUP_PAGE_SIZE));
            if (keyMarker != null) query.put("key-marker", keyMarker);
            if (uploadMarker != null) query.put("upload-id-marker", uploadMarker);
            // Keep SDK signing, region resolution, HTTP errors and timeout behavior. Its Upload DTO
            // rejects MinIO's empty StorageClass even though cleanup does not use that metadata.
            try (var response = await(executeGetAsync(BucketExistsArgs.builder()
                    .bucket(bucket).region(region).build(), null, query))) {
                if (response.body() == null) throw new DocumentArtifactException("S3 分片列表响应为空");
                var result = io.minio.Xml.unmarshal(S3MultipartUploads.class, response.body().string());
                result.validate(bucket);
                return result;
            }
        }

        private ListVersionsResult objectVersions(String bucket, String region, String key,
                                                  String keyMarker, String versionMarker) throws Exception {
            return await(listObjectVersionsAsync(bucket, region, null, null, keyMarker,
                    CLEANUP_PAGE_SIZE, key, versionMarker, null, null)).result();
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failure) {
            throw new DocumentArtifactException("S3 工件操作未确认完成", failure.getCause());
        }
    }

    @PreDestroy
    void close() throws Exception {
        if (client != null) client.close();
    }

    private static void requireText(String value, String environmentVariable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少 S3 文档工件存储配置: " + environmentVariable);
        }
    }
}

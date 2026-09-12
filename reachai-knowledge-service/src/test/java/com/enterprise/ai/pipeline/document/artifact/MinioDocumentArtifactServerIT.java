package com.enterprise.ai.pipeline.document.artifact;

import com.enterprise.ai.repository.DocumentArtifactLifecycleRepository;
import com.enterprise.ai.repository.DocumentImportJobRepository;
import com.enterprise.ai.repository.FileInfoRepository;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import io.minio.*;
import io.minio.messages.Item;
import io.minio.messages.Retention;
import io.minio.messages.RetentionMode;
import io.minio.messages.VersioningConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in integration test. The launcher owns an isolated loopback MinIO process and its data directory. */
@EnabledIfEnvironmentVariable(named = "REACHAI_ARTIFACT_IT_ENDPOINT", matches = "http://127\\.0\\.0\\.1:[0-9]+")
@Timeout(180)
class MinioDocumentArtifactServerIT {
    private static final String KEY = "knowledge-document-import/dij_12345678901234567890123456789012/source/中文 &+%";
    private String bucket;
    private MinioClient admin;
    private MinioDocumentArtifactStore backend;

    @BeforeEach
    void setUp() {
        String endpoint = System.getenv("REACHAI_ARTIFACT_IT_ENDPOINT");
        var address = URI.create(endpoint);
        assertEquals("127.0.0.1", address.getHost());
        assertEquals("http", address.getScheme());
        assertNull(address.getUserInfo());
        String owner = System.getenv("REACHAI_ARTIFACT_IT_OWNER");
        assertNotNull(owner);
        assertTrue(owner.matches("[a-f0-9]{32}"));
        bucket = "reachai-it-" + owner.substring(0, 12) + "-" + UUID.randomUUID().toString().replace("-", "");
        admin = MinioClient.builder().endpoint(endpoint).region("us-east-1")
                .credentials(System.getenv("REACHAI_ARTIFACT_IT_ACCESS"), System.getenv("REACHAI_ARTIFACT_IT_SECRET")).build();
    }

    private void create(boolean objectLock) throws Exception {
        admin.makeBucket(MakeBucketArgs.builder().bucket(bucket).objectLock(objectLock).build());
        var properties = new DocumentArtifactProperties();
        properties.setType("s3");
        properties.setEndpoint(System.getenv("REACHAI_ARTIFACT_IT_ENDPOINT"));
        properties.setAccessKey(System.getenv("REACHAI_ARTIFACT_IT_ACCESS"));
        properties.setSecretKey(System.getenv("REACHAI_ARTIFACT_IT_SECRET"));
        properties.setBucket(bucket);
        properties.setAutoCreateBucket(false);
        backend = new MinioDocumentArtifactStore(properties);
        backend.initialize();
    }

    private void versioning(VersioningConfiguration.Status status) throws Exception {
        admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(bucket)
                .config(new VersioningConfiguration(status, null)).build());
    }

    private void put(String key, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        backend.put(key, new ByteArrayInputStream(bytes), bytes.length, "text/plain; charset=UTF-8");
    }

    private String read(String key) throws Exception {
        try (var input = backend.open(key)) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private List<Item> versions(String key) throws Exception {
        var result = new ArrayList<Item>();
        for (var item : admin.listObjects(ListObjectsArgs.builder().bucket(bucket).prefix(key)
                .includeVersions(true).recursive(true).maxKeys(100).build())) {
            var entry = item.get();
            if (entry.objectName().equals(key)) result.add(entry);
        }
        return result;
    }

    @Test
    void unversionedStorageReadsChineseAndRemovesItsExplicitNullVersion() throws Exception {
        create(false);
        put(KEY, "未启用版本控制的正文");
        assertEquals("未启用版本控制的正文", read(KEY));
        assertEquals(1, versions(KEY).size());
        assertEquals("null", versions(KEY).get(0).versionId());
        backend.delete(KEY);
        backend.delete(KEY);
        assertTrue(versions(KEY).isEmpty());
        System.out.println("S3_SERVER_VERIFIED mode=unversioned ownedVersionsRemaining=0 repeatedCleanup=true chineseReadback=true");
    }

    @Test
    void versionedStorageRemovesBodiesAndMarkersButPreservesTheNeighbor() throws Exception {
        create(false);
        versioning(VersioningConfiguration.Status.ENABLED);
        put(KEY, "历史正文");
        put(KEY, "当前正文");
        assertEquals("当前正文", read(KEY));
        put(KEY + "-neighbor", "相邻对象必须保留");
        admin.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(KEY).build());
        assertEquals(3, versions(KEY).size());
        backend.delete(KEY);
        backend.delete(KEY);
        assertTrue(versions(KEY).isEmpty());
        assertEquals("相邻对象必须保留", read(KEY + "-neighbor"));
        assertEquals(1, versions(KEY + "-neighbor").size());
        System.out.println("S3_SERVER_VERIFIED mode=versioned ownedVersionsRemaining=0 neighborPreserved=true");
    }

    @Test
    void suspendedVersioningRemovesTheNullVersionAndEarlierHistory() throws Exception {
        create(false);
        versioning(VersioningConfiguration.Status.ENABLED);
        put(KEY, "启用期间的旧版本");
        versioning(VersioningConfiguration.Status.SUSPENDED);
        put(KEY, "暂停期间的 null 版本");
        assertEquals(2, versions(KEY).size());
        assertTrue(versions(KEY).stream().anyMatch(item -> "null".equals(item.versionId())));
        backend.delete(KEY);
        assertTrue(versions(KEY).isEmpty());
        assertEquals(VersioningConfiguration.Status.SUSPENDED,
                admin.getBucketVersioning(GetBucketVersioningArgs.builder().bucket(bucket).build()).status());
        System.out.println("S3_SERVER_VERIFIED mode=suspended ownedVersionsRemaining=0 bucketConfigurationPreserved=true");
    }

    @Test
    void actualPaginationDoesNotLoseHistoryAfterDeletingVersions() throws Exception {
        create(false);
        versioning(VersioningConfiguration.Status.ENABLED);
        for (int i = 0; i < 205; i++) put(KEY, "分页正文 " + i);
        assertEquals(205, versions(KEY).size());
        backend.delete(KEY);
        assertTrue(versions(KEY).isEmpty());
        System.out.println("S3_SERVER_VERIFIED mode=pagination initialVersions=205 ownedVersionsRemaining=0");
    }

    @Test
    void aRealVersionHistoryLargerThanTheAttemptBudgetFinishesOnRetry() throws Exception {
        create(false);
        versioning(VersioningConfiguration.Status.ENABLED);
        for (int i = 0; i < 905; i++) put(KEY, "分轮回收 " + i);
        assertEquals(905, versions(KEY).size());
        assertThrows(DocumentArtifactException.class, () -> backend.delete(KEY));
        assertEquals(105, versions(KEY).size());
        backend.delete(KEY);
        assertTrue(versions(KEY).isEmpty());
        System.out.println("S3_SERVER_VERIFIED mode=budget initialVersions=905 afterFirstAttempt=105 ownedVersionsRemaining=0");
    }

    @Test
    void aLockedVersionLeavesDurableRetryIntentUntilItIsRemoved() throws Exception {
        create(true);
        byte[] content = "受保留策略保护的正文".getBytes(StandardCharsets.UTF_8);
        var written = admin.putObject(PutObjectArgs.builder().bucket(bucket).object(KEY)
                .stream(new ByteArrayInputStream(content), content.length, -1)
                .retention(new Retention(RetentionMode.GOVERNANCE, ZonedDateTime.now(ZoneOffset.UTC).plusDays(1))).build());
        try (var db = new KnowledgeQueryTestDatabase(List.of("knowledge_document_artifact_lifecycle",
                "knowledge_document_import_job", "knowledge_file_info"), DocumentArtifactLifecycleRepository.class,
                DocumentImportJobRepository.class, FileInfoRepository.class)) {
            var repository = db.mapper(DocumentArtifactLifecycleRepository.class);
            var journal = new DocumentArtifactLifecycleStore(repository, db.mapper(DocumentImportJobRepository.class),
                    db.mapper(FileInfoRepository.class), new DataSourceTransactionManager(db.jdbc().getDataSource()));
            var reclaimer = new DocumentArtifactReclaimer(backend, repository, journal);
            String id = DocumentArtifactIdentity.artifact(backend.storageId(), KEY);
            journal.register(backend.storageId(), KEY);
            journal.acknowledge(backend.storageId(), KEY);
            journal.retire(backend.storageId(), KEY);
            reclaimer.reclaimPending();
            assertEquals("RECLAIMING", repository.selectById(id).getState());
            assertEquals("DocumentArtifactException", repository.selectById(id).getLastCleanupError());
            assertEquals(1, versions(KEY).size());
            assertEquals("受保留策略保护的正文", read(KEY));
            // Only the isolated fixture administrator removes this test-owned retained version.
            admin.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(KEY).versionId(written.versionId())
                    .bypassGovernanceMode(true).build());
            assertEquals(1, db.jdbc().update("UPDATE knowledge_document_artifact_lifecycle "
                    + "SET next_cleanup_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE artifact_id=?", id));
            reclaimer.reclaimPending();
            assertEquals("RECLAIMED", repository.selectById(id).getState());
            assertTrue(versions(KEY).isEmpty());
        }
        System.out.println("S3_SERVER_VERIFIED mode=governance actualRetentionRejected=true durableRetry=true ownedVersionsRemaining=0");
    }

    @AfterEach
    void cleanOnlyThisCaseBucket() throws Exception {
        try {
            if (admin != null && admin.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                var remaining = new ArrayList<Item>();
                for (var result : admin.listObjects(ListObjectsArgs.builder().bucket(bucket).includeVersions(true).recursive(true).build())) {
                    remaining.add(result.get());
                }
                for (var item : remaining) admin.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(item.objectName())
                        .versionId(item.versionId()).bypassGovernanceMode(true).build());
                admin.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
                assertFalse(admin.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()));
                System.out.println("S3_SERVER_CASE_CLEANUP bucketRemoved=true");
            }
        } finally {
            if (backend != null) backend.close();
            if (admin != null) admin.close();
        }
    }
}

package com.enterprise.ai.pipeline.document.artifact;

import com.enterprise.ai.repository.DocumentArtifactLifecycleRepository;
import com.enterprise.ai.repository.DocumentImportJobRepository;
import com.enterprise.ai.repository.FileInfoRepository;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.enterprise.ai.pipeline.document.artifact.S3ArtifactTestServer.respond;
import static org.junit.jupiter.api.Assertions.*;

/** Stateful S3 version protocol examples, not acceptance against a deployed object store. */
@Timeout(30)
class MinioDocumentArtifactVersionTest {
    private static final String KEY = "knowledge-document-import/dij_12345678901234567890123456789012/source/original";

    @Test
    void removesHistoryAndDeleteMarkersForOnlyTheOwnedKey() throws Exception {
        var bucket = new VersionBucket();
        bucket.add(KEY, "old-body", false);
        bucket.add(KEY, "new-body", false);
        bucket.add(KEY, "deleted", true);
        var neighbor = bucket.add(KEY + "-neighbor", "foreign-body", false);
        var neighborMarker = bucket.add(KEY + "-neighbor", "foreign-marker", true);
        try (var server = new S3ArtifactTestServer(bucket::handle)) {
            var backend = server.backend();
            try {
                backend.delete(KEY);
                assertEquals(List.of(neighbor, neighborMarker), bucket.entries);
                assertEquals(0, bucket.unversionedDeletes);
            } finally { backend.close(); }
        }
    }

    @Test
    void repeatedCleanupOfAnAbsentKeyDoesNotCreateDeleteMarkers() throws Exception {
        var bucket = new VersionBucket();
        try (var server = new S3ArtifactTestServer(bucket::handle)) {
            var backend = server.backend();
            try {
                backend.delete(KEY);
                backend.delete(KEY);
                assertTrue(bucket.entries.isEmpty(), "An absent object must stay absent, including delete markers");
                assertEquals(0, bucket.unversionedDeletes);
            } finally { backend.close(); }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitlyDeletesTheNullVersionWithOrWithoutEarlierHistory(boolean suspended) throws Exception {
        var bucket = new VersionBucket();
        bucket.versioned = suspended;
        bucket.add(KEY, "null", false);
        if (suspended) {
            bucket.add(KEY, "before-suspension", false);
            bucket.add(KEY, "old-delete-marker", true);
        }
        try (var server = new S3ArtifactTestServer(bucket::handle)) {
            var backend = server.backend();
            try {
                backend.delete(KEY);
                assertTrue(bucket.entries.isEmpty());
                assertTrue(server.requests.stream().anyMatch(request -> request.contains("versionId=null")));
                assertEquals(0, bucket.unversionedDeletes);
            } finally { backend.close(); }
        }
    }

    @Test
    void deletingAPageDoesNotSkipHistoryBehindTheNowDeletedCursorVersion() throws Exception {
        var bucket = new VersionBucket();
        for (int i = 0; i < 205; i++) bucket.add(KEY, "version-" + i, i % 3 == 0);
        try (var server = new S3ArtifactTestServer(bucket::handle)) {
            var backend = server.backend();
            try {
                backend.delete(KEY);
                assertTrue(bucket.entries.isEmpty());
                assertEquals(205, bucket.versionDeletes);
                assertEquals(4, bucket.versionLists);
                assertFalse(server.requests.stream().anyMatch(request -> request.contains("key-marker=")),
                        "A deleted version must not be reused as a listing cursor");
                assertTrue(bucket.requestedPageSizes.stream().allMatch(size -> size <= 100));
            } finally { backend.close(); }
        }
    }

    @Test
    void boundedCleanupRetainsProgressAndFinishesOnTheNextAttempt() throws Exception {
        var bucket = new VersionBucket();
        for (int i = 0; i < 905; i++) bucket.add(KEY, "version-" + i, i % 2 == 0);
        try (var server = new S3ArtifactTestServer(bucket::handle)) {
            var backend = server.backend();
            try {
                assertThrows(DocumentArtifactException.class, () -> backend.delete(KEY));
                assertEquals(8, bucket.versionLists);
                assertEquals(105, bucket.entries.size());
                backend.delete(KEY);
                assertTrue(bucket.entries.isEmpty());
                assertEquals(905, bucket.versionDeletes);
                assertEquals(11, bucket.versionLists);
            } finally { backend.close(); }
        }
    }

    @Test
    void listPermissionFailureRemainsRetryableWithoutCreatingAMarker() throws Exception {
        var bucket = new VersionBucket();
        bucket.add(KEY, "body", false);
        bucket.denyVersionList = true;
        try (var server = new S3ArtifactTestServer(bucket::handle)) {
            var backend = server.backend();
            try {
                assertThrows(DocumentArtifactException.class, () -> backend.delete(KEY));
                assertEquals(1, bucket.entries.size());
                assertEquals(0, bucket.versionDeletes + bucket.unversionedDeletes);
                bucket.denyVersionList = false;
                backend.delete(KEY);
                assertTrue(bucket.entries.isEmpty());
            } finally { backend.close(); }
        }
    }

    @Test
    void aRejectedVersionDeleteDoesNotHideUnfinishedHistory() throws Exception {
        var bucket = new VersionBucket();
        bucket.add(KEY, "removable", false);
        var locked = bucket.add(KEY, "locked", false);
        bucket.deniedVersion = "locked";
        try (var server = new S3ArtifactTestServer(bucket::handle)) {
            var backend = server.backend();
            try {
                assertThrows(DocumentArtifactException.class, () -> backend.delete(KEY));
                assertEquals(List.of(locked), bucket.entries);
                bucket.deniedVersion = null;
                backend.delete(KEY);
                assertTrue(bucket.entries.isEmpty());
                assertEquals(0, bucket.governanceBypasses);
            } finally { backend.close(); }
        }
    }

    @Test
    void missingVersionIdentityNeverFallsBackToAnUnversionedDelete() throws Exception {
        var bucket = new VersionBucket();
        bucket.add(KEY, null, false);
        try (var server = new S3ArtifactTestServer(bucket::handle)) {
            var backend = server.backend();
            try {
                assertThrows(DocumentArtifactException.class, () -> backend.delete(KEY));
                assertEquals(0, bucket.versionDeletes + bucket.unversionedDeletes);
                assertEquals(1, bucket.entries.size());
            } finally { backend.close(); }
        }
    }

    @Test
    void encodedKeysAndVersionIdentifiersRemainExact() throws Exception {
        String key = KEY + "/中文 &+%/source";
        var bucket = new VersionBucket();
        bucket.add(key, "版本 + /?&=%", false);
        var other = bucket.add(key + "-neighbor", "foreign", false);
        try (var server = new S3ArtifactTestServer(bucket::handle)) {
            var backend = server.backend();
            try {
                backend.delete(key);
                assertEquals(List.of(other), bucket.entries);
                assertEquals(1, bucket.versionDeletes);
                assertTrue(server.requests.stream().anyMatch(request -> request.contains("%E4%B8%AD%E6%96%87")));
            } finally { backend.close(); }
        }
    }

    @Test
    void aTruncatedForeignListingUsesBothMarkersWithoutDeletingNeighbors() throws Exception {
        var bucket = new VersionBucket();
        for (int i = 0; i < 205; i++) bucket.add(KEY + "-neighbor", "foreign-" + i, i % 2 == 0);
        try (var server = new S3ArtifactTestServer(bucket::handle)) {
            var backend = server.backend();
            try {
                backend.delete(KEY);
                assertEquals(205, bucket.entries.size());
                assertEquals(0, bucket.versionDeletes + bucket.unversionedDeletes);
                assertEquals(3, bucket.versionLists);
                assertTrue(server.requests.stream().anyMatch(request -> request.contains("version-id-marker=")));
            } finally { backend.close(); }
        }
    }

    @Test
    void anUnchangingRemoteListingCannotBeReportedAsComplete() throws Exception {
        var bucket = new VersionBucket();
        bucket.add(KEY, "persistent", false);
        bucket.keepDeletedVersions = true;
        try (var server = new S3ArtifactTestServer(bucket::handle)) {
            var backend = server.backend();
            try {
                assertThrows(DocumentArtifactException.class, () -> backend.delete(KEY));
                assertEquals(8, bucket.versionLists);
                assertEquals(1, bucket.entries.size());
            } finally { backend.close(); }
        }
    }

    @Test
    void truncatedListingWithoutAnAdvancingCursorFailsWithinTheBudget() throws Exception {
        var bucket = new VersionBucket();
        bucket.brokenPagination = true;
        try (var server = new S3ArtifactTestServer(bucket::handle)) {
            var backend = server.backend();
            try {
                assertThrows(DocumentArtifactException.class, () -> backend.delete(KEY));
                assertTrue(bucket.versionLists >= 1 && bucket.versionLists <= 8);
                assertEquals(0, bucket.versionDeletes + bucket.unversionedDeletes);
            } finally { backend.close(); }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"list-denied", "delete-denied", "page-budget"})
    void persistentReclaimerOnlyClosesAnAcknowledgedArtifactAfterVerifiedRemoval(String failure) throws Exception {
        var bucket = new VersionBucket();
        int count = failure.equals("page-budget") ? 905 : 1;
        for (int i = 0; i < count; i++) bucket.add(KEY, "version-" + i, i % 2 == 0);
        bucket.denyVersionList = failure.equals("list-denied");
        bucket.deniedVersion = failure.equals("delete-denied") ? "version-0" : null;
        try (var server = new S3ArtifactTestServer(bucket::handle);
             var db = new KnowledgeQueryTestDatabase(List.of("knowledge_document_artifact_lifecycle",
                     "knowledge_document_import_job", "knowledge_file_info"), DocumentArtifactLifecycleRepository.class,
                     DocumentImportJobRepository.class, FileInfoRepository.class)) {
            var backend = server.backend();
            try {
                var repository = db.mapper(DocumentArtifactLifecycleRepository.class);
                var journal = new DocumentArtifactLifecycleStore(repository, db.mapper(DocumentImportJobRepository.class),
                        db.mapper(FileInfoRepository.class), new DataSourceTransactionManager(db.jdbc().getDataSource()));
                var reclaimer = new DocumentArtifactReclaimer(backend, repository, journal);
                String id = DocumentArtifactIdentity.artifact(backend.storageId(), KEY);
                journal.register(backend.storageId(), KEY);
                journal.acknowledge(backend.storageId(), KEY);
                journal.retire(backend.storageId(), KEY);

                reclaimer.reclaimPending();
                var pending = repository.selectById(id);
                assertEquals("RECLAIMING", pending.getState());
                assertEquals("DocumentArtifactException", pending.getLastCleanupError());
                assertNull(pending.getCleanupLeaseOwner());
                assertFalse(bucket.entries.isEmpty());

                bucket.denyVersionList = false;
                bucket.deniedVersion = null;
                assertEquals(1, db.jdbc().update("UPDATE knowledge_document_artifact_lifecycle "
                        + "SET next_cleanup_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE artifact_id=?", id));
                reclaimer.reclaimPending();
                assertEquals("RECLAIMED", repository.selectById(id).getState());
                assertNull(repository.selectById(id).getLastCleanupError());
                assertTrue(bucket.entries.isEmpty());
                assertEquals(0, bucket.unversionedDeletes);
            } finally { backend.close(); }
        }
    }

    private record Entry(String key, String version, boolean marker) {
        String xml() {
            String tag = marker ? "DeleteMarker" : "Version";
            return "<" + tag + "><Key>" + escape(key) + "</Key>"
                    + (version == null ? "" : "<VersionId>" + escape(version) + "</VersionId>")
                    + "<IsLatest>false</IsLatest><LastModified>2026-09-09T00:00:00.000Z</LastModified>"
                    + (marker ? "" : "<ETag>&quot;fixture&quot;</ETag><Size>1</Size><StorageClass>STANDARD</StorageClass>")
                    + "</" + tag + ">";
        }
    }

    /** Models permanent version deletion and the delete-marker side effect of a plain DELETE. */
    private static final class VersionBucket {
        final List<Entry> entries = new CopyOnWriteArrayList<>();
        final List<Integer> requestedPageSizes = new CopyOnWriteArrayList<>();
        volatile boolean versioned = true, denyVersionList, keepDeletedVersions, brokenPagination;
        volatile String deniedVersion;
        volatile int unversionedDeletes, versionDeletes, versionLists, governanceBypasses;

        Entry add(String key, String version, boolean marker) {
            var entry = new Entry(key, version, marker);
            entries.add(entry);
            return entry;
        }

        void handle(HttpExchange exchange, List<String> requests) throws Exception {
            var query = query(exchange);
            String method = exchange.getRequestMethod();
            if (method.equals("HEAD")) respond(exchange, 200, "");
            else if (method.equals("GET") && query.containsKey("uploads")) {
                respond(exchange, 200, "<ListMultipartUploadsResult><Bucket>artifact-fixture</Bucket>"
                        + "<MaxUploads>100</MaxUploads><IsTruncated>false</IsTruncated></ListMultipartUploadsResult>");
            } else if (method.equals("GET") && query.containsKey("versions")) {
                versionLists++;
                if (denyVersionList) { reject(exchange); return; }
                int max = Integer.parseInt(query.getOrDefault("max-keys", "1000"));
                requestedPageSizes.add(max);
                var remaining = new ArrayList<>(entries.stream()
                        .filter(entry -> entry.key().startsWith(query.getOrDefault("prefix", "")))
                        .sorted(Comparator.comparing(Entry::key)).toList());
                String keyMarker = query.get("key-marker"), versionMarker = query.get("version-id-marker");
                if (keyMarker != null) {
                    int cursor = -1;
                    for (int i = 0; i < remaining.size(); i++) {
                        if (remaining.get(i).key().equals(keyMarker) && remaining.get(i).version().equals(versionMarker)) cursor = i;
                    }
                    if (cursor >= 0) remaining.subList(0, cursor + 1).clear();
                    else remaining.removeIf(entry -> entry.key().compareTo(keyMarker) <= 0);
                }
                boolean truncated = brokenPagination || remaining.size() > max;
                var page = remaining.subList(0, Math.min(max, remaining.size()));
                String next = "";
                if (truncated && !page.isEmpty()) {
                    var last = page.get(page.size() - 1);
                    next = "<NextKeyMarker>" + escape(last.key()) + "</NextKeyMarker><NextVersionIdMarker>"
                            + escape(last.version()) + "</NextVersionIdMarker>";
                }
                String contents = page.stream().map(Entry::xml).reduce("", String::concat);
                respond(exchange, 200, "<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                        + "<Name>artifact-fixture</Name><Prefix>" + escape(query.getOrDefault("prefix", ""))
                        + "</Prefix><MaxKeys>" + max + "</MaxKeys><IsTruncated>" + truncated
                        + "</IsTruncated>" + next + contents + "</ListVersionsResult>");
            } else if (method.equals("DELETE")) {
                if ("true".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("X-Amz-Bypass-Governance-Retention"))) {
                    governanceBypasses++;
                }
                String key = exchange.getRequestURI().getPath().substring("/artifact-fixture/".length());
                if (query.containsKey("versionId")) {
                    String version = query.get("versionId");
                    if (version.equals(deniedVersion)) { reject(exchange); return; }
                    versionDeletes++;
                    if (!keepDeletedVersions) entries.removeIf(entry -> entry.key().equals(key) && version.equals(entry.version()));
                } else {
                    unversionedDeletes++;
                    if (versioned) add(key, "generated-marker-" + unversionedDeletes, true);
                    else entries.removeIf(entry -> entry.key().equals(key));
                }
                respond(exchange, 204, "");
            } else respond(exchange, 500, "<Error><Code>InternalError</Code><Message>Unexpected fixture request</Message></Error>");
        }

        private static void reject(HttpExchange exchange) throws Exception {
            respond(exchange, 403, "<Error><Code>AccessDenied</Code><Message>controlled rejection</Message>"
                    + "<RequestId>fixture</RequestId><HostId>fixture</HostId></Error>");
        }
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> result = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null) for (String pair : raw.split("&")) {
            String[] part = pair.split("=", 2);
            result.put(URLDecoder.decode(part[0], StandardCharsets.UTF_8),
                    part.length == 1 ? "" : URLDecoder.decode(part[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

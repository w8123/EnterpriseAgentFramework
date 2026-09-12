package com.enterprise.ai.pipeline.document.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static com.enterprise.ai.pipeline.document.artifact.S3ArtifactTestServer.respond;
import static org.junit.jupiter.api.Assertions.*;

/** Real MinIO SDK HTTP calls against a controlled S3 protocol server; not a deployed S3 acceptance test. */
@Timeout(15)
class MinioDocumentArtifactBackendTest {
    private static final String KEY = "knowledge-document-import/dij_12345678901234567890123456789012/source/original";

    @Test
    void cleanupAcceptsEmptyStorageMetadataReturnedByMinio() throws Exception {
        try (var server = new S3ArtifactTestServer((exchange, requests) -> {
            if (exchange.getRequestMethod().equals("GET")) {
                String response = exchange.getRequestURI().getRawQuery().contains("versions") ? emptyVersions()
                        : listing(upload(KEY, "owned-upload") + upload(KEY + "-other", "foreign-upload"), false)
                        .replace("<StorageClass>STANDARD</StorageClass>", "<StorageClass></StorageClass>")
                        .replace("<ID>fixture</ID>", "<ID></ID>")
                        .replace("<DisplayName>fixture</DisplayName>", "<DisplayName></DisplayName>");
                respond(exchange, 200, response);
            } else respond(exchange, exchange.getRequestMethod().equals("HEAD") ? 200 : 204, "");
        })) {
            var backend = server.backend();
            try {
                backend.delete(KEY);
                assertEquals(1, server.requests.stream().filter(request -> request.startsWith("DELETE ")).count());
                assertTrue(server.requests.stream().anyMatch(request -> request.contains("uploadId=owned-upload")));
                assertFalse(server.requests.stream().anyMatch(request -> request.contains("uploadId=foreign-upload")));
            } finally { backend.close(); }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-upload-id", "missing-key", "wrong-bucket", "missing-truncation"})
    void invalidMultipartIdentityOrCompletenessNeverPermitsDeletion(String fault) throws Exception {
        String valid = listing(upload(KEY, "owned-upload"), false);
        String invalid = switch (fault) {
            case "missing-upload-id" -> valid.replace("<UploadId>owned-upload</UploadId>", "<UploadId></UploadId>");
            case "missing-key" -> valid.replace("<Key>" + KEY + "</Key>", "<Key></Key>");
            case "wrong-bucket" -> valid.replace("<Bucket>artifact-fixture</Bucket>", "<Bucket>another-bucket</Bucket>");
            default -> valid.replace("<IsTruncated>false</IsTruncated>", "");
        };
        try (var server = new S3ArtifactTestServer((exchange, requests) -> {
            respond(exchange, 200, exchange.getRequestMethod().equals("GET") ? invalid : "");
        })) {
            var backend = server.backend();
            try {
                assertThrows(DocumentArtifactException.class, () -> backend.delete(KEY));
                assertFalse(server.requests.stream().anyMatch(request -> request.startsWith("DELETE ")));
            } finally { backend.close(); }
        }
    }

    @Test
    void cleanupAbortsOnlyMultipartUploadsForTheExactObjectKey() throws Exception {
        try (var server = new S3ArtifactTestServer((exchange, requests) -> {
            if (exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 200, exchange.getRequestURI().getRawQuery().contains("versions") ? emptyVersions()
                        : listing(upload(KEY, "owned-upload") + upload(KEY + "-other", "foreign-upload"), false));
            } else respond(exchange, exchange.getRequestMethod().equals("HEAD") ? 200 : 204, "");
        })) {
            var backend = server.backend();
            try {
                backend.delete(KEY);
                assertTrue(server.requests.stream().anyMatch(request -> request.contains("uploadId=owned-upload")));
                assertFalse(server.requests.stream().anyMatch(request -> request.contains("uploadId=foreign-upload")));
                assertEquals(1, server.requests.stream().filter(request -> request.startsWith("DELETE ")).count());
            } finally { backend.close(); }
        }
    }

    @Test
    void aDisconnectedObjectWriteIsNotAutomaticallyRetried() throws Exception {
        try (var server = new S3ArtifactTestServer((exchange, requests) -> {
            if (exchange.getRequestMethod().equals("PUT")) {
                exchange.getRequestBody().readAllBytes();
                exchange.close();
            } else respond(exchange, 200, "");
        })) {
            var backend = server.backend();
            try {
                byte[] content = "SDK 写入断连".getBytes(StandardCharsets.UTF_8);
                assertThrows(DocumentArtifactException.class,
                        () -> backend.put(KEY, new ByteArrayInputStream(content), content.length, "text/plain"));
                assertEquals(1, server.requests.stream().filter(request -> request.startsWith("PUT ")).count());
            } finally { backend.close(); }
        }
    }

    @Test
    void incompleteMultipartPaginationCannotBeReportedAsSuccessfulCleanup() throws Exception {
        try (var server = new S3ArtifactTestServer((exchange, requests) -> {
            if (exchange.getRequestMethod().equals("GET")) respond(exchange, 200, listing("", true));
            else respond(exchange, exchange.getRequestMethod().equals("HEAD") ? 200 : 204, "");
        })) {
            var backend = server.backend();
            try {
                assertThrows(DocumentArtifactException.class, () -> backend.delete(KEY));
                long pages = server.requests.stream().filter(request -> request.startsWith("GET ")).count();
                assertTrue(pages >= 1 && pages <= 8);
            } finally { backend.close(); }
        }
    }

    @Test
    void authorizationFailureDoesNotBecomeAnAbsentObjectSuccess() throws Exception {
        try (var server = new S3ArtifactTestServer((exchange, requests) -> {
            if (exchange.getRequestMethod().equals("DELETE")) respond(exchange, 403,
                    "<Error><Code>AccessDenied</Code><Message>controlled rejection</Message><RequestId>fixture</RequestId><HostId>fixture</HostId></Error>");
            else if (exchange.getRequestMethod().equals("GET")) respond(exchange, 200, listing(upload(KEY, "owned-upload"), false));
            else respond(exchange, 200, "");
        })) {
            var backend = server.backend();
            try {
                assertThrows(DocumentArtifactException.class, () -> backend.delete(KEY));
                assertEquals(1, server.requests.stream().filter(request -> request.startsWith("DELETE ")).count());
                assertEquals(1, server.requests.stream().filter(request -> request.startsWith("GET ")).count());
            } finally { backend.close(); }
        }
    }

    private static String emptyVersions() {
        return "<ListVersionsResult><Name>artifact-fixture</Name><MaxKeys>100</MaxKeys>"
                + "<IsTruncated>false</IsTruncated></ListVersionsResult>";
    }

    private static String listing(String uploads, boolean truncated) {
        return """
                <ListMultipartUploadsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Bucket>artifact-fixture</Bucket><KeyMarker></KeyMarker><UploadIdMarker></UploadIdMarker>
                  <NextKeyMarker></NextKeyMarker><NextUploadIdMarker></NextUploadIdMarker>
                  <Delimiter></Delimiter><Prefix>%s</Prefix><MaxUploads>100</MaxUploads><IsTruncated>%s</IsTruncated>
                  %s
                </ListMultipartUploadsResult>
                """.formatted(KEY, truncated, uploads);
    }

    private static String upload(String key, String id) {
        return """
                <Upload><Key>%s</Key><UploadId>%s</UploadId>
                  <Initiator><ID>fixture</ID><DisplayName>fixture</DisplayName></Initiator>
                  <Owner><ID>fixture</ID><DisplayName>fixture</DisplayName></Owner>
                  <StorageClass>STANDARD</StorageClass><Initiated>2026-09-08T00:00:00.000Z</Initiated>
                </Upload>
                """.formatted(key, id);
    }

}

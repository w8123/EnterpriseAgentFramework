package com.enterprise.ai.control.knowledge;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Exact-byte signed Control BFF client for Knowledge business-index console APIs. */
@org.springframework.stereotype.Component
public class KnowledgeBizIndexGateway {

    static final String INTERNAL_ROOT = "/internal/knowledge/console/biz-index";
    static final String PROJECT_INTERNAL_ROOT = "/internal/knowledge/project-ingress/projects";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final InternalServiceAuthSigner signer;
    private final HttpClient httpClient;
    private final String knowledgeServiceUrl;
    private final Path temporaryDirectory;
    private final long maxRequestBytes;
    private final int maxResponseBytes;

    @Autowired
    public KnowledgeBizIndexGateway(
            InternalServiceAuthSigner signer,
            @Value("${services.knowledge-service.url:http://localhost:18602}") String knowledgeServiceUrl,
            @Value("${reachai.knowledge.console-ingress.max-body-bytes:104857600}") long maxRequestBytes,
            @Value("${reachai.knowledge.console-ingress.max-response-bytes:16777216}") int maxResponseBytes) {
        this(signer,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                knowledgeServiceUrl,
                Path.of(System.getProperty("java.io.tmpdir", ".")),
                maxRequestBytes,
                maxResponseBytes);
    }

    KnowledgeBizIndexGateway(InternalServiceAuthSigner signer,
                             HttpClient httpClient,
                             String knowledgeServiceUrl,
                             Path temporaryDirectory,
                             long maxRequestBytes,
                             int maxResponseBytes) {
        this.signer = signer;
        this.httpClient = httpClient;
        this.knowledgeServiceUrl = normalizeBaseUrl(knowledgeServiceUrl);
        this.temporaryDirectory = temporaryDirectory;
        this.maxRequestBytes = Math.max(0, maxRequestBytes);
        this.maxResponseBytes = Math.max(1, Math.min(Integer.MAX_VALUE - 1, maxResponseBytes));
    }

    GatewayResponse exchange(String method,
                             String internalPath,
                             String tenantId,
                             String actorId,
                             byte[] body,
                             String contentType) {
        byte[] payload = body == null ? new byte[0] : body;
        if (payload.length > maxRequestBytes) {
            throw tooLarge();
        }
        Map<String, String> signed = signer.sign(
                method,
                requireInternalPath(internalPath),
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                tenantId,
                actorId,
                payload);
        HttpRequest.BodyPublisher publisher = payload.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(payload);
        return send(method, internalPath, signed, publisher, contentType, DEFAULT_TIMEOUT);
    }

    GatewayResponse exchangeProject(String method,
                                    String internalPath,
                                    String projectCode,
                                    String credentialActorId,
                                    byte[] body,
                                    String contentType) {
        byte[] payload = body == null ? new byte[0] : body;
        if (payload.length > maxRequestBytes) {
            throw tooLarge();
        }
        String path = requireProjectInternalPath(internalPath, projectCode);
        Map<String, String> signed = signer.sign(
                method,
                path,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL,
                projectCode,
                credentialActorId,
                payload);
        HttpRequest.BodyPublisher publisher = payload.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(payload);
        return send(method, path, signed, publisher, contentType, DEFAULT_TIMEOUT);
    }

    GatewayResponse exchangeMultipart(String internalPath,
                                      String tenantId,
                                      String actorId,
                                      String dataJson,
                                      List<MultipartFile> attachments) {
        String path = requireInternalPath(internalPath);
        String boundary = "ReachAI-" + UUID.randomUUID();
        Path bodyFile = null;
        try {
            Files.createDirectories(temporaryDirectory);
            bodyFile = Files.createTempFile(temporaryDirectory, "reachai-knowledge-proxy-", ".multipart");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream fileOutput = Files.newOutputStream(bodyFile);
                 BoundedOutputStream bounded = new BoundedOutputStream(fileOutput, maxRequestBytes);
                 DigestOutputStream output = new DigestOutputStream(bounded, digest)) {
                writePartHeader(output, boundary, "data", null, "application/json; charset=UTF-8");
                output.write(requiredDataJson(dataJson).getBytes(StandardCharsets.UTF_8));
                output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                if (attachments != null) {
                    for (MultipartFile attachment : attachments) {
                        if (attachment == null || attachment.isEmpty()) {
                            continue;
                        }
                        writePartHeader(output, boundary, "attachments",
                                safeFilename(attachment.getOriginalFilename()),
                                safeContentType(attachment.getContentType()));
                        try (InputStream input = attachment.getInputStream()) {
                            input.transferTo(output);
                        }
                        output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    }
                }
                output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
            }
            String bodySha256 = HexFormat.of().formatHex(digest.digest());
            Map<String, String> signed = signer.signBodyDigest(
                    "POST",
                    path,
                    InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                    tenantId,
                    actorId,
                    bodySha256);
            return send("POST", path, signed, HttpRequest.BodyPublishers.ofFile(bodyFile),
                    "multipart/form-data; boundary=" + boundary, DEFAULT_TIMEOUT);
        } catch (BoundedOutputStream.LimitExceededException tooLarge) {
            throw tooLarge();
        } catch (ResponseStatusException expected) {
            throw expected;
        } catch (Exception failure) {
            throw unavailable("Knowledge business-index multipart request failed", failure);
        } finally {
            if (bodyFile != null) {
                try {
                    Files.deleteIfExists(bodyFile);
                } catch (IOException ignored) {
                    // The request already completed; an ops sweep can remove a file locked by a failed client.
                }
            }
        }
    }

    private GatewayResponse send(String method,
                                 String internalPath,
                                 Map<String, String> signedHeaders,
                                 HttpRequest.BodyPublisher body,
                                 String contentType,
                                 Duration timeout) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(knowledgeServiceUrl + "/ai" + requireAllowedInternalPath(internalPath)))
                    .timeout(timeout)
                    .header("Accept", "application/json");
            if (StringUtils.hasText(contentType)) {
                builder.header("Content-Type", contentType);
            }
            signedHeaders.forEach(builder::header);
            HttpResponse<InputStream> response = httpClient.send(
                    builder.method(method, body).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBody;
            try (InputStream input = response.body()) {
                responseBody = input == null ? new byte[0] : input.readNBytes(maxResponseBytes + 1);
            }
            if (responseBody.length > maxResponseBytes) {
                throw unavailable("Knowledge business-index response exceeded the safety limit", null);
            }
            return new GatewayResponse(
                    response.statusCode(),
                    response.headers().firstValue("Content-Type")
                            .filter(KnowledgeBizIndexGateway::isSafeResponseContentType)
                            .orElse("application/json"),
                    responseBody);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable("Knowledge business-index request was interrupted", interrupted);
        } catch (IOException | IllegalArgumentException failure) {
            throw unavailable("Knowledge business-index service is unavailable", failure);
        }
    }

    private static void writePartHeader(OutputStream output,
                                        String boundary,
                                        String name,
                                        String filename,
                                        String contentType) throws IOException {
        StringBuilder header = new StringBuilder()
                .append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"")
                .append(name).append('"');
        if (filename != null) {
            header.append("; filename=\"").append(escapeQuoted(filename)).append('"');
        }
        header.append("\r\nContent-Type: ").append(contentType).append("\r\n\r\n");
        output.write(header.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static String requiredDataJson(String value) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "business-index data part is required");
        }
        return value;
    }

    private static String safeFilename(String value) {
        String filename = StringUtils.hasText(value) ? value.trim() : "attachment.bin";
        filename = filename.replace('\\', '/');
        int slash = filename.lastIndexOf('/');
        if (slash >= 0) {
            filename = filename.substring(slash + 1);
        }
        filename = filename.replaceAll("[\\r\\n\\u0000-\\u001f\\u007f]", "_");
        if (filename.isBlank()) {
            filename = "attachment.bin";
        }
        return filename.length() > 255 ? filename.substring(0, 255) : filename;
    }

    private static String safeContentType(String value) {
        if (!StringUtils.hasText(value) || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || !value.trim().matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")) {
            return "application/octet-stream";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String escapeQuoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String requireInternalPath(String value) {
        String path = value == null ? "" : value.trim();
        if (!(INTERNAL_ROOT.equals(path) || path.startsWith(INTERNAL_ROOT + "/"))
                || path.contains("?") || path.contains("#") || path.contains("..")) {
            throw new IllegalArgumentException("invalid Knowledge business-index internal path");
        }
        return path;
    }

    private static String requireProjectInternalPath(String value, String projectCode) {
        String path = value == null ? "" : value.trim();
        String project = projectCode == null ? "" : projectCode.trim();
        String expectedPrefix = PROJECT_INTERNAL_ROOT + "/" + project + "/biz-index/";
        if (project.isBlank() || !path.startsWith(expectedPrefix)
                || path.contains("?") || path.contains("#") || path.contains("..")) {
            throw new IllegalArgumentException("invalid Knowledge project-ingress internal path");
        }
        return path;
    }

    private static String requireAllowedInternalPath(String value) {
        String path = value == null ? "" : value.trim();
        if (INTERNAL_ROOT.equals(path) || path.startsWith(INTERNAL_ROOT + "/")) {
            return requireInternalPath(path);
        }
        if (path.startsWith(PROJECT_INTERNAL_ROOT + "/")
                && !path.contains("?") && !path.contains("#") && !path.contains("..")) {
            return path;
        }
        throw new IllegalArgumentException("invalid Knowledge business-index internal path");
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!StringUtils.hasText(normalized)) {
            normalized = "http://localhost:18602";
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/ai")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    private static boolean isSafeResponseContentType(String value) {
        if (value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.startsWith("application/json")
                || normalized.startsWith("application/problem+json")
                || normalized.startsWith("text/plain");
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Knowledge business-index request exceeds the configured limit");
    }

    private static ResponseStatusException unavailable(String message, Throwable cause) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message, cause);
    }

    record GatewayResponse(int status, String contentType, byte[] body) {
    }

    private static final class BoundedOutputStream extends FilterOutputStream {
        private final long limit;
        private long written;

        private BoundedOutputStream(OutputStream output, long limit) {
            super(output);
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            out.write(value);
            written++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            requireCapacity(length);
            out.write(bytes, offset, length);
            written += length;
        }

        private void requireCapacity(long requested) throws LimitExceededException {
            if (requested < 0 || written > limit - requested) {
                throw new LimitExceededException();
            }
        }

        private static final class LimitExceededException extends IOException {
        }
    }
}

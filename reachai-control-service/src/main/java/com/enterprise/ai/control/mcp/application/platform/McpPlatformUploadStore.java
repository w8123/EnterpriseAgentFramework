package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Files a platform AI connection sends out of band, because the MCP body cap keeps them out of tool arguments. An
 * upload belongs to the connection that sent it, expires after an hour and is consumed by the one tool call that uses
 * it; tools that only inspect it leave it in place.
 */
@Service
public class McpPlatformUploadStore {
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final int MAX_PENDING_PER_CONNECTION = 5;
    private static final long TTL_SECONDS = 3600;
    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();
    private final Path directory;
    private final Clock clock;

    public McpPlatformUploadStore() {
        this(Path.of(System.getProperty("java.io.tmpdir"), "reachai-platform-ai-uploads"), Clock.systemUTC());
    }

    McpPlatformUploadStore(Path directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
    }

    public record Upload(String uploadId, long connectionId, String fileName, long size, String sha256, Instant expiresAt, Path path) {
        public Map<String, Object> view() {
            return Map.of("uploadId", uploadId, "fileName", fileName, "size", size, "sha256", sha256, "expiresAt", expiresAt.toString());
        }
    }

    public Upload put(ConnectionIdentity owner, String fileName, InputStream content) {
        String name = safeName(fileName);
        evictExpired();
        if (uploads.values().stream().filter(upload -> upload.connectionId() == owner.connectionId()).count() >= MAX_PENDING_PER_CONNECTION) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "too many pending uploads");
        }
        String id = "upl_" + UUID.randomUUID().toString().replace("-", "");
        Path target = directory.resolve(id);
        try {
            Files.createDirectories(directory);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (OutputStream out = new DigestOutputStream(Files.newOutputStream(target), digest)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = content.read(buffer)) != -1) {
                    size += read;
                    if (size > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "upload exceeds 20MB");
                    out.write(buffer, 0, read);
                }
            }
            if (size == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "upload is empty");
            Upload upload = new Upload(id, owner.connectionId(), name, size, HexFormat.of().formatHex(digest.digest()),
                    clock.instant().plusSeconds(TTL_SECONDS), target);
            uploads.put(id, upload);
            return upload;
        } catch (ResponseStatusException refused) {
            delete(target);
            throw refused;
        } catch (IOException | NoSuchAlgorithmException failure) {
            delete(target);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "upload could not be stored");
        }
    }

    /** The upload's bytes, only for the connection that sent it; with consume the upload is removed first, so it runs once. */
    public byte[] read(ConnectionIdentity owner, String uploadId, boolean consume) {
        Upload upload = uploadId == null ? null : uploads.get(uploadId);
        if (upload == null || upload.connectionId() != owner.connectionId() || clock.instant().isAfter(upload.expiresAt())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "upload not found or expired");
        }
        if (consume && !uploads.remove(uploadId, upload)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "upload already used");
        try { return Files.readAllBytes(upload.path()); }
        catch (IOException failure) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "upload not found or expired"); }
        finally { if (consume) delete(upload.path()); }
    }

    public Upload describe(ConnectionIdentity owner, String uploadId) {
        Upload upload = uploadId == null ? null : uploads.get(uploadId);
        if (upload == null || upload.connectionId() != owner.connectionId() || clock.instant().isAfter(upload.expiresAt())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "upload not found or expired");
        }
        return upload;
    }

    private void evictExpired() {
        Instant now = clock.instant();
        uploads.values().removeIf(upload -> {
            if (!now.isAfter(upload.expiresAt())) return false;
            delete(upload.path());
            return true;
        });
    }

    private static String safeName(String fileName) {
        String name = fileName == null ? "" : fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || name.length() > 200 || name.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a file name is required");
        }
        return name;
    }

    private static void delete(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }
}

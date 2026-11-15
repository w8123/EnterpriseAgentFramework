package com.enterprise.ai.control.aiassist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;

/** A Composer artifact archive served by Control; this endpoint is not a Composer/Packagist repository. */
public final class ControlPhpSdkArtifactSupport {
    public static final String PACKAGE_NAME = "reachai/php-sdk";
    public static final String VERSION = "1.0.0";
    public static final String FILE_NAME = "reachai-php-sdk-" + VERSION + ".zip";
    private static final String RESOURCE = "ai-assist/artifacts/php-sdk/" + FILE_NAME;

    private ControlPhpSdkArtifactSupport() { }

    public static String downloadUrl(String baseUrl) {
        String root = StringUtils.hasText(baseUrl) ? baseUrl.replaceAll("/+$", "") : "";
        return root + "/api/ai-assist/artifacts/php-sdk/" + FILE_NAME;
    }

    public static String integritySha256() {
        try {
            byte[] bytes = loadVerified();
            return bytes == null ? null : sha256(bytes);
        } catch (IOException ex) { return null; }
    }

    public static ControlAiAssistProjectController.SdkArtifact composerArtifact(String baseUrl) {
        String command = "composer config repositories.reachai artifact ./third_party/reachai && composer require "
                + PACKAGE_NAME + ":" + VERSION;
        return new ControlAiAssistProjectController.SdkArtifact(
                "php", "php", PACKAGE_NAME + ":" + VERSION, null, null, PACKAGE_NAME,
                VERSION, "platform-artifact-download", List.of(), command,
                "Download the PHP SDK zip and verify integritySha256. Keep the zip unextracted in the business project's "
                        + "third_party/reachai directory and install it through a Composer artifact repository. "
                        + "Projects without Composer may extract it and require reachai-php/autoload.php. "
                        + "The download endpoint is not a Packagist repository.",
                "composer-artifact-zip", downloadUrl(baseUrl), integritySha256(), command, "fail-closed",
                List.of(FILE_NAME), null, "business-backend-php-project", command, null, null);
    }

    public static ResponseEntity<byte[]> packageResponse(String filename, boolean digestOnly) throws IOException {
        if (!StringUtils.hasText(filename) || !filename.matches("^[A-Za-z0-9._-]+$")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (!FILE_NAME.equals(filename)) return ResponseEntity.notFound().build();
        byte[] bytes = loadVerified();
        if (bytes == null) return ResponseEntity.notFound().build();
        if (digestOnly) {
            byte[] digest = (sha256(bytes) + "\n").getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).contentLength(digest.length).body(digest);
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(FILE_NAME, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(bytes.length).body(bytes);
    }

    private static byte[] loadVerified() throws IOException {
        return loadVerified(new ClassPathResource(RESOURCE), new ClassPathResource(RESOURCE + ".sha256"));
    }

    static byte[] loadVerified(Resource packageResource, Resource hashResource) throws IOException {
        if (!packageResource.exists() || !hashResource.exists()) return null;
        byte[] bytes;
        String declared;
        try (var in = packageResource.getInputStream()) { bytes = in.readAllBytes(); }
        try (var in = hashResource.getInputStream()) { declared = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim(); }
        if (!declared.matches("[0-9a-f]{64}") || !MessageDigest.isEqual(
                declared.getBytes(StandardCharsets.US_ASCII), sha256(bytes).getBytes(StandardCharsets.US_ASCII))) return null;
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
}

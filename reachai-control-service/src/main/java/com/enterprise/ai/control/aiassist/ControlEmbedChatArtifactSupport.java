package com.enterprise.ai.control.aiassist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;

/**
 * Versioned Embed Chat npm tarball served from classpath and bundled into onboarding skill zip.
 */
public final class ControlEmbedChatArtifactSupport {

    public static final String PACKAGE_NAME = "@reachai/embed-chat";
    public static final String VERSION = "1.0.0-SNAPSHOT";
    public static final String FORMAT = "npm-tarball";
    public static final String TARBALL_FILE_NAME = "reachai-embed-chat-" + VERSION + ".tgz";
    public static final String MANIFEST_FILE_NAME = "manifest-artifact.json";
    public static final String CLASS_PATH_DIR = "ai-assist/artifacts/embed-chat/";
    public static final String SKILL_RELATIVE_PATH = "artifacts/" + TARBALL_FILE_NAME;
    public static final String SKILL_MANIFEST_RELATIVE_PATH = "artifacts/" + MANIFEST_FILE_NAME;
    public static final String SKILL_INSTALLER_RELATIVE_PATH = "scripts/install-embed-chat.mjs";
    public static final String ARTIFACT_PATH_WITHIN_SKILL = "reachai-onboarding/" + SKILL_RELATIVE_PATH;
    public static final String VENDORED_ARTIFACT_PATH = "vendor/reachai/" + TARBALL_FILE_NAME;
    public static final String INSTALL_WORKING_DIRECTORY = "business-frontend-package-root";
    public static final String FALLBACK_POLICY = "skill-zip-tarball";
    public static final String SOURCE_POLICY = "platform-artifact-tarball";
    /**
     * Replace {skillExtractDir} with the absolute directory where the onboarding
     * skill zip was extracted. The installer verifies the bundled artifact,
     * vendors it below the business frontend, and runs npm using a stable
     * relative path.
     */
    public static final String INSTALL_COMMAND_TEMPLATE =
            "node \"{skillExtractDir}/reachai-onboarding/"
                    + SKILL_INSTALLER_RELATIVE_PATH
                    + "\" --business-frontend-dir \".\"";
    public static final String INSTALL_COMMAND = INSTALL_COMMAND_TEMPLATE;

    public static final List<String> REQUIRED_FILES = List.of(
            "package.json",
            "index.mjs",
            "index.cjs",
            "index.d.ts",
            "reachai-chat-embed.umd.js",
            "style.css");

    private ControlEmbedChatArtifactSupport() {
    }

    public static boolean isSafeVersion(String version) {
        return StringUtils.hasText(version) && version.matches("^[A-Za-z0-9._-]+$");
    }

    public static String downloadUrl(String baseUrl) {
        String root = StringUtils.hasText(baseUrl) ? baseUrl.replaceAll("/+$", "") : "";
        return root + "/api/ai-assist/artifacts/embed-chat/" + VERSION + ".tgz";
    }

    public static String integritySha256() {
        ClassPathResource resource = new ClassPathResource(CLASS_PATH_DIR + TARBALL_FILE_NAME + ".sha256");
        if (!resource.exists()) {
            return null;
        }
        try (var input = resource.getInputStream()) {
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            return StringUtils.hasText(text) ? text.split("\\s+")[0] : null;
        } catch (IOException ex) {
            return null;
        }
    }

    public static ControlAiAssistProjectController.SdkArtifact npmArtifact(String baseUrl) {
        return new ControlAiAssistProjectController.SdkArtifact(
                "npm",
                "browser",
                PACKAGE_NAME + "@" + VERSION,
                null,
                null,
                PACKAGE_NAME,
                VERSION,
                SOURCE_POLICY,
                List.of(),
                INSTALL_COMMAND,
                "Install from the business frontend package.json directory. "
                        + "Expand {skillExtractDir} in installCommandTemplate and run the bundled Node installer. "
                        + "It verifies integritySha256, copies the tarball to " + VENDORED_ARTIFACT_PATH + ", "
                        + "and records a portable repo-relative file dependency. "
                        + "Do not npm install directly from a temporary Skill extract path. "
                        + "Authenticated platform downloadUrl needs AI Coding/platform auth headers; "
                        + "npm cannot send those headers, so prefer the skill-bundled tarball. "
                        + "Do not require a ReachAI source checkout or ai-admin-front build:sdk as business install.",
                FORMAT,
                downloadUrl(baseUrl),
                integritySha256(),
                INSTALL_COMMAND,
                FALLBACK_POLICY,
                REQUIRED_FILES,
                ARTIFACT_PATH_WITHIN_SKILL,
                INSTALL_WORKING_DIRECTORY,
                INSTALL_COMMAND_TEMPLATE,
                null,
                null);
    }

    public static ResponseEntity<byte[]> tarballResponse(String version) throws IOException {
        if (!isSafeVersion(version)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (!VERSION.equals(version)) {
            return ResponseEntity.notFound().build();
        }
        ClassPathResource resource = new ClassPathResource(CLASS_PATH_DIR + TARBALL_FILE_NAME);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        byte[] body;
        try (var input = resource.getInputStream()) {
            body = input.readAllBytes();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(TARBALL_FILE_NAME, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType("application/gzip"))
                .contentLength(body.length)
                .body(body);
    }

    public static ResponseEntity<byte[]> sha256Response(String version) throws IOException {
        if (!isSafeVersion(version)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (!VERSION.equals(version)) {
            return ResponseEntity.notFound().build();
        }
        ClassPathResource resource = new ClassPathResource(CLASS_PATH_DIR + TARBALL_FILE_NAME + ".sha256");
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        byte[] body;
        try (var input = resource.getInputStream()) {
            body = input.readAllBytes();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .contentLength(body.length)
                .body(body);
    }

    public static byte[] loadTarballBytes() throws IOException {
        ClassPathResource resource = new ClassPathResource(CLASS_PATH_DIR + TARBALL_FILE_NAME);
        if (!resource.exists()) {
            throw new IOException("Missing Embed Chat artifact: " + TARBALL_FILE_NAME);
        }
        try (var input = resource.getInputStream()) {
            return input.readAllBytes();
        }
    }

    public static byte[] loadManifestBytes() throws IOException {
        ClassPathResource resource = new ClassPathResource(
                CLASS_PATH_DIR + MANIFEST_FILE_NAME);
        if (!resource.exists()) {
            throw new IOException("Missing Embed Chat artifact manifest");
        }
        try (var input = resource.getInputStream()) {
            return input.readAllBytes();
        }
    }
}

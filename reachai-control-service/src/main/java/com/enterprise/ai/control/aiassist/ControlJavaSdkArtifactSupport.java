package com.enterprise.ai.control.aiassist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;

/**
 * Versioned Java SDK artifacts that can be consumed without a ReachAI source checkout.
 */
public final class ControlJavaSdkArtifactSupport {

    public static final String GROUP_ID = "com.enterprise.ai";
    public static final String VERSION = "1.0.0-SNAPSHOT";
    public static final String CAPABILITY_SDK = "reachai-capability-sdk";
    public static final String SPRING_BOOT2_STARTER = "reachai-spring-boot2-starter";
    public static final String CLASS_PATH_DIR = "ai-assist/artifacts/java-sdk/";
    public static final String SKILL_INSTALLER_RELATIVE_PATH = "scripts/install-java-sdk.ps1";
    public static final String SOURCE_POLICY = "platform-artifact-download";
    public static final String INSTALL_WORKING_DIRECTORY =
            "business-backend-maven-module-or-reactor-root";

    private static final Map<String, String> NOTES = Map.of(
            CAPABILITY_SDK,
            "Download the JAR and standalone consumer POM from ReachAI, verify both SHA-256 values, then install them into the business system's Maven local repository.",
            SPRING_BOOT2_STARTER,
            "Install after reachai-capability-sdk. The Starter keeps Java 8-compatible bytecode and supports Spring Boot 2.7+ and Spring Boot 3 auto-configuration; a Spring Boot 3 application must still use the Java version required by its Boot release. No ReachAI source checkout or shared repository is required.");

    private ControlJavaSdkArtifactSupport() {
    }

    public static List<ControlAiAssistProjectController.SdkArtifact> mavenArtifacts(
            String baseUrl) {
        return List.of(
                mavenArtifact(CAPABILITY_SDK, baseUrl),
                mavenArtifact(SPRING_BOOT2_STARTER, baseUrl));
    }

    private static ControlAiAssistProjectController.SdkArtifact mavenArtifact(
            String artifactId,
            String baseUrl) {
        String jarUrl = artifactUrl(baseUrl, artifactId, "jar");
        String pomUrl = artifactUrl(baseUrl, artifactId, "pom");
        String jarSha256 = integritySha256(artifactId, "jar");
        String pomSha256 = integritySha256(artifactId, "pom");
        String installCommand = installCommand(
                artifactId,
                jarUrl,
                jarSha256,
                pomUrl,
                pomSha256);
        return new ControlAiAssistProjectController.SdkArtifact(
                "maven",
                "java",
                GROUP_ID + ":" + artifactId + ":" + VERSION,
                GROUP_ID,
                artifactId,
                null,
                VERSION,
                SOURCE_POLICY,
                List.of(),
                installCommand,
                NOTES.get(artifactId),
                "maven-jar",
                jarUrl,
                jarSha256,
                installCommand,
                "fail-closed",
                List.of(
                        artifactId + "-" + VERSION + ".jar",
                        artifactId + "-" + VERSION + ".pom"),
                null,
                INSTALL_WORKING_DIRECTORY,
                installCommand,
                pomUrl,
                pomSha256);
    }

    private static String installCommand(
            String artifactId,
            String jarUrl,
            String jarSha256,
            String pomUrl,
            String pomSha256) {
        return "powershell -NoProfile -ExecutionPolicy Bypass -File "
                + "\"{skillExtractDir}/reachai-onboarding/"
                + SKILL_INSTALLER_RELATIVE_PATH
                + "\" -GroupId \"" + GROUP_ID
                + "\" -ArtifactId \"" + artifactId
                + "\" -Version \"" + VERSION
                + "\" -JarUrl \"" + jarUrl
                + "\" -JarSha256 \"" + nullToEmpty(jarSha256)
                + "\" -PomUrl \"" + pomUrl
                + "\" -PomSha256 \"" + nullToEmpty(pomSha256) + "\"";
    }

    public static String artifactUrl(
            String baseUrl,
            String artifactId,
            String extension) {
        String root = StringUtils.hasText(baseUrl)
                ? baseUrl.replaceAll("/+$", "")
                : "";
        return root + "/api/ai-assist/artifacts/java-sdk/"
                + artifactId + "/" + VERSION + "." + extension;
    }

    public static String integritySha256(String artifactId, String extension) {
        if (!isKnownArtifact(artifactId) || !isKnownExtension(extension)) {
            return null;
        }
        ClassPathResource resource = new ClassPathResource(
                CLASS_PATH_DIR + fileName(artifactId, extension) + ".sha256");
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

    public static ResponseEntity<byte[]> artifactResponse(
            String artifactId,
            String version,
            String extension) throws IOException {
        if (!isSafePathSegment(artifactId)
                || !isSafePathSegment(version)
                || !isSafePathSegment(extension)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (!isKnownArtifact(artifactId)
                || !VERSION.equals(version)
                || !isKnownExtension(extension)) {
            return ResponseEntity.notFound().build();
        }
        String fileName = fileName(artifactId, extension);
        ClassPathResource resource = new ClassPathResource(CLASS_PATH_DIR + fileName);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        byte[] body;
        try (var input = resource.getInputStream()) {
            body = input.readAllBytes();
        }
        MediaType contentType = "jar".equals(extension)
                ? MediaType.parseMediaType("application/java-archive")
                : MediaType.APPLICATION_XML;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(contentType)
                .contentLength(body.length)
                .body(body);
    }

    public static ResponseEntity<byte[]> sha256Response(
            String artifactId,
            String version,
            String extension) throws IOException {
        if (!isSafePathSegment(artifactId)
                || !isSafePathSegment(version)
                || !isSafePathSegment(extension)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (!isKnownArtifact(artifactId)
                || !VERSION.equals(version)
                || !isKnownExtension(extension)) {
            return ResponseEntity.notFound().build();
        }
        ClassPathResource resource = new ClassPathResource(
                CLASS_PATH_DIR + fileName(artifactId, extension) + ".sha256");
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

    private static boolean isKnownArtifact(String artifactId) {
        return CAPABILITY_SDK.equals(artifactId)
                || SPRING_BOOT2_STARTER.equals(artifactId);
    }

    private static boolean isKnownExtension(String extension) {
        return "jar".equals(extension) || "pom".equals(extension);
    }

    private static boolean isSafePathSegment(String value) {
        return StringUtils.hasText(value) && value.matches("^[A-Za-z0-9._-]+$");
    }

    private static String fileName(String artifactId, String extension) {
        return artifactId + "-" + VERSION + "." + extension;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

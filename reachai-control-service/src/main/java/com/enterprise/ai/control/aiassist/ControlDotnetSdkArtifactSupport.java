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

/** A versioned NuGet package distributed by Control, without a public NuGet feed. */
public final class ControlDotnetSdkArtifactSupport {
    public static final String PACKAGE_NAME = "ReachAI.AspNetCore";
    public static final String VERSION = "1.0.0";
    private static final String FILE_NAME = PACKAGE_NAME + "." + VERSION + ".nupkg";
    private static final String RESOURCE = "ai-assist/artifacts/dotnet-sdk/" + FILE_NAME;

    private ControlDotnetSdkArtifactSupport() { }

    public static String downloadUrl(String baseUrl) {
        String root = StringUtils.hasText(baseUrl) ? baseUrl.replaceAll("/+$", "") : "";
        return root + "/api/ai-assist/artifacts/dotnet-sdk/" + PACKAGE_NAME + "/" + VERSION + ".nupkg";
    }

    /** Do not advertise an integrity value if the package is missing or does not match it. */
    public static String integritySha256() {
        try {
            byte[] bytes = loadVerified();
            return bytes == null ? null : sha256(bytes);
        } catch (IOException ex) {
            return null;
        }
    }

    public static ControlAiAssistProjectController.SdkArtifact nugetArtifact(String baseUrl) {
        String command = "dotnet add <business-project.csproj> package " + PACKAGE_NAME
                + " --version " + VERSION + " --source <verified-local-feed>";
        return new ControlAiAssistProjectController.SdkArtifact(
                "nuget", "csharp", PACKAGE_NAME + ":" + VERSION, null, null, PACKAGE_NAME,
                VERSION, "platform-artifact-download", List.of(), command,
                "Download the nupkg using the platform's authenticated session and verify integritySha256. "
                        + "Store it in a project-local NuGet feed and configure that feed persistently for subsequent restores. "
                        + "The package is not published to a public feed; a missing checksum means the Control build has no verified package.",
                "nuget-package", downloadUrl(baseUrl), integritySha256(), command, "fail-closed",
                List.of(FILE_NAME), null, "business-backend-dotnet-project", command, null, null);
    }

    public static ResponseEntity<byte[]> packageResponse(String packageName, String version) throws IOException {
        if (!safe(packageName) || !safe(version)) return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        if (!PACKAGE_NAME.equals(packageName) || !VERSION.equals(version)) return ResponseEntity.notFound().build();
        byte[] bytes = loadVerified();
        if (bytes == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(FILE_NAME, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(bytes.length).body(bytes);
    }

    public static ResponseEntity<byte[]> sha256Response(String packageName, String version) throws IOException {
        if (!safe(packageName) || !safe(version)) return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        if (!PACKAGE_NAME.equals(packageName) || !VERSION.equals(version)) return ResponseEntity.notFound().build();
        byte[] bytes = loadVerified();
        if (bytes == null) return ResponseEntity.notFound().build();
        byte[] digest = (sha256(bytes) + "\n").getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).contentLength(digest.length).body(digest);
    }

    private static byte[] loadVerified() throws IOException {
        return loadVerified(new ClassPathResource(RESOURCE), new ClassPathResource(RESOURCE + ".sha256"));
    }

    static byte[] loadVerified(Resource packageResource, Resource hashResource) throws IOException {
        if (!packageResource.exists() || !hashResource.exists()) return null;
        byte[] bytes;
        String declared;
        try (var in = packageResource.getInputStream()) { bytes = in.readAllBytes(); }
        try (var in = hashResource.getInputStream()) {
            declared = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        if (!declared.matches("[0-9a-f]{64}") || !MessageDigest.isEqual(
                declared.getBytes(StandardCharsets.US_ASCII), sha256(bytes).getBytes(StandardCharsets.US_ASCII))) return null;
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }

    private static boolean safe(String value) {
        return StringUtils.hasText(value) && value.matches("^[A-Za-z0-9._-]+$");
    }
}

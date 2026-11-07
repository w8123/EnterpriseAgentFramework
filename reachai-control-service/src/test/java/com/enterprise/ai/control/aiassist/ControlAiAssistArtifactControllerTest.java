package com.enterprise.ai.control.aiassist;

import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlAiAssistArtifactControllerTest {

    private final ControlAiAssistArtifactController controller = new ControlAiAssistArtifactController();

    @Test
    void goPackageIsVerifiedAndRejectsModifiedBytes() throws Exception {
        byte[] good = "go package".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(good));
        var digest = new ByteArrayResource(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertArrayEquals(good, ControlGoSdkArtifactSupport.loadVerified(new ByteArrayResource(good), digest));
        assertNull(ControlGoSdkArtifactSupport.loadVerified(new ByteArrayResource(new byte[] {1}), digest));
        assertEquals(400, controller.downloadGoSdk("../secret.zip").getStatusCode().value());
        assertEquals(404, controller.downloadGoSdk("other.zip").getStatusCode().value());
        String actual = ControlGoSdkArtifactSupport.integritySha256();
        assertNotNull(actual, "A normal Control build must contain the verified Go SDK archive");
        var response = controller.downloadGoSdk(ControlGoSdkArtifactSupport.FILE_NAME);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(actual, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.getBody())));
        assertEquals(actual, new String(controller.downloadGoSdk(ControlGoSdkArtifactSupport.FILE_NAME + ".sha256").getBody()).trim());
    }

    @Test
    void nodePackageIsVerifiedAndRejectsModifiedBytes() throws Exception {
        byte[] good = "node package".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(good));
        var digest = new ByteArrayResource(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertArrayEquals(good, ControlNodeSdkArtifactSupport.loadVerified(new ByteArrayResource(good), digest));
        assertNull(ControlNodeSdkArtifactSupport.loadVerified(new ByteArrayResource(new byte[] {1}), digest));
        assertEquals(400, controller.downloadNodeSdk("../secret.tgz").getStatusCode().value());
        assertEquals(404, controller.downloadNodeSdk("other.tgz").getStatusCode().value());
        String actual = ControlNodeSdkArtifactSupport.integritySha256();
        assertNotNull(actual, "A normal Control build must contain the verified Node SDK archive");
        var response = controller.downloadNodeSdk(ControlNodeSdkArtifactSupport.FILE_NAME);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(actual, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.getBody())));
        assertEquals(actual, new String(controller.downloadNodeSdk(ControlNodeSdkArtifactSupport.FILE_NAME + ".sha256").getBody()).trim());
    }

    @Test
    void pythonPackageRejectsModifiedBytesAndInvalidRequests() throws Exception {
        byte[] good = "wheel bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(good));
        var digest = new ByteArrayResource(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertArrayEquals(good, ControlPythonSdkArtifactSupport.loadVerified(new ByteArrayResource(good), digest));
        assertNull(ControlPythonSdkArtifactSupport.loadVerified(new ByteArrayResource(new byte[] {1}), digest));
        assertEquals(400, controller.downloadPythonSdk("../secret.whl").getStatusCode().value());
        assertEquals(404, controller.downloadPythonSdk("other-1.0-py3-none-any.whl").getStatusCode().value());
    }

    @Test
    void servesVerifiedPythonWheelAndItsDigest() throws Exception {
        String hash = ControlPythonSdkArtifactSupport.integritySha256();
        assertNotNull(hash, "A normal Control build must contain the verified Python SDK wheel");
        var wheel = controller.downloadPythonSdk(ControlPythonSdkArtifactSupport.FILE_NAME);
        assertEquals(200, wheel.getStatusCode().value());
        assertEquals(hash, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wheel.getBody())));
        assertEquals(hash, new String(controller.downloadPythonSdk(ControlPythonSdkArtifactSupport.FILE_NAME + ".sha256").getBody()).trim());
    }

    @Test
    void dotnetPackageRejectsMismatchInsteadOfServingStaleBytes() throws Exception {
        byte[] packageBytes = "package bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] alteredBytes = "altered bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(packageBytes));
        var hash = new ByteArrayResource(sha.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertArrayEquals(packageBytes, ControlDotnetSdkArtifactSupport.loadVerified(
                new ByteArrayResource(packageBytes), hash));
        assertNull(ControlDotnetSdkArtifactSupport.loadVerified(new ByteArrayResource(alteredBytes), hash));
    }

    @Test
    void dotnetArtifactIsOnlyServedWhenItsBytesMatchThePublishedDigest() throws Exception {
        assertEquals(400, controller.downloadDotnetSdk("../secret", "1.0.0").getStatusCode().value());
        assertEquals(404, controller.downloadDotnetSdk("Other.Package", "1.0.0").getStatusCode().value());
        assertEquals(404, controller.downloadDotnetSdk("ReachAI.AspNetCore", "9.0.0").getStatusCode().value());
        String digest = ControlDotnetSdkArtifactSupport.integritySha256();
        assertNotNull(digest, "A normal Control checkout/build must include the verified C# SDK package");
        var response = controller.downloadDotnetSdk("ReachAI.AspNetCore", "1.0.0");
        assertEquals(200, response.getStatusCode().value());
        assertEquals(digest, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(response.getBody())));
        assertEquals(digest, new String(controller.downloadDotnetSdkSha256(
                "ReachAI.AspNetCore", "1.0.0").getBody()).trim());
    }

    @Test
    void downloadReturnsGzipTarballWithDisposition() throws Exception {
        ResponseEntity<byte[]> response = controller.downloadEmbedChat("1.0.0-SNAPSHOT");
        ResponseEntity<byte[]> repeated = controller.downloadEmbedChat("1.0.0-SNAPSHOT");
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 100);
        assertEquals(200, repeated.getStatusCode().value());
        assertArrayEquals(response.getBody(), repeated.getBody());
        assertEquals("application/gzip", response.getHeaders().getContentType().toString());
        assertTrue(String.valueOf(response.getHeaders().getFirst("Content-Disposition"))
                .contains("reachai-embed-chat-1.0.0-SNAPSHOT.tgz"));
        assertTrue(String.valueOf(response.getHeaders().getFirst("Content-Disposition"))
                .toLowerCase()
                .contains("attachment"));
    }

    @Test
    void missingVersionReturns404() throws Exception {
        ResponseEntity<byte[]> response = controller.downloadEmbedChat("9.9.9");
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void pathTraversalRejected() throws Exception {
        ResponseEntity<byte[]> response = controller.downloadEmbedChat("../secret");
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void sha256MatchesSidecar() throws Exception {
        ResponseEntity<byte[]> response = controller.downloadEmbedChatSha256("1.0.0-SNAPSHOT");
        assertEquals(200, response.getStatusCode().value());
        String sha = new String(response.getBody()).trim();
        assertEquals(64, sha.length());
        assertEquals(ControlEmbedChatArtifactSupport.integritySha256(), sha);
    }

    @Test
    void servesJavaSdkJarPomAndMatchingHashes() throws Exception {
        for (String artifactId : java.util.List.of(
                ControlJavaSdkArtifactSupport.CAPABILITY_SDK,
                ControlJavaSdkArtifactSupport.SPRING_BOOT2_STARTER)) {
            String version = ControlJavaSdkArtifactSupport.VERSION;

            ResponseEntity<byte[]> jar = controller.downloadJavaSdkJar(artifactId, version);
            ResponseEntity<byte[]> pom = controller.downloadJavaSdkPom(artifactId, version);
            ResponseEntity<byte[]> jarHash = controller.downloadJavaSdkJarSha256(artifactId, version);
            ResponseEntity<byte[]> pomHash = controller.downloadJavaSdkPomSha256(artifactId, version);

            assertEquals(200, jar.getStatusCode().value());
            assertEquals(200, pom.getStatusCode().value());
            assertTrue(jar.getBody().length > 100);
            assertTrue(new String(pom.getBody()).contains("<artifactId>" + artifactId));
            assertEquals(
                    new String(jarHash.getBody()).trim(),
                    HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(jar.getBody())));
            assertEquals(
                    new String(pomHash.getBody()).trim(),
                    HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(pom.getBody())));
        }
    }
}

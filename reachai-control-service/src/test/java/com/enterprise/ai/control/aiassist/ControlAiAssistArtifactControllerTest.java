package com.enterprise.ai.control.aiassist;

import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlAiAssistArtifactControllerTest {

    private final ControlAiAssistArtifactController controller = new ControlAiAssistArtifactController();

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
        String artifactId = ControlJavaSdkArtifactSupport.CAPABILITY_SDK;
        String version = ControlJavaSdkArtifactSupport.VERSION;

        ResponseEntity<byte[]> jar = controller.downloadJavaSdkJar(
                artifactId,
                version);
        ResponseEntity<byte[]> pom = controller.downloadJavaSdkPom(
                artifactId,
                version);
        ResponseEntity<byte[]> jarHash = controller.downloadJavaSdkJarSha256(
                artifactId,
                version);
        ResponseEntity<byte[]> pomHash = controller.downloadJavaSdkPomSha256(
                artifactId,
                version);

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

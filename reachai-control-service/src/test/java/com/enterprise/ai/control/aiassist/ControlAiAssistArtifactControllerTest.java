package com.enterprise.ai.control.aiassist;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlAiAssistArtifactControllerTest {

    private final ControlAiAssistArtifactController controller = new ControlAiAssistArtifactController();

    @Test
    void downloadReturnsGzipTarballWithDisposition() throws Exception {
        ResponseEntity<byte[]> response = controller.downloadEmbedChat("1.0.0-SNAPSHOT");
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 100);
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
}

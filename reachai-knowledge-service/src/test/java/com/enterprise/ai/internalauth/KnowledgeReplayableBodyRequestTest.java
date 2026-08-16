package com.enterprise.ai.internalauth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeReplayableBodyRequestTest {

    @Test
    void spoolsLargeBodyAndDeletesTemporaryFileOnClose() throws Exception {
        byte[] body = "streamed-sensitive-body".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/test");
        request.setContent(body);
        KnowledgeReplayableBodyRequest captured = KnowledgeReplayableBodyRequest.capture(
                request, 1024, 4);

        assertTrue(captured.spooledToDisk());
        assertArrayEquals(body, captured.getInputStream().readAllBytes());
        assertArrayEquals(body, captured.getInputStream().readAllBytes());

        captured.close();
        assertThrows(IOException.class, captured::getInputStream);
    }
}

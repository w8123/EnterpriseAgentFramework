package com.enterprise.ai.model.runtime;

import com.enterprise.ai.model.instance.ModelInstanceRuntime;
import com.enterprise.ai.model.service.ChatRequest;
import com.enterprise.ai.model.service.ChatResponse;
import com.enterprise.ai.model.service.ModelStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleRuntimeClientResetRetryTest {

    @Test
    @Timeout(10)
    void chatRetriesOnceWhenTheFirstConnectionIsReset() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}";
        try (ResettingProvider provider = new ResettingProvider("application/json", json)) {
            OpenAiCompatibleRuntimeClient client = new OpenAiCompatibleRuntimeClient(new ObjectMapper());

            ChatResponse response = client.chat(provider.runtime(), request(), Duration.ofSeconds(5));

            assertEquals("OK", response.getContent());
            assertEquals(2, provider.connections.get());
        }
    }

    @Test
    @Timeout(10)
    void streamRetriesOnceWhenTheFirstConnectionIsReset() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
        try (ResettingProvider provider = new ResettingProvider("text/event-stream", sse)) {
            OpenAiCompatibleRuntimeClient client = new OpenAiCompatibleRuntimeClient(new ObjectMapper());

            List<ModelStreamEvent> events = client.chatStreamEvents(provider.runtime(), request())
                    .collectList().block(Duration.ofSeconds(5));

            assertTrue(events != null && !events.isEmpty());
            assertTrue(events.stream().noneMatch(event -> event.toString().contains("Connection reset")));
            assertEquals(2, provider.connections.get());
        }
    }

    @Test
    void onlyTransportResetsAreRetried() {
        assertTrue(OpenAiCompatibleRuntimeClient.isStaleConnectionFailure(new IOException("Connection reset")));
        assertTrue(OpenAiCompatibleRuntimeClient.isStaleConnectionFailure(
                new RuntimeException(new SocketException("Broken pipe"))));
        assertFalse(OpenAiCompatibleRuntimeClient.isStaleConnectionFailure(new HttpTimeoutException("timed out")));
        assertFalse(OpenAiCompatibleRuntimeClient.isStaleConnectionFailure(new IllegalStateException("bad json")));
    }

    private static ChatRequest request() {
        return ChatRequest.builder().messages(List.of(ChatRequest.ChatMessage.builder()
                .role("user").content("Reply with OK.").build())).build();
    }

    /** Resets the first TCP connection like a provider that closed an idle keep-alive socket, then answers. */
    private static final class ResettingProvider implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        private final AtomicInteger connections = new AtomicInteger();
        private final Thread acceptor;

        private ResettingProvider(String contentType, String body) throws IOException {
            acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        int connection = connections.incrementAndGet();
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        int contentLength = 0;
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase().startsWith("content-length:")) {
                                contentLength = Integer.parseInt(line.substring(15).trim());
                            }
                        }
                        reader.skip(contentLength);
                        if (connection == 1) {
                            socket.setSoLinger(true, 0);
                            continue;
                        }
                        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                        OutputStream out = socket.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + contentType
                                + "\r\nContent-Length: " + payload.length
                                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        out.write(payload);
                        out.flush();
                    } catch (IOException ignored) {
                        // server closed
                    }
                }
            }, "model-reset-fixture");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private ModelInstanceRuntime runtime() {
            return ModelInstanceRuntime.builder().id("reset-fixture").provider("fixture")
                    .modelName("fixture-model").connectionConfig(Map.of(
                            "baseUrl", "http://127.0.0.1:" + server.getLocalPort())).build();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}

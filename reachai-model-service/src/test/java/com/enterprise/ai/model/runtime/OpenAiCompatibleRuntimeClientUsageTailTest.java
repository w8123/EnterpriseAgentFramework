package com.enterprise.ai.model.runtime;

import com.enterprise.ai.model.instance.ModelInstanceRuntime;
import com.enterprise.ai.model.service.ChatRequest;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleRuntimeClientUsageTailTest {

    private static final String FINISH = "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\n";

    @Test
    @Timeout(10)
    void usageSentAfterFinishReasonReachesTheStream() throws Exception {
        String tail = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":2,\"total_tokens\":22}}\n\n"
                + "data: [DONE]\n\n";
        try (SseProvider provider = new SseProvider(FINISH + tail)) {
            List<ModelStreamEvent> events = stream(provider);

            ModelStreamEvent usage = events.stream()
                    .filter(event -> ModelStreamEvent.USAGE.equals(event.getType())).findFirst().orElse(null);
            assertNotNull(usage);
            assertEquals(20, usage.getUsage().getPromptTokens());
            assertEquals(2, usage.getUsage().getCompletionTokens());
            assertEquals(ModelStreamEvent.COMPLETED, events.get(events.size() - 1).getType());
        }
    }

    @Test
    @Timeout(10)
    void providerThatHangsAfterFinishReasonStillCompletes() throws Exception {
        try (SseProvider provider = new SseProvider(FINISH)) {
            long started = System.nanoTime();
            List<ModelStreamEvent> events = stream(provider);

            assertEquals(ModelStreamEvent.COMPLETED, events.get(events.size() - 1).getType());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5);
        }
    }

    private static List<ModelStreamEvent> stream(SseProvider provider) {
        ChatRequest request = ChatRequest.builder().messages(List.of(ChatRequest.ChatMessage.builder()
                .role("user").content("Reply with OK.").build())).build();
        List<ModelStreamEvent> events = new OpenAiCompatibleRuntimeClient(new ObjectMapper())
                .chatStreamEvents(provider.runtime(), request).collectList().block(Duration.ofSeconds(8));
        assertNotNull(events);
        return events;
    }

    /** Writes the SSE body without Content-Length and keeps the socket open until the test ends. */
    private static final class SseProvider implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        private final CountDownLatch done = new CountDownLatch(1);

        private SseProvider(String body) throws IOException {
            Thread acceptor = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        // request headers; the body is ignored
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n" + body)
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    done.await();
                } catch (IOException | InterruptedException ignored) {
                    // test finished
                }
            }, "model-sse-fixture");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private ModelInstanceRuntime runtime() {
            return ModelInstanceRuntime.builder().id("sse-fixture").provider("fixture")
                    .modelName("fixture-model").connectionConfig(Map.of(
                            "baseUrl", "http://127.0.0.1:" + server.getLocalPort())).build();
        }

        @Override
        public void close() throws IOException {
            done.countDown();
            server.close();
        }
    }
}

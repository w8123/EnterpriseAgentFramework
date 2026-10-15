package com.enterprise.ai.model.runtime;

import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.instance.ModelInstanceRuntime;
import com.enterprise.ai.model.service.ChatRequest;
import com.enterprise.ai.model.service.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiCompatibleRuntimeClientDeadlineTest {

    @Test
    @Timeout(10)
    void slowBodyMustRespectCompleteDeadlineAndCancelExchange() throws Exception {
        try (ProviderFixture provider = new ProviderFixture(true)) {
            OpenAiCompatibleRuntimeClient client = new OpenAiCompatibleRuntimeClient(new ObjectMapper());
            long started = System.nanoTime();

            BizException failure = assertThrows(BizException.class,
                    () -> client.chat(provider.runtime(), request(), Duration.ofMillis(500)));

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(504, failure.getCode());
            assertTrue(failure.getMessage().contains("timed out"));
            assertTrue(provider.headersSent.await(1, TimeUnit.SECONDS), "must have started reading the response body");
            assertTrue(elapsedMs < 1500, "the complete body deadline must precede the delayed body");
            assertTrue(provider.disconnected.await(2, TimeUnit.SECONDS),
                    "cancelling the original future must release its active exchange");
        }
    }

    @Test
    @Timeout(10)
    void completeBodyWithinBudgetStillReturnsContent() throws Exception {
        try (ProviderFixture provider = new ProviderFixture(false)) {
            OpenAiCompatibleRuntimeClient client = new OpenAiCompatibleRuntimeClient(new ObjectMapper());

            ChatResponse response = client.chat(provider.runtime(), request(), Duration.ofSeconds(2));

            assertEquals("OK", response.getContent());
        }
    }

    @Test
    @Timeout(10)
    void slowHeadersAlsoRespectDeadline() throws Exception {
        try (ProviderFixture provider = new ProviderFixture(false, true)) {
            OpenAiCompatibleRuntimeClient client = new OpenAiCompatibleRuntimeClient(new ObjectMapper());
            long started = System.nanoTime();

            BizException failure = assertThrows(BizException.class,
                    () -> client.chat(provider.runtime(), request(), Duration.ofMillis(250)));

            assertEquals(504, failure.getCode());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1200);
        }
    }

    @Test
    void deadlineCancelsOriginalFutureAndRejectsLateSuccess() {
        CompletableFuture<HttpResponse<String>> original = new CompletableFuture<>();
        ClientFixture fixture = clientWith(original);

        BizException failure = assertThrows(BizException.class,
                () -> fixture.client.chat(mockRuntime(), request(), Duration.ofMillis(40)));

        assertEquals(504, failure.getCode());
        assertTrue(original.isCancelled());
        assertFalse(original.complete(okResponse()), "a late response must not turn cancellation into success");
    }

    @Test
    void interruptedCallerCancelsOriginalFutureAndRestoresInterrupt() {
        CompletableFuture<HttpResponse<String>> original = new CompletableFuture<>();
        ClientFixture fixture = clientWith(original);
        try {
            Thread.currentThread().interrupt();

            BizException failure = assertThrows(BizException.class,
                    () -> fixture.client.chat(mockRuntime(), request(), Duration.ofSeconds(1)));

            assertEquals(502, failure.getCode());
            assertTrue(failure.getMessage().contains("interrupted"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(original.isCancelled());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void transportTimeoutMapsToGatewayTimeout() {
        ClientFixture fixture = clientWith(CompletableFuture.failedFuture(new HttpTimeoutException("fixture timeout")));

        BizException failure = assertThrows(BizException.class,
                () -> fixture.client.chat(mockRuntime(), request(), Duration.ofSeconds(1)));

        assertEquals(504, failure.getCode());
        assertTrue(failure.getMessage().contains("timed out"));
    }

    @Test
    void nestedTransportFailureIsUnwrappedWithoutCompletionWrapper() {
        ClientFixture fixture = clientWith(CompletableFuture.failedFuture(
                new CompletionException(new IOException("transport fixture"))));

        BizException failure = assertThrows(BizException.class,
                () -> fixture.client.chat(mockRuntime(), request(), Duration.ofSeconds(1)));

        assertEquals(502, failure.getCode());
        assertTrue(failure.getMessage().contains("IOException"));
        assertFalse(failure.getMessage().contains("CompletionException"));
    }

    @Test
    void productionChatKeepsEightMinuteDefaultBudget() {
        ClientFixture fixture = clientWith(CompletableFuture.completedFuture(okResponse()));

        assertEquals("OK", fixture.client.chat(mockRuntime(), request()).getContent());

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(fixture.transport).sendAsync(sent.capture(), any());
        assertEquals(Duration.ofMinutes(8), sent.getValue().timeout().orElseThrow());
    }

    @Test
    void invalidOrExpandedBudgetNeverDispatches() {
        ClientFixture fixture = clientWith(new CompletableFuture<>());
        for (Duration budget : List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofMinutes(9))) {
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.client.chat(mockRuntime(), request(), budget));
        }
        verify(fixture.transport, never()).sendAsync(any(), any());
    }

    private static ClientFixture clientWith(CompletableFuture<HttpResponse<String>> original) {
        HttpClient transport = mock(HttpClient.class);
        when(transport.<String>sendAsync(any(), any())).thenReturn(original);
        OpenAiCompatibleRuntimeClient client = new OpenAiCompatibleRuntimeClient(new ObjectMapper());
        ReflectionTestUtils.setField(client, "httpClient", transport);
        return new ClientFixture(client, transport);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> okResponse() {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}");
        return response;
    }

    private static ModelInstanceRuntime mockRuntime() {
        return ModelInstanceRuntime.builder().id("mock-fixture").provider("fixture").modelName("fixture-model")
                .connectionConfig(Map.of("baseUrl", "http://127.0.0.1:1")).build();
    }

    private record ClientFixture(OpenAiCompatibleRuntimeClient client, HttpClient transport) {
    }

    private static ChatRequest request() {
        return ChatRequest.builder().messages(List.of(ChatRequest.ChatMessage.builder()
                .role("user").content("Reply with OK.").build())).build();
    }

    private static final class ProviderFixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final CountDownLatch headersSent = new CountDownLatch(1);
        private final CountDownLatch disconnected = new CountDownLatch(1);

        private ProviderFixture(boolean delayedBody) throws IOException {
            this(delayedBody, false);
        }

        private ProviderFixture(boolean delayedBody, boolean delayedHeaders) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(task -> {
                Thread thread = new Thread(task, "model-deadline-fixture");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext("/chat/completions", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    if (delayedHeaders) {
                        Thread.sleep(1800);
                    }
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(' ');
                    exchange.getResponseBody().flush();
                    headersSent.countDown();
                    if (delayedBody) {
                        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000);
                        byte[] keepAlive = " ".repeat(4096).getBytes(StandardCharsets.UTF_8);
                        while (System.nanoTime() < until) {
                            exchange.getResponseBody().write(keepAlive);
                            exchange.getResponseBody().flush();
                            Thread.sleep(25);
                        }
                    }
                    exchange.getResponseBody().write("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"
                            .getBytes(StandardCharsets.UTF_8));
                } catch (IOException cancelledExchange) {
                    disconnected.countDown();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        private ModelInstanceRuntime runtime() {
            return ModelInstanceRuntime.builder().id("local-fixture").provider("fixture")
                    .modelName("fixture-model").connectionConfig(Map.of(
                            "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort())).build();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}

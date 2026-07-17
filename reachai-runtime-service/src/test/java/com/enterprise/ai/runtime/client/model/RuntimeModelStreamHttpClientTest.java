package com.enterprise.ai.runtime.client.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用本地 HttpServer 验证 sendAsync 响应头前/后取消，不访问外部 Provider。
 */
class RuntimeModelStreamHttpClientTest {

    private HttpServer server;
    private String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void cancelBeforeResponseHeadersCancelsFutureAndSkipsEvents() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger handlerHits = new AtomicInteger();
        server.createContext("/model/chat/stream/events", exchange -> {
            handlerHits.incrementAndGet();
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 若未被取消，才写响应
            byte[] body = "data: {\"type\":\"content.delta\",\"text\":\"x\"}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        RuntimeModelStreamHttpClient client = new RuntimeModelStreamHttpClient(objectMapper, baseUrl);
        ModelStreamSubscription subscription = new ModelStreamSubscription();
        List<RuntimeModelStreamHttpClient.ModelStreamEventDto> events = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        CompletableFuture<Void> call = CompletableFuture.runAsync(() -> {
            try {
                client.streamChatEvents(minimalRequest(), events::add, subscription);
            } catch (Throwable t) {
                error.set(t);
            }
        });

        assertTrue(entered.await(3, TimeUnit.SECONDS), "handler should start before cancel");
        subscription.cancel();
        release.countDown();
        call.get(5, TimeUnit.SECONDS);

        assertEquals(0, events.size(), "cancel before headers must not deliver events");
        assertTrue(error.get() == null, "cancel must not throw / fallback: " + error.get());
    }

    @Test
    void cancelAfterHeadersClosesStreamAndStopsOnEvent() throws Exception {
        CountDownLatch firstEventWritten = new CountDownLatch(1);
        CountDownLatch holdOpen = new CountDownLatch(1);
        server.createContext("/model/chat/stream/events", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("data: {\"type\":\"content.delta\",\"text\":\"a\"}\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                firstEventWritten.countDown();
                // 阻塞保持连接，等待客户端 cancel 关闭流
                holdOpen.await(5, TimeUnit.SECONDS);
                try {
                    os.write("data: {\"type\":\"content.delta\",\"text\":\"b\"}\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException ignored) {
                    // expected after cancel closes stream
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        RuntimeModelStreamHttpClient client = new RuntimeModelStreamHttpClient(objectMapper, baseUrl);
        ModelStreamSubscription subscription = new ModelStreamSubscription();
        List<RuntimeModelStreamHttpClient.ModelStreamEventDto> events = new ArrayList<>();
        CountDownLatch gotFirst = new CountDownLatch(1);

        CompletableFuture<Void> call = CompletableFuture.runAsync(() ->
                client.streamChatEvents(minimalRequest(), event -> {
                    events.add(event);
                    gotFirst.countDown();
                }, subscription));

        assertTrue(firstEventWritten.await(3, TimeUnit.SECONDS));
        assertTrue(gotFirst.await(3, TimeUnit.SECONDS));
        subscription.cancel();
        holdOpen.countDown();
        call.get(5, TimeUnit.SECONDS);

        assertEquals(1, events.size());
        assertEquals("a", events.get(0).text);
    }

    @Test
    void normalCompletionDeliversMultipleEvents() {
        server.createContext("/model/chat/stream/events", exchange -> {
            byte[] body = (
                    "data: {\"type\":\"content.delta\",\"text\":\"hel\"}\n\n"
                            + "data: {\"type\":\"content.delta\",\"text\":\"lo\"}\n\n"
                            + "data: {\"type\":\"completed\",\"finishReason\":\"stop\"}\n\n"
            ).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        RuntimeModelStreamHttpClient client = new RuntimeModelStreamHttpClient(objectMapper, baseUrl);
        List<RuntimeModelStreamHttpClient.ModelStreamEventDto> events = new ArrayList<>();
        client.streamChatEvents(minimalRequest(), events::add);
        assertEquals(3, events.size());
        assertEquals("hel", events.get(0).text);
        assertEquals("lo", events.get(1).text);
        assertEquals("completed", events.get(2).type);
    }

    @Test
    void sseHeartbeatCommentsAreIgnored() {
        server.createContext("/model/chat/stream/events", exchange -> {
            byte[] body = (
                    ": heartbeat\n\n"
                            + "data: {\"type\":\"content.delta\",\"text\":\"ok\"}\n\n"
                            + ": heartbeat\n\n"
            ).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        RuntimeModelStreamHttpClient client = new RuntimeModelStreamHttpClient(objectMapper, baseUrl);
        List<RuntimeModelStreamHttpClient.ModelStreamEventDto> events = new ArrayList<>();
        client.streamChatEvents(minimalRequest(), events::add);
        assertEquals(1, events.size());
        assertEquals("ok", events.get(0).text);
    }

    private static RuntimeModelServiceClient.ModelChatRequest minimalRequest() {
        return RuntimeModelServiceClient.ModelChatRequest.builder()
                .modelInstanceId("model-1")
                .messages(List.of(RuntimeModelServiceClient.ModelChatRequest.ChatMessage.builder()
                        .role("user")
                        .content("hi")
                        .build()))
                .build();
    }
}

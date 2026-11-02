package com.enterprise.ai.reach.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Field;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachAiHttpRegistryTransportTest {

    @Test
    void doesNotExposeErrorResponseBodies() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/error", exchange -> {
            byte[] body = "secret-upstream-response".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            IOException failure = assertThrows(IOException.class, () -> new ReachAiHttpRegistryTransport().exchange(
                    "GET", "http://127.0.0.1:" + server.getAddress().getPort() + "/error", Collections.emptyMap(), null));
            assertTrue(failure.getMessage().contains("status=403"));
            assertFalse(failure.getMessage().contains("secret-upstream-response"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void limitsSuccessfulResponseSize() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/large", exchange -> {
            byte[] body = new byte[8 * 1024 * 1024 + 1];
            exchange.sendResponseHeaders(200, body.length);
            try { exchange.getResponseBody().write(body); } catch (IOException ignored) { }
            exchange.close();
        });
        server.start();
        try {
            IOException failure = assertThrows(IOException.class, () -> new ReachAiHttpRegistryTransport().exchange(
                    "GET", "http://127.0.0.1:" + server.getAddress().getPort() + "/large", Collections.emptyMap(), null));
            assertTrue(failure.getMessage().contains("response too large"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void neverFollowsRedirectsForSignedRequests() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger redirectedRequests = new AtomicInteger();
        server.createContext("/target", exchange -> {
            redirectedRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            int status = Integer.parseInt(exchange.getRequestURI().getQuery());
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try {
            ReachAiHttpRegistryTransport transport = new ReachAiHttpRegistryTransport();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            for (int status : new int[]{302, 307, 308}) {
                IOException failure = assertThrows(IOException.class, () -> transport.exchange("POST",
                        base + "/redirect?" + status, Collections.singletonMap("X-ReachAI-App-Key", "test-key"),
                        "payload"));
                assertEquals(true, failure.getMessage().contains("status=" + status));
            }
            assertEquals(0, redirectedRequests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesSixtySecondReadTimeoutByDefault() throws Exception {
        ReachAiHttpRegistryTransport transport = new ReachAiHttpRegistryTransport();
        Field field = ReachAiHttpRegistryTransport.class.getDeclaredField("readTimeoutMs");
        field.setAccessible(true);

        assertEquals(60000, field.getInt(transport));
    }

    @Test
    void registryPropertiesConfigureTheActualTransportWhileRegistrationIsDisabled() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ReachAiRegistryAutoConfiguration.class))
                .withPropertyValues("reachai.registry.enabled=false", "reachai.registry.connect-timeout-ms=3000",
                        "reachai.registry.read-timeout-ms=120000")
                .run(context -> {
                    ReachAiHttpRegistryTransport transport = (ReachAiHttpRegistryTransport) context.getBean(ReachAiRegistryTransport.class);
                    assertEquals(3000, timeout(transport, "connectTimeoutMs"));
                    assertEquals(120000, timeout(transport, "readTimeoutMs"));
                });
    }

    @Test
    void rejectsUnboundedOrExcessiveTransportTimeouts() {
        ReachAiHttpRegistryTransport transport = new ReachAiHttpRegistryTransport();
        assertThrows(IllegalArgumentException.class, () -> transport.setReadTimeoutMs(0));
        assertThrows(IllegalArgumentException.class, () -> transport.setReadTimeoutMs(240001));
        assertThrows(IllegalArgumentException.class, () -> transport.setConnectTimeoutMs(0));
        assertThrows(IllegalArgumentException.class, () -> transport.setConnectTimeoutMs(30001));
    }

    @Test
    void rejectsInvalidTimeoutConfigurationAtStartup() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ReachAiRegistryAutoConfiguration.class))
                .withPropertyValues("reachai.registry.enabled=false", "reachai.registry.read-timeout-ms=0")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    private static int timeout(ReachAiHttpRegistryTransport transport, String name) throws Exception {
        Field field = ReachAiHttpRegistryTransport.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(transport);
    }
}

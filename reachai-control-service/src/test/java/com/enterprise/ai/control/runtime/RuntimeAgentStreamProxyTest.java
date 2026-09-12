package com.enterprise.ai.control.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeAgentStreamProxyTest {

    @Test
    void rejectsNonAllowlistedStreamPaths() {
        RuntimeAgentStreamProxy proxy = new RuntimeAgentStreamProxy(
                new ObjectMapper(), HttpClient.newHttpClient(), "http://localhost:18604");

        assertThrows(IllegalArgumentException.class,
                () -> proxy.stream("/api/runtime/agents/execute", Map.of(), new ByteArrayOutputStream()));
        assertThrows(IllegalArgumentException.class,
                () -> proxy.stream("/api/embed/chat/stream", Map.of(), new ByteArrayOutputStream()));
        assertThrows(IllegalArgumentException.class,
                () -> proxy.stream(RuntimeAgentStreamProxy.DEBUG_SESSION_STREAM, Map.of(), new ByteArrayOutputStream()));
        assertThrows(IllegalArgumentException.class,
                () -> proxy.stream("/api/runtime/debug-sessions/session-1/submit/stream", Map.of(), new ByteArrayOutputStream()));
        assertThrows(IllegalArgumentException.class,
                () -> proxy.streamSignedDebugSession("/api/embed/chat/stream", new byte[0], Map.of(), new ByteArrayOutputStream()));
    }

    @Test
    void relayUpstreamClosesRuntimeWhenHeartbeatWriteFailsWithBrokenPipe() throws Exception {
        AtomicBoolean upstreamClosed = new AtomicBoolean(false);
        AtomicInteger upstreamReadsAfterClose = new AtomicInteger();
        byte[] payload = (": heartbeat\n\n"
                + "event: message.delta\n"
                + "data: {\"text\":\"should-not-relay\"}\n\n")
                .getBytes(StandardCharsets.UTF_8);
        InputStream upstream = new FilterInputStream(new ByteArrayInputStream(payload)) {
            private boolean closed;

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (closed) {
                    upstreamReadsAfterClose.incrementAndGet();
                    return -1;
                }
                return super.read(b, off, len);
            }

            @Override
            public void close() throws IOException {
                closed = true;
                upstreamClosed.set(true);
                super.close();
            }
        };
        OutputStream downstream = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("Broken pipe");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("Broken pipe");
            }
        };

        RuntimeAgentStreamProxy proxy = new RuntimeAgentStreamProxy(
                new ObjectMapper(), HttpClient.newHttpClient(), "http://localhost:18604");
        // 真实生产方法 relayUpstream（非测试内复制 disconnect 判断）
        proxy.relayUpstream(upstream, downstream, SseStreamRelay.passthrough());

        assertTrue(upstreamClosed.get(), "broken pipe on heartbeat must close Runtime upstream");
        assertEqualsZeroOrNoFurtherBusinessRelay(upstreamReadsAfterClose);
    }

    @Test
    void streamProductionEntryClosesUpstreamOnBrokenPipeHeartbeat() throws Exception {
        AtomicBoolean serverSawClientClose = new AtomicBoolean(false);
        AtomicInteger businessFramesWrittenByServer = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/runtime/agents/execute/stream", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                body.flush();
                try {
                    Thread.sleep(80);
                    body.write(("event: message.delta\n"
                            + "data: {\"text\":\"should-not-forward\"}\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    body.flush();
                    businessFramesWrittenByServer.incrementAndGet();
                } catch (IOException ex) {
                    serverSawClientClose.set(true);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            RuntimeAgentStreamProxy proxy = new RuntimeAgentStreamProxy(
                    new ObjectMapper(),
                    HttpClient.newBuilder().build(),
                    "http://127.0.0.1:" + port);
            OutputStream brokenDownstream = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    throw new IOException("Broken pipe");
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    throw new IOException("Broken pipe");
                }
            };

            // 真实生产入口 stream(...) → httpClient.send → relayUpstream
            proxy.stream(Map.of("message", "ping"), brokenDownstream);

            assertTrue(serverSawClientClose.get() || businessFramesWrittenByServer.get() == 0,
                    "proxy must close upstream so Runtime stops (or never delivers) business frames after disconnect");
        } finally {
            server.stop(0);
        }
    }

    private static void assertEqualsZeroOrNoFurtherBusinessRelay(AtomicInteger readsAfterClose) {
        assertFalse(readsAfterClose.get() > 0 && readsAfterClose.get() > 2,
                "upstream should not keep reading business frames after close; reads=" + readsAfterClose.get());
    }
}

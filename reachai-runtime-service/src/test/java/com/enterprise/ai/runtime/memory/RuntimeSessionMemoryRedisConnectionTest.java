package com.enterprise.ai.runtime.memory;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeSessionMemoryRedisConnectionTest {
    @Test
    void staleIdleConnectionIsDiscardedBeforeTheNextRead() throws Exception {
        try (var redis = new ClosingRedis(); var client = RuntimeSessionMemoryConfiguration.redisClient(redis.uri())) {
            assertNull(client.get("state"));
            redis.resetLastConnection();
            assertNull(client.get("state"), "the next turn must reconnect before reading state");
        }
    }

    @Test
    void defaultPoolReproducesTheObservedConnectionReset() throws Exception {
        try (var redis = new ClosingRedis(); var client = new JedisPooled(redis.uri())) {
            assertNull(client.get("state"));
            redis.resetLastConnection();
            var error = assertThrows(JedisConnectionException.class, () -> client.get("state"));
            assertInstanceOf(SocketException.class, error.getCause());
            assertTrue(error.getCause().getMessage().toLowerCase(java.util.Locale.ROOT).contains("reset"),
                    "the default pool must reproduce the observed connection reset");
        }
    }

    /** Minimal RESP server: emulate a Redis/network peer resetting an idle connection. */
    private static final class ClosingRedis implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0);
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final AtomicReference<Socket> lastConnection = new AtomicReference<>();

        ClosingRedis() throws IOException {
            workers.submit(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        lastConnection.set(socket);
                        workers.submit(() -> serve(socket));
                    } catch (IOException stopped) {
                        if (!server.isClosed()) throw new IllegalStateException(stopped);
                    }
                }
            });
        }

        URI uri() { return URI.create("redis://127.0.0.1:" + server.getLocalPort()); }

        void resetLastConnection() throws IOException {
            Socket socket = lastConnection.get();
            assertNotNull(socket);
            socket.setSoLinger(true, 0);
            socket.close();
        }

        private void serve(Socket socket) {
            try (socket) {
                InputStream input = socket.getInputStream();
                while (!socket.isClosed()) {
                    String header = line(input);
                    if (!header.startsWith("*")) throw new IOException("expected RESP array");
                    int count = Integer.parseInt(header.substring(1));
                    List<String> command = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        String length = line(input);
                        if (!length.startsWith("$")) throw new IOException("expected RESP bulk string");
                        int size = Integer.parseInt(length.substring(1));
                        byte[] bytes = input.readNBytes(size);
                        if (bytes.length != size || input.read() != '\r' || input.read() != '\n') {
                            throw new EOFException("incomplete RESP command");
                        }
                        command.add(new String(bytes, StandardCharsets.UTF_8));
                    }
                    String op = command.get(0);
                    String reply = "PING".equalsIgnoreCase(op) ? "+PONG\r\n"
                            : "GET".equalsIgnoreCase(op) ? "$-1\r\n" : "+OK\r\n";
                    socket.getOutputStream().write(reply.getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                }
            } catch (IOException ignored) {
                // Closing the connection is the condition under test.
            }
        }

        private static String line(InputStream input) throws IOException {
            StringBuilder result = new StringBuilder();
            for (;;) {
                int value = input.read();
                if (value < 0) throw new EOFException();
                if (value == '\r') {
                    if (input.read() != '\n') throw new IOException("invalid RESP line");
                    return result.toString();
                }
                result.append((char) value);
            }
        }

        @Override public void close() throws Exception {
            Socket socket = lastConnection.get();
            if (socket != null) socket.close();
            server.close();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}

package com.enterprise.ai.pipeline.document.artifact;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A local HTTP transport fixture; requests still pass through the real MinIO SDK. */
final class S3ArtifactTestServer implements AutoCloseable {
    private final HttpServer server;
    final List<String> requests = new CopyOnWriteArrayList<>();

    @FunctionalInterface
    interface Handler { void handle(HttpExchange exchange, List<String> requests) throws Exception; }

    S3ArtifactTestServer(Handler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            try {
                handler.handle(exchange, requests);
            } catch (Exception failure) {
                try { respond(exchange, 500, "<Error><Code>InternalError</Code><Message>fixture response failed</Message></Error>"); }
                catch (Exception ignored) { exchange.close(); }
            }
        });
        server.start();
    }

    MinioDocumentArtifactStore backend() {
        var properties = new DocumentArtifactProperties();
        properties.setType("s3");
        properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setAccessKey("fixture-access");
        properties.setSecretKey("fixture-secret");
        properties.setBucket("artifact-fixture");
        properties.setAutoCreateBucket(false);
        var backend = new MinioDocumentArtifactStore(properties);
        backend.initialize();
        return backend;
    }

    static void respond(HttpExchange exchange, int status, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.getResponseHeaders().set("Connection", "close");
        if (exchange.getRequestMethod().equals("HEAD") || status == 204) exchange.sendResponseHeaders(status, -1);
        else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Override public void close() { server.stop(0); }
}

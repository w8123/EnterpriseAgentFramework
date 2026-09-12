package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CapabilityReviewGatewayTest {

    private static final String SECRET = "control-capability-review-gateway-secret";

    @Test
    void sendsEveryReviewOperationThroughTheProjectScopedCapabilityRoutes() throws Exception {
        List<CapturedRequest> captured = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> capture(exchange, captured));
        server.start();
        try {
            CapabilityReviewGateway gateway = new CapabilityReviewGateway(
                    new InternalServiceAuthSigner(SECRET),
                    new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());

            gateway.createPendingSnapshot("orders", Map.of("syncId", "sync-1"), "7");
            gateway.listSnapshots("orders", "7");
            gateway.listChanges("orders", "PENDING", "订单 & status=ALL", 2, 500, "7");
            gateway.listDiffItems("orders", 11L, "7");
            gateway.reviewDiffItem("orders", 12L, Map.of("action", "APPLY"), "7");
            gateway.rollbackDiffItem("orders", 12L, Map.of("note", "restore"), "7");

            assertEquals(List.of(
                    new CapturedRequest("POST", "/api/registry/projects/orders/capabilities/diff"),
                    new CapturedRequest("GET", "/api/registry/projects/orders/capability-snapshots"),
                    new CapturedRequest("GET", "/api/registry/projects/orders/capability-changes"),
                    new CapturedRequest("GET",
                            "/api/registry/projects/orders/capability-snapshots/11/diff-items"),
                    new CapturedRequest("POST",
                            "/api/registry/projects/orders/capability-diff-items/12/review"),
                    new CapturedRequest("POST",
                            "/api/registry/projects/orders/capability-diff-items/12/rollback")
            ), captured);
        } finally {
            server.stop(0);
        }
    }

    private static void capture(HttpExchange exchange,
                                List<CapturedRequest> captured) throws java.io.IOException {
        exchange.getRequestBody().readAllBytes();
        captured.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath()));
        byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private record CapturedRequest(String method, String path) {
    }
}

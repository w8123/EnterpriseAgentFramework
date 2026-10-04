package com.enterprise.ai.control.capability;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
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
import java.util.LinkedHashMap;
import java.util.Locale;
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

    @Test
    void encodesCatalogQueryAndSignsTheExactOwnerReadPath() throws Exception {
        List<CatalogRequest> captured = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> captureCatalog(exchange, captured));
        server.start();
        try {
            CapabilityReviewGateway gateway = new CapabilityReviewGateway(
                    new InternalServiceAuthSigner(SECRET),
                    new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());

            gateway.listCapabilities(2, 500, "订单 & status=ALL#+/", "code+/source",
                    false, 7L, "42");
            gateway.listBusinessMethods(3, 1, "orders / create", true, 7L, "42");
            gateway.getBusinessMethod("orders_create", "42");
            gateway.getProjectById(7L, "42");

            assertEquals(4, captured.size());
            CatalogRequest list = captured.get(0);
            assertEquals("GET", list.method());
            assertEquals("/api/tools", list.path());
            assertEquals("current=2&size=100&keyword=%E8%AE%A2%E5%8D%95%20%26%20status%3DALL%23%2B%2F"
                    + "&source=code%2B%2Fsource&enabled=false&projectId=7", list.query());
            assertSigned(list);

            CatalogRequest businessMethods = captured.get(1);
            assertEquals("GET", businessMethods.method());
            assertEquals("/internal/capability/business-methods", businessMethods.path());
            assertEquals("current=3&size=1&keyword=orders%20%2F%20create&enabled=true&projectId=7",
                    businessMethods.query());
            assertSigned(businessMethods);

            CatalogRequest businessMethod = captured.get(2);
            assertEquals("GET", businessMethod.method());
            assertEquals("/internal/capability/business-methods/orders_create", businessMethod.path());
            assertEquals(null, businessMethod.query());
            assertSigned(businessMethod);

            CatalogRequest project = captured.get(3);
            assertEquals("GET", project.method());
            assertEquals("/internal/capability/projects/by-id/7", project.path());
            assertEquals(null, project.query());
            assertSigned(project);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void marketReadsAndSelectionWritesSignTheExactOwnerPathActorAndBody() throws Exception {
        List<CatalogRequest> captured = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> captureCatalog(exchange, captured)); server.start();
        try {
            var gateway = new CapabilityReviewGateway(new InternalServiceAuthSigner(SECRET), new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), HttpClient.newHttpClient());
            gateway.readApiMarket("/integrations", Map.of("projectId", "41"), "42");
            gateway.readApiMarket("/integrations/1", Map.of(), "42");
            gateway.selectApiMarket("isolated-orders-alpha", Map.of("projectId", 41, "versionId", 21,
                    "operationIds", List.of(31), "note", "固定来源选择"), "42");
            gateway.updateApiMarketStatus(1, Map.of("status", "DISABLED"), "42");
            assertEquals(List.of("/internal/capability/api-market/integrations",
                    "/internal/capability/api-market/integrations/1",
                    "/internal/capability/api-market/entries/isolated-orders-alpha/integrations",
                    "/internal/capability/api-market/integrations/1/status"), captured.stream().map(CatalogRequest::path).toList());
            for (var request : captured) {
                assertSigned(request);
                assertEquals("42", request.headers().get(InternalServiceAuthHeaders.IDENTITY_USER_ID.toLowerCase(Locale.ROOT)));
                assertEquals(InternalServiceHmac.bodySha256Hex(request.body()),
                        request.headers().get(InternalServiceAuthHeaders.BODY_SHA256.toLowerCase(Locale.ROOT)));
            }
            assertEquals("固定来源选择", new ObjectMapper().readTree(captured.get(2).body()).path("note").asText());
        } finally { server.stop(0); }
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

    private static void captureCatalog(HttpExchange exchange,
                                       List<CatalogRequest> captured) throws java.io.IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name.toLowerCase(Locale.ROOT), values.get(0));
            }
        });
        captured.add(new CatalogRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(),
                headers, body));
        byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static void assertSigned(CatalogRequest request) {
        String caller = request.headers().get(InternalServiceAuthHeaders.CALLER.toLowerCase(Locale.ROOT));
        String source = request.headers().get(InternalServiceAuthHeaders.IDENTITY_SOURCE.toLowerCase(Locale.ROOT));
        String userId = request.headers().get(InternalServiceAuthHeaders.IDENTITY_USER_ID.toLowerCase(Locale.ROOT));
        String timestamp = request.headers().get(InternalServiceAuthHeaders.TIMESTAMP.toLowerCase(Locale.ROOT));
        String nonce = request.headers().get(InternalServiceAuthHeaders.NONCE.toLowerCase(Locale.ROOT));
        String bodyDigest = request.headers().get(InternalServiceAuthHeaders.BODY_SHA256.toLowerCase(Locale.ROOT));
        String signature = request.headers().get(InternalServiceAuthHeaders.SIGNATURE.toLowerCase(Locale.ROOT));
        String canonical = InternalServiceHmac.canonical(
                request.method(), request.path(), caller, source, userId,
                timestamp, nonce, bodyDigest);

        assertEquals(InternalServiceHmac.sign(SECRET, canonical), signature);
    }

    private record CapturedRequest(String method, String path) {
    }

    private record CatalogRequest(String method, String path, String query,
                                  Map<String, String> headers, byte[] body) {
    }
}

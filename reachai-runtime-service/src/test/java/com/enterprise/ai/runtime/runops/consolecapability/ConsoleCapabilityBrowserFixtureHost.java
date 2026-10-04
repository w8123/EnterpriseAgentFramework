package com.enterprise.ai.runtime.runops.consolecapability;

import com.enterprise.ai.capability.internalauth.CapabilityInternalAuthProperties;
import com.enterprise.ai.capability.internalauth.CapabilityInternalAuthVerifier;
import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import com.enterprise.ai.common.capability.CapabilityInvocationRequest;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.capability.CapabilityInvocationConsoleController;
import com.enterprise.ai.control.capability.CapabilityReviewGateway;
import com.enterprise.ai.control.capability.RuntimeConsoleCapabilityInvocationGateway;
import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogFeignClient;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityInternalAuthSigner;
import com.enterprise.ai.runtime.execution.capability.RuntimeCapabilityCatalogGateway;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.trace.RuntimeTraceRootService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Isolated browser fixture for BMAPI-2C-B.
 *
 * <p>The public HTTP endpoints call the production Control controller. Its Runtime
 * adapter delegates into a real H2-backed Runtime invocation service, which uses
 * the production Runtime signer/gateway against a loopback synthetic business
 * response handler verified by Capability's production HMAC verifier. It does
 * not invoke a real SDK Endpoint or Invoker, and deliberately has no real project
 * credential, user data, or external service dependency.</p>
 */
public final class ConsoleCapabilityBrowserFixtureHost {

    private static final String METHOD = "orders.lookup";
    private static final String PROJECT = "orders";
    private static final String HASH = "a".repeat(64);
    private static final String SDK_SECRET = "bmapi-browser-fixture-secret";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ConsoleCapabilityBrowserFixtureHost() {
    }

    private static Map<String, Object> map(Object... values) {
        if (values.length % 2 != 0) throw new IllegalArgumentException("map entries must be paired");
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 19613;
        Path readyFile = args.length > 1 ? Path.of(args[1]) : null;
        Fixture fixture = new Fixture(port);
        Runtime.getRuntime().addShutdownHook(new Thread(fixture::close, "bmapi-browser-fixture-close"));
        fixture.start();
        if (readyFile != null) {
            Files.createDirectories(readyFile.getParent());
            Files.writeString(readyFile, JSON.writeValueAsString(Map.of(
                    "port", port,
                    "syntheticResponsePort", fixture.syntheticResponsePort(),
                    "mode", fixture.mode().wireValue())) + System.lineSeparator(), StandardCharsets.UTF_8);
        }
        System.out.println("BMAPI_BROWSER_FIXTURE_READY port=" + port + " syntheticResponsePort=" + fixture.syntheticResponsePort());
        Thread.currentThread().join();
    }

    private static final class Fixture implements AutoCloseable {
        private final RuntimeQueryTestDatabase database;
        private final ConsoleCapabilityInvocationService runtime;
        private final SyntheticBusinessResponseHandler syntheticResponseHandler;
        private final HttpServer syntheticResponseServer;
        private final HttpServer publicServer;
        private final CapabilityInvocationConsoleController controller;
        private final MockHttpServletRequest request = new MockHttpServletRequest();
        private final AtomicInteger publicPostCount = new AtomicInteger();
        private final AtomicInteger publicGetCount = new AtomicInteger();
        /** Evidence only: record submitted top-level field names, never their values. */
        private volatile List<String> lastPublicInputKeys = List.of();

        Fixture(int port) throws Exception {
            database = new RuntimeQueryTestDatabase(List.of(
                    "runtime_run", "runtime_trace_span", "runtime_console_capability_invocation"),
                    RuntimeRunMapper.class, RuntimeTraceSpanMapper.class, ConsoleCapabilityInvocationMapper.class);
            syntheticResponseHandler = new SyntheticBusinessResponseHandler();
            syntheticResponseServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            syntheticResponseServer.createContext(RuntimeCapabilityInternalAuthSigner.CAPABILITY_INVOCATION_PATH, syntheticResponseHandler);
            syntheticResponseServer.start();

            RuntimeCapabilityCatalogGateway gateway = new RuntimeCapabilityCatalogGateway(
                    new LoopbackCapabilityInvocationClient(syntheticResponseServer.getAddress().getPort()),
                    new RuntimeCapabilityInternalAuthSigner(SDK_SECRET), JSON);
            runtime = new ConsoleCapabilityInvocationService(
                    database.mapper(ConsoleCapabilityInvocationMapper.class),
                    database.mapper(RuntimeRunMapper.class),
                    new RuntimeTraceRootService(database.mapper(RuntimeTraceSpanMapper.class), JSON),
                    gateway, JSON, new DataSourceTransactionManager(database.jdbc().getDataSource()));
            controller = controller(runtime);
            publicServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            publicServer.createContext("/api", this::handlePublic);
        }

        void start() {
            publicServer.start();
        }

        int syntheticResponsePort() {
            return syntheticResponseServer.getAddress().getPort();
        }

        Mode mode() {
            return syntheticResponseHandler.mode;
        }

        private CapabilityInvocationConsoleController controller(ConsoleCapabilityInvocationService service) {
            CapabilityReviewGateway capability = mock(CapabilityReviewGateway.class);
            RuntimeConsoleCapabilityInvocationGateway runtimeGateway = mock(RuntimeConsoleCapabilityInvocationGateway.class);
            PlatformRequestAuthorization authorization = mock(PlatformRequestAuthorization.class);
            ControlToolAclDecisionService acl = mock(ControlToolAclDecisionService.class);
            PlatformAuthenticatedSession session = new PlatformAuthenticatedSession(
                    new PlatformPrincipal(42L, "browser-fixture", "浏览器夹具"), "fixture-session",
                    LocalDateTime.now().plusHours(1), List.of("OPERATOR"),
                    List.of(PlatformPermissions.PLATFORM_READ, PlatformPermissions.CAPABILITY_INVOKE, PlatformPermissions.RUNOPS_READ),
                    List.of(new PlatformPermissionGrant(PlatformPermissions.PLATFORM_READ, "PROJECT", PROJECT),
                            new PlatformPermissionGrant(PlatformPermissions.CAPABILITY_INVOKE, "PROJECT", PROJECT),
                            new PlatformPermissionGrant(PlatformPermissions.RUNOPS_READ, "PROJECT", PROJECT)));
            when(authorization.requirePermission(any(), anyString())).thenReturn(session);
            when(authorization.requireResourcePermission(any(), anyString(), anyString(), any(), anyString())).thenReturn(session);
            when(acl.decide(any(), anyLong(), anyString(), eq("TOOL"), anyString()))
                    .thenReturn(ControlToolAclDecisionService.DECISION_ALLOW);
            when(capability.getBusinessMethodInvocationContext(eq(METHOD), eq("42")))
                    .thenAnswer(call -> ResponseEntity.ok(invocationContext()));
            when(runtimeGateway.invoke(any(ConsoleCapabilityInvocationContracts.InvocationCommand.class), eq("42")))
                    .thenAnswer(call -> {
                        ConsoleCapabilityInvocationContracts.InvocationCommand command = call.getArgument(0);
                        ConsoleCapabilityInvocationContracts.InvocationOutcome outcome = service.invoke(command);
                        if (syntheticResponseHandler.mode == Mode.EXPIRED_RESULT) {
                            // The production retention setting deliberately has a one-hour lower bound.
                            // This isolated H2-only seam simulates elapsed retention, then asks the real
                            // Runtime read path to clear the result and project the expired outcome.
                            database.jdbc().update("UPDATE runtime_console_capability_invocation "
                                            + "SET result_expires_at = ? WHERE invocation_id = ?",
                                    LocalDateTime.now().minusSeconds(1), command.invocationId());
                            outcome = service.get(command.invocationId(), "42");
                        }
                        return ResponseEntity.ok(wire(outcome));
                    });
            when(runtimeGateway.get(anyString(), eq("42")))
                    .thenAnswer(call -> {
                        ConsoleCapabilityInvocationContracts.InvocationOutcome outcome = service.get(call.getArgument(0), "42");
                        return outcome == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(wire(outcome));
                    });
            return new CapabilityInvocationConsoleController(capability, runtimeGateway, authorization, acl, JSON);
        }

        private void handlePublic(HttpExchange exchange) throws IOException {
            try {
                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type,X-ReachAI-CSRF");
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                    return;
                }
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                if ("GET".equals(method) && "/api/platform/auth/me".equals(path)) {
                    write(exchange, 200, sessionView());
                    return;
                }
                if ("GET".equals(method) && "/api/scan-projects".equals(path)) {
                    write(exchange, 200, List.of(scanProject()));
                    return;
                }
                if ("GET".equals(method) && "/api/business-methods".equals(path)) {
                    write(exchange, 200, Map.of("records", List.of(toolInfo()), "total", 1, "size", 20, "current", 1, "pages", 1));
                    return;
                }
                if ("GET".equals(method) && "/api/business-methods/".concat(METHOD).equals(path)) {
                    write(exchange, 200, toolInfo());
                    return;
                }
                if ("GET".equals(method) && "/api/business-methods/".concat(METHOD).concat("/invocation-context").equals(path)) {
                    writeController(exchange, controller.context(request, METHOD));
                    return;
                }
                if ("POST".equals(method) && "/api/business-methods/".concat(METHOD).concat("/invocations").equals(path)) {
                    publicPostCount.incrementAndGet();
                    Map<String, Object> body = JSON.readValue(exchange.getRequestBody().readAllBytes(), new TypeReference<>() { });
                    lastPublicInputKeys = inputKeys(body.get("input"));
                    writeController(exchange, controller.invoke(request, METHOD, body));
                    return;
                }
                if ("GET".equals(method) && path.startsWith("/api/business-method-invocations/")) {
                    publicGetCount.incrementAndGet();
                    String id = path.substring("/api/business-method-invocations/".length());
                    writeController(exchange, controller.get(request, id));
                    return;
                }
                if ("GET".equals(method) && "/api/fixture/evidence".equals(path)) {
                    write(exchange, 200, Map.of(
                            "publicPosts", publicPostCount.get(),
                            "publicGets", publicGetCount.get(),
                            "syntheticBusinessRequests", syntheticResponseHandler.requests.get(),
                            "verifiedInternalRequests", syntheticResponseHandler.verifiedRequests.get(),
                            "lastPublicInputKeys", lastPublicInputKeys,
                            "mode", syntheticResponseHandler.mode.wireValue()));
                    return;
                }
                if ("POST".equals(method) && path.startsWith("/api/fixture/mode/")) {
                    String value = path.substring("/api/fixture/mode/".length());
                    syntheticResponseHandler.mode = Mode.fromWire(value);
                    write(exchange, 200, Map.of("mode", syntheticResponseHandler.mode.wireValue()));
                    return;
                }
                write(exchange, 404, Map.of("success", false, "code", "FIXTURE_NOT_FOUND", "message", "fixture route not found"));
            } catch (Exception failure) {
                write(exchange, 500, Map.of("success", false, "code", "FIXTURE_ERROR", "message", failure.getClass().getSimpleName()));
            }
        }

        private Map<String, Object> sessionView() {
            return map(
                    "sessionId", "fixture-session",
                    "expiresAt", "2030-01-01T00:00:00",
                    "principal", Map.of(
                            "userId", 42,
                            "username", "browser-fixture",
                            "displayName", "浏览器夹具",
                            "permissions", List.of("platform:read", "capability:invoke", "runops:read"),
                            "permissionGrants", List.of(
                                    Map.of("permissionCode", "platform:read", "scopeType", "PROJECT", "scopeValue", PROJECT),
                                    Map.of("permissionCode", "capability:invoke", "scopeType", "PROJECT", "scopeValue", PROJECT),
                                    Map.of("permissionCode", "runops:read", "scopeType", "PROJECT", "scopeValue", PROJECT))));
        }

        /** Minimal project-catalog record required only to select this isolated fixture scope in the real UI. */
        private Map<String, Object> scanProject() {
            return map(
                    "id", 7, "name", "订单项目", "projectCode", PROJECT,
                    "projectKind", "REGISTERED", "environment", "fixture", "owner", "browser-fixture",
                    "visibility", "PROJECT", "baseUrl", "http://127.0.0.1", "contextPath", "",
                    "scanPath", "", "scanType", "auto", "toolCount", 1, "status", "scanned");
        }

        private Map<String, Object> toolInfo() {
            return map(
                    "assetType", "BUSINESS_METHOD", "name", METHOD, "title", "查询订单",
                    "description", "隔离浏览器验收用的订单查询业务方法。", "parameters", parameters(),
                    "source", "sdk", "sourceLocation", "@ReachCapability", "projectId", 7,
                    "projectCode", PROJECT, "qualifiedName", METHOD, "sourceQualifiedName", METHOD,
                    "sourceProjectName", "订单项目", "sideEffect", syntheticResponseHandler.mode.sideEffect(),
                    "sourceAvailability", "READY", "enabled", true);
        }

        private Map<String, Object> invocationContext() {
            return map(
                    "contractVersion", 1, "name", METHOD, "qualifiedName", METHOD, "sourceQualifiedName", METHOD,
                    "assetType", "BUSINESS_METHOD", "projectId", 7, "projectCode", PROJECT,
                    "currentContractHash", HASH, "acceptedContractHash", HASH, "sourceContractHash", HASH,
                    "sourceAvailability", "READY", "enabled", true, "sideEffect", syntheticResponseHandler.mode.sideEffect(),
                    "parameters", parameters(),
                    "requestBodyType", "json", "responseType", "json", "targetDescription", "订单项目隔离合成业务响应夹具",
                    "targetInstanceStatus", "READY", "credentialAvailable", true, "businessIdentityRequired", false,
                    "executable", true, "timeoutMs", 3_000L);
        }

        /** The sensitive mode exists only to exercise the real browser editor's masking projection. */
        private List<Map<String, Object>> parameters() {
            if (syntheticResponseHandler.mode == Mode.OPTIONAL_FIELD) {
                return List.of(
                        map("name", "orderNo", "type", "string", "description", "订单号", "required", true,
                                "location", "body", "children", List.of(), "metadata", Map.of()),
                        map("name", "memo", "type", "string", "description", "可选备注", "required", false,
                                "location", "body", "children", List.of(), "metadata", Map.of()));
            }
            if (syntheticResponseHandler.mode == Mode.INVALID_FORM) {
                return List.of(map("name", "count", "type", "integer", "description", "数量", "required", true,
                        "location", "body", "children", List.of(), "metadata", Map.of()));
            }
            if (syntheticResponseHandler.mode == Mode.DTO_SENSITIVE) {
                return List.of(map("name", "request", "type", "object", "description", "敏感请求对象", "required", true,
                        "location", "body", "children", List.of(
                                map("name", "token", "type", "string", "description", "令牌", "required", true,
                                        "location", "body", "children", List.of(), "metadata", Map.of("sensitive", true)),
                                map("name", "profile", "type", "object", "description", "敏感嵌套对象", "required", true,
                                        "location", "body", "children", List.of(
                                                map("name", "secret", "type", "string", "description", "密钥", "required", true,
                                                        "location", "body", "children", List.of(), "metadata", Map.of("sensitive", true))),
                                        "metadata", Map.of()),
                                map("name", "records", "type", "array", "description", "敏感记录", "required", true,
                                        "location", "body", "children", List.of(
                                                map("name", "pin", "type", "string", "description", "PIN", "required", true,
                                                        "location", "body", "children", List.of(), "metadata", Map.of("sensitive", true))),
                                        "metadata", Map.of())),
                        "metadata", Map.of()));
            }
            if (syntheticResponseHandler.mode != Mode.SENSITIVE) {
                return List.of(map("name", "orderNo", "type", "string", "description", "订单号",
                        "required", true, "location", "body", "children", List.of(), "metadata", Map.of()));
            }
            return List.of(
                    map("name", "approved", "type", "boolean", "description", "审批标记", "required", true,
                            "location", "body", "children", List.of(), "metadata", Map.of("sensitive", true)),
                    map("name", "request", "type", "object", "description", "敏感请求对象", "required", true,
                            "location", "body", "children", List.of(
                                    map("name", "token", "type", "string", "description", "令牌", "required", true,
                                            "location", "body", "children", List.of(), "metadata", Map.of("sensitive", true))),
                            "metadata", Map.of()),
                    map("name", "records", "type", "array", "description", "敏感记录列表", "required", true,
                            "location", "body", "children", List.of(
                                    map("name", "pin", "type", "string", "description", "PIN", "required", true,
                                            "location", "body", "children", List.of(), "metadata", Map.of("sensitive", true))),
                            "metadata", Map.of()));
        }

        private List<String> inputKeys(Object input) {
            if (!(input instanceof Map<?, ?> values)) return List.of();
            List<String> keys = new ArrayList<>();
            for (Object key : values.keySet()) keys.add(String.valueOf(key));
            return List.copyOf(keys);
        }

        private Map<String, Object> wire(ConsoleCapabilityInvocationContracts.InvocationOutcome outcome) {
            return JSON.convertValue(outcome, new TypeReference<LinkedHashMap<String, Object>>() { });
        }

        private void writeController(HttpExchange exchange, ResponseEntity<Object> response) throws IOException {
            write(exchange, response.getStatusCode().value(), response.getBody());
        }

        private void write(HttpExchange exchange, int status, Object body) throws IOException {
            byte[] bytes = body == null ? new byte[0] : JSON.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            publicServer.stop(0);
            syntheticResponseServer.stop(0);
            database.close();
        }
    }

    private enum Mode {
        SUCCESS("success", "READ_ONLY"),
        BUSINESS_FAILURE("business-failure", "READ_ONLY"),
        CONNECTION_CLOSE("connection-close", "READ_ONLY"),
        REJECTED("rejected", "READ_ONLY"),
        EXPIRED_RESULT("expired-result", "READ_ONLY"),
        SENSITIVE("sensitive", "READ_ONLY"),
        OPTIONAL_FIELD("optional-field", "READ_ONLY"),
        INVALID_FORM("invalid-form", "WRITE"),
        DTO_SENSITIVE("dto-sensitive", "READ_ONLY"),
        WRITE("write", "WRITE"),
        LARGE_RESULT("large-result", "READ_ONLY");

        private final String wireValue;
        private final String sideEffect;

        Mode(String wireValue, String sideEffect) {
            this.wireValue = wireValue;
            this.sideEffect = sideEffect;
        }

        String wireValue() {
            return wireValue;
        }

        String sideEffect() {
            return sideEffect;
        }

        static Mode fromWire(String value) {
            for (Mode candidate : values()) if (Objects.equals(candidate.wireValue, value)) return candidate;
            throw new IllegalArgumentException("Unknown fixture mode");
        }
    }

    /** Production Runtime gateway signs this loopback call; Capability verifies it before returning a synthetic response. */
    private static final class LoopbackCapabilityInvocationClient implements RuntimeCapabilityCatalogFeignClient {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        private final URI uri;

        LoopbackCapabilityInvocationClient(int port) {
            uri = URI.create("http://127.0.0.1:" + port + RuntimeCapabilityInternalAuthSigner.CAPABILITY_INVOCATION_PATH);
        }

        @Override
        public CapabilityInvocationResponse invokeCapability(Map<String, String> headers, byte[] exactBody) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(exactBody));
                headers.forEach(request::header);
                HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("fixture synthetic business HTTP " + response.statusCode());
                }
                return JSON.readValue(response.body(), CapabilityInvocationResponse.class);
            } catch (Exception failure) {
                throw new IllegalStateException("fixture synthetic business transport failed", failure);
            }
        }

        @Override public Map<String, Object> getToolDefinition(String qualifiedName) { throw unsupported(); }
        @Override public CapabilityInvocationResponse invokeTool(String qualifiedName, Map<String, String> headers, byte[] body) { throw unsupported(); }
        @Override public Map<String, Object> getCompositionDefinition(String qualifiedName) { throw unsupported(); }
        @Override public Map<String, Object> getProject(String projectCode) { throw unsupported(); }
        @Override public Map<String, Object> getProjectById(Long projectId) { throw unsupported(); }
        @Override public List<Map<String, Object>> listProjectTools(Long projectId) { throw unsupported(); }
        @Override public Map<String, Object> projectReadinessFacts(Long projectId) { throw unsupported(); }
        @Override public com.enterprise.ai.common.capability.HttpApiConsoleContracts.ExecutionContext httpApiExecutionContext(
                Long apiId, Map<String, String> headers, byte[] body) { throw unsupported(); }
        @Override public com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts.InvocationContext businessMethodExecutionContext(
                String qualifiedName, Map<String, String> headers, byte[] body) { throw unsupported(); }

        private UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by browser fixture");
        }
    }

    private static final class SyntheticBusinessResponseHandler implements com.sun.net.httpserver.HttpHandler {
        private final CapabilityInternalAuthVerifier verifier = new CapabilityInternalAuthVerifier(
                new CapabilityInternalAuthProperties(SDK_SECRET, 300, 600, 1000, 1_048_576),
                (caller, nonce, now, ttl, max) -> true, JSON);
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger verifiedRequests = new AtomicInteger();
        private volatile Mode mode = Mode.SUCCESS;

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] body = exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            boolean verified = verifier.verifyToolExecution(
                    exchange.getRequestMethod(), exchange.getRequestURI().getPath(), header(exchange, InternalServiceAuthHeaders.CALLER),
                    header(exchange, InternalServiceAuthHeaders.IDENTITY_SOURCE), header(exchange, InternalServiceAuthHeaders.IDENTITY_TENANT_ID),
                    header(exchange, InternalServiceAuthHeaders.IDENTITY_USER_ID), header(exchange, InternalServiceAuthHeaders.TIMESTAMP),
                    header(exchange, InternalServiceAuthHeaders.NONCE), header(exchange, InternalServiceAuthHeaders.BODY_SHA256),
                    header(exchange, InternalServiceAuthHeaders.SIGNATURE), body, System.currentTimeMillis()).isPresent();
            if (!verified) {
                write(exchange, 401, Map.of("code", "HMAC_INVALID"));
                return;
            }
            verifiedRequests.incrementAndGet();
            if (mode == Mode.CONNECTION_CLOSE) {
                exchange.close();
                return;
            }
            CapabilityInvocationRequest request = CapabilityInvocationRequest.fromWire(
                    JSON.readValue(body, new TypeReference<Map<String, Object>>() { }));
            if (mode == Mode.BUSINESS_FAILURE) {
                write(exchange, 200, response(request, CapabilityInvocationStatus.BUSINESS_FAILED, false,
                        Map.of("reason", "ORDER_CLOSED"), "ORDER_CLOSED", CapabilityInvocationFailureCategory.BUSINESS_RESPONSE));
                return;
            }
            if (mode == Mode.REJECTED) {
                write(exchange, 200, response(request, CapabilityInvocationStatus.REJECTED, false,
                        Map.of("reason", "FIXTURE_INPUT_REJECTED"), "FIXTURE_INPUT_REJECTED",
                        CapabilityInvocationFailureCategory.POLICY_REJECTED));
                return;
            }
            Object data = mode == Mode.LARGE_RESULT
                    ? Map.of("payload", "x".repeat(70_000))
                    : Map.of("accepted", true, "syntheticBusinessResponse", "hmac-verified");
            write(exchange, 200, response(request, CapabilityInvocationStatus.SUCCEEDED, true, data, null,
                    CapabilityInvocationFailureCategory.NONE));
        }

        private CapabilityInvocationResponse response(CapabilityInvocationRequest request,
                                                      CapabilityInvocationStatus status,
                                                      boolean success,
                                                      Object data,
                                                      String code,
                                                      CapabilityInvocationFailureCategory category) {
            return new CapabilityInvocationResponse(1, request.invocationId(), request.qualifiedName(),
                    "lookup", "Lookup", status, success, data, code, code, category, false,
                    1L, 1, code, Map.of("statusCode", 200));
        }

        private String header(HttpExchange exchange, String name) {
            return exchange.getRequestHeaders().getFirst(name);
        }

        private void write(HttpExchange exchange, int status, Object body) throws IOException {
            byte[] bytes = JSON.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}

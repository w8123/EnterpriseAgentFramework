package com.enterprise.ai.capability.internal;

import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.capability.registry.CapabilityChangeLifecycle;
import com.enterprise.ai.capability.registry.CapabilityChangePolicy;
import com.enterprise.ai.capability.registry.CapabilitySourceStateEntity;
import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationClaims;
import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Local HTTP evidence for the real outbound path; no development project endpoint is touched. */
class ConsoleCapabilityLocalHttpInvocationTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsProjectCredentialButNoPlatformOrBusinessIdentityToTheSdkEndpoint() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/orders/lookup", exchange -> {
            requests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst(ReachAiInvocationToken.HEADER_NAME));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"success\":true,\"data\":{\"orderNo\":\"O-42\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        ToolDefinitionMapper tools = mock(ToolDefinitionMapper.class);
        RegistrySecurityService credentials = mock(RegistrySecurityService.class);
        ToolDefinitionEntity tool = tool();
        tool.setParametersJson("[{\"name\":\"orderNo\",\"type\":\"string\",\"required\":true}]");
        when(tools.selectOne(any())).thenReturn(tool);
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("orders");
        credential.setAppKey("orders-app");
        credential.setAppSecret("local-test-secret");
        when(credentials.findPrimaryActiveCredential("orders")).thenReturn(Optional.of(credential));
        CapabilityOutboundTransportPolicy transport = mock(CapabilityOutboundTransportPolicy.class);
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(tools,
                new DefaultCapabilityHttpToolInvoker(new RestTemplateBuilder(), transport, new ObjectMapper()),
                credentials, acceptedGuard(tool));

        Map<String, Object> response = service.execute("orders.lookup", Map.of(
                "invocationId", "b5da114f-c4bf-4c56-a624-9263d7efbe7b",
                "input", Map.of("orderNo", "O-42"),
                "context", Map.of(),
                "deadlineEpochMs", System.currentTimeMillis() + 10_000L,
                "idempotencyKey", "b5da114f-c4bf-4c56-a624-9263d7efbe7b",
                "constraints", Map.of(
                        "consoleCapabilityInvocation", true,
                        "expectedQualifiedName", "orders.lookup",
                        "expectedProjectCode", "orders",
                        "expectedProjectId", 7L,
                        "expectedContractHash", "a".repeat(64),
                        "requireSignedInvocation", true)));

        assertEquals(1, requests.get());
        assertTrueJson(body.get(), "orderNo", "O-42");
        ReachAiInvocationClaims claims = ReachAiInvocationToken.verify("local-test-secret", authorization.get(),
                "orders", "orders.lookup", System.currentTimeMillis());
        assertEquals("orders", claims.getProjectCode());
        assertEquals("orders-app", claims.getAppKey());
        assertNull(claims.getTenantId());
        assertNull(claims.getExternalUserId());
        assertNull(claims.getGlobalUserId());
        assertNull(claims.getUserName());
        assertTrueJson(new ObjectMapper().writeValueAsString(claims.getRoles()), "", null);
        assertEquals(true, response.get("success"));
    }

    @Test
    void requiredBusinessRolesFailBeforeAnyHttpRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/orders/lookup", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        ToolDefinitionMapper tools = mock(ToolDefinitionMapper.class);
        RegistrySecurityService credentials = mock(RegistrySecurityService.class);
        ToolDefinitionEntity tool = tool();
        tool.setCapabilityMetadataJson("{\"requiredRoles\":[\"order-reader\"]}");
        when(tools.selectOne(any())).thenReturn(tool);
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setAppKey("orders-app"); credential.setAppSecret("local-test-secret");
        when(credentials.findPrimaryActiveCredential("orders")).thenReturn(Optional.of(credential));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(tools,
                new DefaultCapabilityHttpToolInvoker(new RestTemplateBuilder(),
                        mock(CapabilityOutboundTransportPolicy.class), new ObjectMapper()),
                credentials, acceptedGuard(tool));

        CapabilityInvocationPolicyException rejected = assertThrows(CapabilityInvocationPolicyException.class,
                () -> service.execute("orders.lookup", Map.of("input", Map.of("orderNo", "O-43"), "context", Map.of(),
                        "constraints", Map.of("consoleCapabilityInvocation", true,
                                "expectedQualifiedName", "orders.lookup", "expectedProjectCode", "orders",
                                "expectedProjectId", 7L, "expectedContractHash", "a".repeat(64),
                                "requireSignedInvocation", true))));
        assertEquals("CAPABILITY_BUSINESS_IDENTITY_REQUIRED", rejected.code());
        assertEquals(0, requests.get());
    }

    @Test
    void missingRequiredDeclaredInputFailsBeforeAnyHttpRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/orders/lookup", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        ToolDefinitionMapper tools = mock(ToolDefinitionMapper.class);
        RegistrySecurityService credentials = mock(RegistrySecurityService.class);
        ToolDefinitionEntity tool = tool();
        tool.setParametersJson("[{\"name\":\"orderNo\",\"type\":\"string\",\"required\":true}]");
        when(tools.selectOne(any())).thenReturn(tool);
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setAppKey("orders-app"); credential.setAppSecret("local-test-secret");
        when(credentials.findPrimaryActiveCredential("orders")).thenReturn(Optional.of(credential));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(tools,
                new DefaultCapabilityHttpToolInvoker(new RestTemplateBuilder(),
                        mock(CapabilityOutboundTransportPolicy.class), new ObjectMapper()),
                credentials, acceptedGuard(tool));

        CapabilityInvocationPolicyException rejected = assertThrows(CapabilityInvocationPolicyException.class,
                () -> service.execute("orders.lookup", Map.of("input", Map.of(), "context", Map.of(),
                        "constraints", Map.of("consoleCapabilityInvocation", true,
                                "expectedQualifiedName", "orders.lookup", "expectedProjectCode", "orders",
                                "expectedProjectId", 7L, "expectedContractHash", "a".repeat(64),
                                "requireSignedInvocation", true))));

        assertEquals("CAPABILITY_INPUT_INVALID", rejected.code());
        assertEquals(0, requests.get());
    }

    @Test
    void acceptedScalarDtoAndArrayDeclarationsReachTheLocalSdkHandler() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/orders/lookup", exchange -> {
            requests.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"success\":true,\"data\":{\"ok\":true}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        ToolDefinitionMapper tools = mock(ToolDefinitionMapper.class);
        RegistrySecurityService credentials = mock(RegistrySecurityService.class);
        ToolDefinitionEntity tool = tool();
        tool.setParametersJson("["
                + "{\"name\":\"orderNo\",\"type\":\"string\",\"required\":true,\"metadata\":{\"enum\":[\"O-1\"]}},"
                + "{\"name\":\"request\",\"type\":\"object\",\"required\":true,\"children\":[{\"name\":\"quantity\",\"type\":\"integer\",\"required\":true,\"metadata\":{\"minimum\":1}}]},"
                + "{\"name\":\"items\",\"type\":\"array\",\"required\":true,\"children\":[{\"name\":\"sku\",\"type\":\"string\",\"required\":true}]}] ");
        when(tools.selectOne(any())).thenReturn(tool);
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setAppKey("orders-app"); credential.setAppSecret("local-test-secret");
        when(credentials.findPrimaryActiveCredential("orders")).thenReturn(Optional.of(credential));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(tools,
                new DefaultCapabilityHttpToolInvoker(new RestTemplateBuilder(),
                        mock(CapabilityOutboundTransportPolicy.class), new ObjectMapper()),
                credentials, acceptedGuard(tool));

        service.execute("orders.lookup", consoleRequest(Map.of("orderNo", "O-1",
                "request", Map.of("quantity", 2), "items", java.util.List.of(Map.of("sku", "SKU-1")))));

        assertEquals(1, requests.get());
        assertTrueJson(body.get(), "orderNo", "O-1");
    }

    @Test
    void declaredTypeEnumAndBoundaryErrorsReachNoHttpHandler() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/orders/lookup", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        ToolDefinitionMapper tools = mock(ToolDefinitionMapper.class);
        RegistrySecurityService credentials = mock(RegistrySecurityService.class);
        ToolDefinitionEntity tool = tool();
        tool.setParametersJson("[{\"name\":\"quantity\",\"type\":\"integer\",\"required\":true,"
                + "\"metadata\":{\"enum\":[2,3],\"minimum\":2,\"maximum\":3}}]");
        when(tools.selectOne(any())).thenReturn(tool);
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setAppKey("orders-app"); credential.setAppSecret("local-test-secret");
        when(credentials.findPrimaryActiveCredential("orders")).thenReturn(Optional.of(credential));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(tools,
                new DefaultCapabilityHttpToolInvoker(new RestTemplateBuilder(),
                        mock(CapabilityOutboundTransportPolicy.class), new ObjectMapper()),
                credentials, acceptedGuard(tool));

        for (Object invalid : java.util.List.of("two", 1, 4)) {
            CapabilityInvocationPolicyException rejected = assertThrows(CapabilityInvocationPolicyException.class,
                    () -> service.execute("orders.lookup", consoleRequest(Map.of("quantity", invalid))));
            assertEquals("CAPABILITY_INPUT_INVALID", rejected.code());
        }
        assertEquals(0, requests.get());
    }

    @Test
    void localSdkBusinessFailureIsReturnedWithoutASecondOutboundAttempt() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/orders/lookup", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"success\":false,\"code\":\"ORDER_CLOSED\",\"message\":\"business rejected\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        ToolDefinitionMapper tools = mock(ToolDefinitionMapper.class);
        RegistrySecurityService credentials = mock(RegistrySecurityService.class);
        ToolDefinitionEntity tool = tool();
        tool.setParametersJson("[{\"name\":\"orderNo\",\"type\":\"string\",\"required\":true}]");
        when(tools.selectOne(any())).thenReturn(tool);
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setAppKey("orders-app"); credential.setAppSecret("local-test-secret");
        when(credentials.findPrimaryActiveCredential("orders")).thenReturn(Optional.of(credential));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(tools,
                new DefaultCapabilityHttpToolInvoker(new RestTemplateBuilder(),
                        mock(CapabilityOutboundTransportPolicy.class), new ObjectMapper()),
                credentials, acceptedGuard(tool));

        Map<String, Object> result = service.execute("orders.lookup", consoleRequest(Map.of("orderNo", "O-closed")));

        assertEquals(1, requests.get());
        assertEquals(false, result.get("success"));
        assertEquals("CAPABILITY_BUSINESS_RESPONSE_FAILED", result.get("code"));
        assertEquals("ORDER_CLOSED", result.get("businessCode"));
    }

    @Test
    void localSdkDeadlineTimeoutHasOneOutboundAttempt() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/orders/lookup", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(1_200L);
                byte[] response = "{\"success\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        ToolDefinitionMapper tools = mock(ToolDefinitionMapper.class);
        RegistrySecurityService credentials = mock(RegistrySecurityService.class);
        ToolDefinitionEntity tool = tool();
        tool.setParametersJson("[{\"name\":\"orderNo\",\"type\":\"string\",\"required\":true}]");
        when(tools.selectOne(any())).thenReturn(tool);
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setAppKey("orders-app"); credential.setAppSecret("local-test-secret");
        when(credentials.findPrimaryActiveCredential("orders")).thenReturn(Optional.of(credential));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(tools,
                new DefaultCapabilityHttpToolInvoker(new RestTemplateBuilder(),
                        mock(CapabilityOutboundTransportPolicy.class), new ObjectMapper()),
                credentials, acceptedGuard(tool));
        Map<String, Object> request = new LinkedHashMap<>(consoleRequest(Map.of("orderNo", "O-timeout")));
        request.put("deadlineEpochMs", System.currentTimeMillis() + 350L);

        assertThrows(org.springframework.web.client.ResourceAccessException.class,
                () -> service.execute("orders.lookup", request));
        assertEquals(1, requests.get());
    }

    @Test
    void studioProjectMethodRechecksIdentitySourceReadonlyCredentialAndInputBeforeAnyHttpRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/orders/lookup", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        for (String mutation : java.util.List.of("project", "user", "credential", "roles", "write", "unknown", "missing", "hash")) {
            ToolDefinitionMapper tools = mock(ToolDefinitionMapper.class);
            RegistrySecurityService credentials = mock(RegistrySecurityService.class);
            ToolDefinitionEntity tool = tool();
            tool.setParametersJson("[{\"name\":\"orderNo\",\"type\":\"string\",\"required\":true}]");
            when(tools.selectOne(any())).thenReturn(tool);
            RegistryCredentialEntity credential = new RegistryCredentialEntity();
            credential.setProjectCode("orders"); credential.setAppKey("orders-app"); credential.setAppSecret("local-test-secret");
            when(credentials.findPrimaryActiveCredential("orders")).thenReturn(
                    "credential".equals(mutation) ? Optional.empty() : Optional.of(credential));
            if ("roles".equals(mutation)) tool.setCapabilityMetadataJson("{\"requiredRoles\":[\"reader\"]}");
            if ("write".equals(mutation)) tool.setSideEffect("WRITE");
            if ("unknown".equals(mutation)) tool.setSideEffect("UNKNOWN");
            Map<String, Object> context = new LinkedHashMap<>(Map.of("tenantId", "project".equals(mutation) ? "other" : "orders"));
            if ("user".equals(mutation)) context.put("externalUserId", "platform:42");
            Map<String, Object> constraints = new LinkedHashMap<>(Map.of("studioReadOnlyTrial", true,
                    "expectedQualifiedName", "orders.lookup", "expectedProjectCode", "orders", "expectedProjectId", 7L,
                    "expectedContractHash", "hash".equals(mutation) ? "b".repeat(64) : "a".repeat(64), "requireSignedInvocation", true));
            var service = new CapabilityToolExecutionService(tools, new DefaultCapabilityHttpToolInvoker(new RestTemplateBuilder(),
                    mock(CapabilityOutboundTransportPolicy.class), new ObjectMapper()), credentials, acceptedGuard(tool));
            assertThrows(RuntimeException.class, () -> service.execute("orders.lookup", Map.of("input",
                    "missing".equals(mutation) ? Map.of() : Map.of("orderNo", "O-42"), "context", context, "constraints", constraints)), mutation);
            assertEquals(0, requests.get(), mutation + " must reject before HTTP dispatch");
        }
    }

    private ToolDefinitionEntity tool() {
        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setId(1L); tool.setName("lookup"); tool.setTitle("Lookup"); tool.setQualifiedName("orders.lookup");
        tool.setAssetType("BUSINESS_METHOD"); tool.setProjectId(7L); tool.setProjectCode("orders");
        tool.setSourceQualifiedName("orders.lookup"); tool.setSourceLocation("sdk:orders:orders.lookup");
        tool.setEnabled(true); tool.setSideEffect("READ_ONLY"); tool.setHttpMethod("POST");
        tool.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        tool.setContextPath("/api"); tool.setEndpointPath("/orders/lookup");
        tool.setRequestBodyType("json"); tool.setResponseType("json"); tool.setCapabilityMetadataJson("{\"requiredRoles\":[]}");
        return tool;
    }

    private Map<String, Object> consoleRequest(Map<String, Object> input) {
        return Map.of("input", input, "context", Map.of(), "constraints", Map.of(
                "consoleCapabilityInvocation", true, "expectedQualifiedName", "orders.lookup",
                "expectedProjectCode", "orders", "expectedProjectId", 7L,
                "expectedContractHash", "a".repeat(64), "requireSignedInvocation", true));
    }

    private CapabilitySourceContractGuard acceptedGuard(ToolDefinitionEntity tool) {
        CapabilityChangeLifecycle lifecycle = mock(CapabilityChangeLifecycle.class);
        CapabilityChangePolicy policy = mock(CapabilityChangePolicy.class);
        CapabilitySourceStateEntity state = new CapabilitySourceStateEntity();
        state.setSourceContractHash("a".repeat(64));
        state.setAcceptedContractHash("a".repeat(64));
        state.setAvailability("READY");
        when(lifecycle.sourceState("orders.lookup")).thenReturn(state);
        when(policy.contractHash(tool)).thenReturn("a".repeat(64));
        return new CapabilitySourceContractGuard(lifecycle, policy);
    }

    private void assertTrueJson(String json, String field, Object value) throws Exception {
        if (field.isEmpty()) {
            assertEquals(java.util.List.of(), new ObjectMapper().readValue(json, java.util.List.class));
        } else {
            assertEquals(value, new ObjectMapper().readTree(json).path(field).asText());
        }
    }
}

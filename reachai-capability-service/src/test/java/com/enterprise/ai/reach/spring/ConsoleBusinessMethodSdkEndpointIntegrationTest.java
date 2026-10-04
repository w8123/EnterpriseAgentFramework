package com.enterprise.ai.reach.spring;

import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.capability.internal.CapabilityInvocationPolicyException;
import com.enterprise.ai.capability.internal.CapabilityOutboundTransportPolicy;
import com.enterprise.ai.capability.internal.CapabilitySourceContractGuard;
import com.enterprise.ai.capability.internal.CapabilityToolExecutionService;
import com.enterprise.ai.capability.internal.DefaultCapabilityHttpToolInvoker;
import com.enterprise.ai.capability.registry.CapabilityChangeLifecycle;
import com.enterprise.ai.capability.registry.CapabilityChangePolicy;
import com.enterprise.ai.capability.registry.CapabilitySourceStateEntity;
import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E1 local-only integration: actual SDK scanner/registry declaration, actual
 * owner validation and outbound HTTP, then the actual endpoint/invoker Java
 * binding. No development project endpoint is contacted.
 */
class ConsoleBusinessMethodSdkEndpointIntegrationTest {

    private final ObjectMapper json = new ObjectMapper();
    private final FixtureCapability fixture = new FixtureCapability();
    private HttpServer server;
    private AtomicInteger httpRequests;
    private Map<String, Map<String, Object>> registrations;
    private ReachCapabilityEndpoint endpoint;

    @BeforeEach
    void setUp() throws Exception {
        ReachAiRegistryProperties properties = properties();
        ReachCapabilityBeanScanner scanner = new ReachCapabilityBeanScanner(new Object[]{fixture});
        ReachAiRegistryClient registry = new ReachAiRegistryClient(properties, scanner,
                (method, url, headers, body) -> "{}");
        registrations = registrations(registry.capabilityRegistrations(registry.capabilities()));
        endpoint = new ReachCapabilityEndpoint(new ReachCapabilityInvoker(new Object[]{fixture}),
                new ReachCapabilityInvocationVerifier(properties), List.of());
        httpRequests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/reachai/capabilities", exchange -> {
            httpRequests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String prefix = "/reachai/capabilities/";
            String suffix = "/invoke";
            String capability = path.startsWith(prefix) && path.endsWith(suffix)
                    ? path.substring(prefix.length(), path.length() - suffix.length()) : "";
            try {
                Map<String, Object> input = json.readValue(exchange.getRequestBody().readAllBytes(),
                        new TypeReference<Map<String, Object>>() { });
                Object result = endpoint.invoke(capability,
                        exchange.getRequestHeaders().getFirst("X-ReachAI-Invocation-Token"),
                        input);
                write(exchange, 200, Map.of("success", true, "data", result));
            } catch (Exception failure) {
                write(exchange, 400, Map.of("success", false, "code", "SDK_BINDING_FAILED",
                        "message", failure.getClass().getSimpleName()));
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void actualSdkDeclarationAndEndpointBindNoArgsScalarDtoMapAndCollection() throws Exception {
        invoke("orders.none", Map.of());
        invoke("orders.scalar", Map.of("orderNo", "S-1"));
        invoke("orders.dto", Map.of("orderNo", "D-flat", "phone", "sentinel-phone"));
        invoke("orders.dto", Map.of("request", Map.of("orderNo", "D-wrapped", "phone", "sentinel-phone")));
        invoke("orders.attributes", Map.of("attributes", Map.of("dynamic", Map.of("nested", true))));
        invoke("orders.quantities", Map.of("quantities", List.of(1, 2, 3)));

        assertEquals(6, httpRequests.get());
        assertEquals(1, fixture.noneCalls.get());
        assertEquals("S-1", fixture.lastScalar);
        assertEquals("D-wrapped", fixture.lastDtoOrderNo);
        assertEquals("sentinel-phone", fixture.lastDtoPhone);
        assertEquals(1, fixture.attributesCalls.get());
        assertEquals(List.of(1, 2, 3), fixture.lastQuantities);

        Map<String, Object> dtoParameters = registrations.get("orders.dto");
        String parameters = json.writeValueAsString(dtoParameters.get("parameters"));
        assertTrue(parameters.contains("request.orderNo"), "registry keeps the source dotted path for hash/diff");
        assertTrue(parameters.contains("request.phone"));
        assertTrue(parameters.contains("\"sensitive\":true"));
        assertTrue(json.writeValueAsString(registrations.get("orders.quantities").get("parameters"))
                .contains("\"itemsType\":\"number\""));
    }

    @Test
    void invalidDtoAndPrimitiveArrayNeverReachTheSdkEndpoint() throws Exception {
        CapabilityInvocationPolicyException dto = assertThrows(CapabilityInvocationPolicyException.class,
                () -> invoke("orders.dto", Map.of("phone", "sentinel-phone")));
        assertEquals("CAPABILITY_INPUT_INVALID", dto.code());
        List<?> diagnostics = (List<?>) dto.safeMetadata().get("inputDiagnostics");
        assertEquals("orderNo", ((Map<?, ?>) diagnostics.get(0)).get("path"));

        CapabilityInvocationPolicyException array = assertThrows(CapabilityInvocationPolicyException.class,
                () -> invoke("orders.quantities", Map.of("quantities", List.of("wrong"))));
        assertEquals("CAPABILITY_INPUT_INVALID", array.code());
        assertFalse(array.safeMetadata().toString().contains("wrong"));
        assertEquals(0, httpRequests.get());
        assertEquals(0, fixture.dtoCalls.get());
        assertEquals(0, fixture.quantitiesCalls.get());
    }

    private void invoke(String qualifiedName, Map<String, Object> input) throws Exception {
        ToolDefinitionEntity tool = tool(qualifiedName);
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        when(mapper.selectOne(any())).thenReturn(tool);
        RegistrySecurityService credentials = mock(RegistrySecurityService.class);
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("orders");
        credential.setAppKey("orders-app");
        credential.setAppSecret("local-test-secret");
        when(credentials.findPrimaryActiveCredential("orders")).thenReturn(Optional.of(credential));

        CapabilityChangePolicy policy = new CapabilityChangePolicy(json);
        CapabilityChangeLifecycle lifecycle = mock(CapabilityChangeLifecycle.class);
        CapabilitySourceStateEntity state = new CapabilitySourceStateEntity();
        String hash = policy.contractHash(tool);
        state.setSourceContractHash(hash);
        state.setAcceptedContractHash(hash);
        state.setAvailability("READY");
        when(lifecycle.sourceState(qualifiedName)).thenReturn(state);
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(mapper,
                new DefaultCapabilityHttpToolInvoker(
                        new RestTemplateBuilder(), mock(CapabilityOutboundTransportPolicy.class), new ObjectMapper()),
                credentials, new CapabilitySourceContractGuard(lifecycle, policy));
        service.execute(qualifiedName, Map.of(
                "invocationId", "e1-" + qualifiedName.replace('.', '-') + "-" + httpRequests.get(),
                "input", input,
                "context", Map.of(),
                "deadlineEpochMs", System.currentTimeMillis() + 10_000L,
                "idempotencyKey", "e1-" + qualifiedName.replace('.', '-') + "-" + httpRequests.get(),
                "constraints", Map.of(
                        "consoleCapabilityInvocation", true,
                        "expectedQualifiedName", qualifiedName,
                        "expectedProjectCode", "orders",
                        "expectedProjectId", 7L,
                        "expectedContractHash", hash,
                        "requireSignedInvocation", true)));
    }

    private ToolDefinitionEntity tool(String qualifiedName) throws Exception {
        Map<String, Object> registration = registrations.get(qualifiedName);
        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setId(1L);
        tool.setName(qualifiedName.substring(qualifiedName.indexOf('.') + 1));
        tool.setTitle(String.valueOf(registration.get("title")));
        tool.setQualifiedName(qualifiedName);
        tool.setAssetType("BUSINESS_METHOD");
        tool.setProjectId(7L);
        tool.setProjectCode("orders");
        tool.setSourceQualifiedName(qualifiedName);
        tool.setSourceLocation("sdk:orders:" + qualifiedName);
        tool.setEnabled(true);
        tool.setSideEffect(String.valueOf(registration.get("sideEffect")));
        tool.setHttpMethod(String.valueOf(registration.get("httpMethod")));
        tool.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        tool.setContextPath("");
        tool.setEndpointPath(String.valueOf(registration.get("endpointPath")));
        tool.setRequestBodyType(String.valueOf(registration.get("requestBodyType")));
        tool.setResponseType(String.valueOf(registration.get("responseType")));
        tool.setParametersJson(json.writeValueAsString(registration.get("parameters")));
        tool.setCapabilityMetadataJson(json.writeValueAsString(registration.get("metadata")));
        return tool;
    }

    private Map<String, Map<String, Object>> registrations(List<Map<String, Object>> values) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> value : values) result.put(String.valueOf(value.get("name")), value);
        return result;
    }

    private ReachAiRegistryProperties properties() {
        ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
        properties.getProject().setCode("orders");
        properties.getProject().setBaseUrl("http://127.0.0.1");
        properties.getRegistry().setUrl("http://registry.local");
        properties.getRegistry().setAppKey("orders-app");
        properties.getRegistry().setAppSecret("local-test-secret");
        properties.getCapability().setRequireInvocationToken(true);
        return properties;
    }

    private void write(com.sun.net.httpserver.HttpExchange exchange, int status, Object response) throws java.io.IOException {
        byte[] bytes = json.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    static class FixtureCapability {
        private final AtomicInteger noneCalls = new AtomicInteger();
        private final AtomicInteger dtoCalls = new AtomicInteger();
        private final AtomicInteger attributesCalls = new AtomicInteger();
        private final AtomicInteger quantitiesCalls = new AtomicInteger();
        private String lastScalar;
        private String lastDtoOrderNo;
        private String lastDtoPhone;
        private List<Integer> lastQuantities = new ArrayList<>();

        @ReachCapability(name = "orders.none")
        public Map<String, Object> none() {
            noneCalls.incrementAndGet();
            return Map.of("kind", "none");
        }

        @ReachCapability(name = "orders.scalar")
        public Map<String, Object> scalar(@ReachParam(name = "orderNo", required = true) String orderNo) {
            lastScalar = orderNo;
            return Map.of("orderNo", orderNo);
        }

        @ReachCapability(name = "orders.dto")
        public Map<String, Object> dto(@ReachParam(name = "request", required = true) OrderRequest request) {
            dtoCalls.incrementAndGet();
            lastDtoOrderNo = request.orderNo;
            lastDtoPhone = request.phone;
            return Map.of("orderNo", request.orderNo, "phone", request.phone);
        }

        @ReachCapability(name = "orders.attributes")
        public Map<String, Object> attributes(@ReachParam(name = "attributes", required = true) Map<String, Object> attributes) {
            attributesCalls.incrementAndGet();
            return Map.of("size", attributes.size());
        }

        @ReachCapability(name = "orders.quantities")
        public Map<String, Object> quantities(@ReachParam(name = "quantities", required = true) List<Integer> quantities) {
            quantitiesCalls.incrementAndGet();
            lastQuantities = quantities;
            return Map.of("size", quantities.size());
        }
    }

    static class OrderRequest {
        @ReachParam(required = true)
        public String orderNo;

        @ReachParam(sensitive = true, example = "should-not-leak")
        public String phone;
    }
}

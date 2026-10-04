package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;
import com.enterprise.ai.reach.sdk.capability.ReachCapabilityDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachAiRegistryClientTest {

    @Test
    void registerAndSyncPostsProjectThenHeartbeatWithReachAiHeaders() {
        ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
        properties.getRegistry().setUrl("https://reachai.example.com/");
        properties.getRegistry().setAppKey("demo-key");
        properties.getRegistry().setAppSecret("demo-secret");
        properties.getProject().setCode("demo");
        properties.getProject().setName("Demo Project");
        properties.getProject().setBaseUrl("https://biz.example.com");
        properties.getEmbed().setAllowedOrigins(Arrays.asList("http://localhost:9200"));
        properties.getEmbed().setAllowedAgentIds(Arrays.asList("team-archive-assistant"));
        properties.getEmbed().setTokenTtlSeconds(1800);

        RecordingTransport transport = new RecordingTransport();
        ReachAiRegistryClient client = new ReachAiRegistryClient(
                properties,
                new ReachCapabilityBeanScanner(new Object[]{new ContractCapability()}),
                transport);

        client.registerAndSync();

        assertEquals(2, transport.requests.size());
        RecordingTransport.Request register = transport.requests.get(0);
        assertEquals("POST", register.method);
        assertEquals("https://reachai.example.com/api/registry/projects/register", register.url);
        assertEquals("demo-key", register.headers.get("X-ReachAI-App-Key"));
        assertNotNull(register.headers.get("X-ReachAI-Timestamp"));
        assertNotNull(register.headers.get("X-ReachAI-Nonce"));
        assertNotNull(register.headers.get("X-ReachAI-Signature"));
        assertEquals(4, register.headers.size());
        assertEquals("demo", register.body.get("projectCode"));
        assertEquals("Demo Project", register.body.get("name"));
        assertEquals(Arrays.asList("http://localhost:9200"), register.body.get("allowedOrigins"));
        assertEquals(Arrays.asList("team-archive-assistant"), register.body.get("allowedAgentIds"));
        assertEquals(1800, register.body.get("tokenTtlSeconds"));

        RecordingTransport.Request heartbeat = transport.requests.get(1);
        assertEquals("POST", heartbeat.method);
        assertEquals("https://reachai.example.com/api/registry/projects/demo/instances/heartbeat", heartbeat.url);
        assertNotNull(heartbeat.body.get("instanceId"));
        Map<?, ?> metadata = (Map<?, ?>) heartbeat.body.get("metadata");
        assertEquals("CAPABILITY_HOST", metadata.get("runtimePlacement"));
        assertEquals(Boolean.TRUE, metadata.get("supportsTools"));
        assertNull(metadata.get("capabilityCount"));
    }

    @Test
    void registerAndSyncDoesNothingWhenRegistrySecretIsMissing() {
        ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
        properties.getRegistry().setUrl("https://reachai.example.com");
        properties.getRegistry().setAppKey("demo-key");
        properties.getProject().setCode("demo");

        RecordingTransport transport = new RecordingTransport();
        ReachAiRegistryClient client = new ReachAiRegistryClient(
                properties,
                new ReachCapabilityBeanScanner(new Object[]{new ContractCapability()}),
                transport);

        client.registerAndSync();

        assertEquals(0, transport.requests.size());
    }

    @Test
    void enrollmentTokenRegistersOnceThenPersistsAndReusesGeneratedCredential() throws Exception {
        Path store = Files.createTempDirectory("reachai-registry-test").resolve("credential.properties");
        ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
        properties.getRegistry().setUrl("https://reachai.example.com");
        properties.getRegistry().setEnrollmentToken("ren_once");
        properties.getRegistry().setCredentialStorePath(store.toString());
        properties.getProject().setCode("orders");
        properties.getProject().setName("Orders");
        properties.getProject().setBaseUrl("https://orders.example.com");
        EnrollmentTransport transport = new EnrollmentTransport();

        ReachAiRegistryClient client = new ReachAiRegistryClient(
                properties, new ReachCapabilityBeanScanner(new Object[0]), transport);
        client.registerAndSync();

        assertEquals(2, transport.requests.size());
        RecordingTransport.Request registration = transport.requests.get(0);
        assertEquals("ren_once", registration.headers.get(ReachAiRegistryClient.ENROLLMENT_TOKEN_HEADER));
        assertEquals(1, registration.headers.size());
        assertFalse(registration.body.containsKey("appSecret"));
        RecordingTransport.Request heartbeat = transport.requests.get(1);
        assertEquals("rak_generated", heartbeat.headers.get("X-ReachAI-App-Key"));
        assertNotNull(heartbeat.headers.get("X-ReachAI-Signature"));
        assertEquals("rak_generated", properties.getRegistry().getAppKey());
        assertEquals("ras_generated", properties.getRegistry().getAppSecret());

        ReachAiRegistryProperties restartProperties = new ReachAiRegistryProperties();
        restartProperties.getRegistry().setUrl("https://reachai.example.com");
        restartProperties.getRegistry().setCredentialStorePath(store.toString());
        restartProperties.getProject().setCode("orders");
        restartProperties.getProject().setBaseUrl("https://orders.example.com");
        RecordingTransport restartTransport = new RecordingTransport();
        ReachAiRegistryClient restarted = new ReachAiRegistryClient(
                restartProperties, new ReachCapabilityBeanScanner(new Object[0]), restartTransport);

        assertEquals("rak_generated", restartProperties.getRegistry().getAppKey());
        assertEquals("ras_generated", restartProperties.getRegistry().getAppSecret());
        restarted.registerAndSync();
        assertEquals("rak_generated", restartTransport.requests.get(0).headers.get("X-ReachAI-App-Key"));
    }

    @Test
    void registerAndSyncDoesNotScanOrSyncCapabilitiesByDefault() {
        ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
        properties.getRegistry().setUrl("https://reachai.example.com");
        properties.getRegistry().setAppKey("demo-key");
        properties.getRegistry().setAppSecret("demo-secret");
        properties.getProject().setCode("demo");
        properties.getProject().setBaseUrl("https://biz.example.com");

        CountingScanner scanner = new CountingScanner();
        RecordingTransport transport = new RecordingTransport();
        ReachAiRegistryClient client = new ReachAiRegistryClient(properties, scanner, transport);

        client.registerAndSync();

        assertEquals(2, transport.requests.size());
        assertEquals(0, scanner.scanCalls);
        RecordingTransport.Request heartbeat = transport.requests.get(1);
        Map<?, ?> metadata = (Map<?, ?>) heartbeat.body.get("metadata");
        assertNull(metadata.get("capabilityCount"));
    }

    @Test
    void scanAndSyncCapabilitiesScansBeansAndPostsToRegistry() {
        ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
        properties.getRegistry().setUrl("https://reachai.example.com");
        properties.getRegistry().setAppKey("demo-key");
        properties.getRegistry().setAppSecret("demo-secret");
        properties.getProject().setCode("demo");
        properties.getProject().setBaseUrl("https://biz.example.com");

        CountingScanner scanner = new CountingScanner();
        RecordingTransport transport = new RecordingTransport();
        ReachAiRegistryClient client = new ReachAiRegistryClient(properties, scanner, transport);

        ReachAiRegistryClient.ManualCapabilitySyncResponse response = client.scanAndSyncCapabilities();

        assertEquals(1, scanner.scanCalls);
        assertEquals(1, response.getCapabilityCount());
        assertEquals(0, response.getHttpApiCount());
        assertEquals(1, transport.requests.size());
        RecordingTransport.Request sync = transport.requests.get(0);
        assertEquals("POST", sync.method);
        assertEquals("https://reachai.example.com/api/registry/projects/demo/capabilities/sync", sync.url);
        assertEquals("SDK", sync.body.get("source"));
        assertEquals(Boolean.FALSE, sync.body.get("apply"));
        List<?> capabilities = (List<?>) sync.body.get("capabilities");
        assertEquals(1, capabilities.size());
        assertEquals(Collections.emptyList(), sync.body.get("httpApis"));
        Map<?, ?> metadata = (Map<?, ?>) ((Map<?, ?>) capabilities.get(0)).get("metadata");
        assertFalse(metadata.containsKey("assetType"));
    }

    @Test
    void callbackScanMarksTheSnapshotSourceSeparatelyFromStartupSync() {
        ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
        properties.getRegistry().setUrl("https://reachai.example.com");
        properties.getRegistry().setAppKey("demo-key");
        properties.getRegistry().setAppSecret("demo-secret");
        properties.getProject().setCode("demo");

        RecordingTransport transport = new RecordingTransport();
        ReachAiRegistryClient client = new ReachAiRegistryClient(
                properties, new CountingScanner(), transport);

        client.scanAndSyncCapabilitiesFromPlatformCallback();

        assertEquals("SDK_CALLBACK", transport.requests.get(0).body.get("source"));
    }

    @Test
    void registerAndSyncDoesNotBreakApplicationStartupWhenRegistryIsUnavailable() {
        ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
        properties.getRegistry().setUrl("https://reachai.example.com");
        properties.getRegistry().setAppKey("demo-key");
        properties.getRegistry().setAppSecret("demo-secret");
        properties.getProject().setCode("demo");

        ReachAiRegistryClient client = new ReachAiRegistryClient(
                properties,
                new ReachCapabilityBeanScanner(new Object[]{new ContractCapability()}),
                new FailingTransport());

        assertDoesNotThrow(client::registerAndSync);
    }

    @Test
    void scanAndSyncCapabilitiesPlacesPlainSpringMvcControllerEndpointsInHttpApisOnly() {
        ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
        properties.getRegistry().setUrl("https://reachai.example.com");
        properties.getRegistry().setAppKey("demo-key");
        properties.getRegistry().setAppSecret("demo-secret");
        properties.getProject().setCode("demo");
        properties.getProject().setBaseUrl("https://biz.example.com");

        RecordingTransport transport = new RecordingTransport();
        ReachAiRegistryClient client = new ReachAiRegistryClient(
                properties,
                new ReachCapabilityBeanScanner(new Object[]{new PlainController()}),
                transport);

        client.scanAndSyncCapabilities();

        RecordingTransport.Request sync = transport.requests.get(0);
        List<?> capabilities = (List<?>) sync.body.get("capabilities");
        assertEquals(0, capabilities.size());
        List<?> httpApis = (List<?>) sync.body.get("httpApis");
        assertEquals(2, httpApis.size());

        Map<?, ?> getItem = findHttpApi(httpApis, "/plain/getStatus");
        assertEquals("GET", getItem.get("httpMethod"));
        assertEquals("/plain/getStatus", getItem.get("endpointPath"));
        assertEquals("UNKNOWN", getItem.get("authenticationState"));
        assertTrue(((String) getItem.get("sourceKey")).startsWith("mvc:"));

        Map<?, ?> postItem = findHttpApi(httpApis, "/plain/create");
        assertEquals("POST", postItem.get("httpMethod"));
        assertEquals("/plain/create", postItem.get("endpointPath"));
        Map<?, ?> requestBody = (Map<?, ?>) postItem.get("requestBody");
        assertEquals("BODY", requestBody.get("location"));
    }

    @Test
    void dualDeclarationReportsBusinessMethodAndMvcOperationInSeparateArrays() {
        ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
        properties.getRegistry().setUrl("https://reachai.example.com");
        properties.getRegistry().setAppKey("demo-key");
        properties.getRegistry().setAppSecret("demo-secret");
        properties.getProject().setCode("demo");
        properties.getProject().setBaseUrl("https://biz.example.com");

        RecordingTransport transport = new RecordingTransport();
        ReachAiRegistryClient client = new ReachAiRegistryClient(
                properties,
                new ReachCapabilityBeanScanner(new Object[]{new AnnotatedController()}),
                transport);

        client.scanAndSyncCapabilities();

        List<?> capabilities = (List<?>) transport.requests.get(0).body.get("capabilities");
        assertEquals(1, capabilities.size());
        Map<?, ?> capability = findCapability(capabilities, "team.current-user");
        assertEquals(
                "/reachai/capabilities/team.current-user/invoke",
                capability.get("endpointPath"));
        Map<?, ?> metadata = (Map<?, ?>) capability.get("metadata");
        assertEquals(Boolean.TRUE, metadata.get("declared"));
        assertEquals("ReachCapability", metadata.get("source"));
        assertEquals("BUSINESS_METHOD", metadata.get("assetType"));
        List<?> httpApis = (List<?>) transport.requests.get(0).body.get("httpApis");
        assertEquals(1, httpApis.size());
        assertEquals("GET", findHttpApi(httpApis, "/users/current").get("httpMethod"));
    }

    static class ContractCapability {
        @ReachCapability(name = "contract.query", title = "Query contract")
        public String query(@ReachParam(description = "Contract number", required = true) String contractNo) {
            return contractNo;
        }
    }

    private Map<?, ?> findCapability(List<?> capabilities, String name) {
        for (Object item : capabilities) {
            Map<?, ?> map = (Map<?, ?>) item;
            if (name.equals(map.get("name"))) {
                return map;
            }
        }
        throw new AssertionError("capability not found: " + name);
    }

    private Map<?, ?> findHttpApi(List<?> httpApis, String endpointPath) {
        for (Object item : httpApis) {
            Map<?, ?> map = (Map<?, ?>) item;
            if (endpointPath.equals(map.get("endpointPath"))) {
                return map;
            }
        }
        throw new AssertionError("HTTP API not found: " + endpointPath);
    }

    static class CountingScanner extends ReachCapabilityBeanScanner {
        private int scanCalls;

        CountingScanner() {
            super(new Object[0]);
        }

        @Override
        public ScanResult scanResult() {
            scanCalls++;
            ReachCapabilityDescriptor descriptor = new ReachCapabilityDescriptor();
            descriptor.setName("contract.query");
            descriptor.setTitle("Query contract");
            descriptor.setHttpMethod("POST");
            descriptor.setEndpointPath("/reachai/capabilities/contract.query/invoke");
            descriptor.setRequestBodyType("java.util.Map");
            descriptor.setReturnType("java.lang.String");
            return new ScanResult(Collections.singletonList(descriptor), Collections.emptyList());
        }
    }

    @RestController
    @RequestMapping("/plain")
    static class PlainController {
        @GetMapping("/getStatus")
        public String getStatus(@RequestParam(required = false) String keyword) {
            return "ok";
        }

        @PostMapping("/create")
        public String create(@RequestBody String payload) {
            return payload;
        }
    }

    @RestController
    @RequestMapping("/users")
    static class AnnotatedController {
        @GetMapping("/current")
        @ReachCapability(name = "team.current-user", title = "Current user")
        public String currentUser() {
            return "user-001";
        }
    }

    static class RecordingTransport implements ReachAiRegistryTransport {
        protected final List<Request> requests = new ArrayList<Request>();

        @Override
        public String exchange(String method, String url, Map<String, String> headers, Object body) {
            requests.add(new Request(method, url, headers, body));
            return "{}";
        }

        static class Request {
            private final String method;
            private final String url;
            private final Map<String, String> headers;
            private final Map<?, ?> body;

            Request(String method, String url, Map<String, String> headers, Object body) {
                this.method = method;
                this.url = url;
                this.headers = headers;
                this.body = (Map<?, ?>) body;
            }
        }
    }

    static class FailingTransport implements ReachAiRegistryTransport {
        @Override
        public String exchange(String method, String url, Map<String, String> headers, Object body) {
            throw new IllegalStateException("registry unavailable");
        }
    }

    static class EnrollmentTransport extends RecordingTransport {
        @Override
        public String exchange(String method, String url, Map<String, String> headers, Object body) {
            super.exchange(method, url, headers, body);
            return url.endsWith("/api/registry/projects/register")
                    ? "{\"projectCode\":\"orders\",\"appKey\":\"rak_generated\",\"appSecret\":\"ras_generated\"}"
                    : "{}";
        }
    }
}

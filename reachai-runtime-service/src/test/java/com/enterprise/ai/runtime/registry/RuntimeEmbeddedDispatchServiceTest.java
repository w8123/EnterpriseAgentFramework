package com.enterprise.ai.runtime.registry;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class RuntimeEmbeddedDispatchServiceTest {

    @Test
    void springContainerCanInstantiateRuntimeEmbeddedDispatchServiceWithInjectedDependencies() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RuntimeCapabilityCatalogClient.class,
                    () -> capabilityClient(instance("ONLINE", null, policy(false, true))));
            context.registerBean(ObjectMapper.class, (Supplier<ObjectMapper>) ObjectMapper::new);
            context.registerBean(RuntimeRegistryService.class);
            context.registerBean(RestTemplateBuilder.class, (Supplier<RestTemplateBuilder>) RestTemplateBuilder::new);
            context.registerBean(RuntimeEmbeddedDispatchService.class);

            assertDoesNotThrow(context::refresh);
        }
    }

    @Test
    void dispatchesToRegisteredEmbeddedRuntimeInstance() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        RuntimeEmbeddedDispatchService service = new RuntimeEmbeddedDispatchService(
                new RuntimeRegistryService(capabilityClient(instance("ONLINE", null, policy(false, true))),
                        new ObjectMapper()),
                restTemplate);
        RuntimeEmbeddedDispatchRequest request = request();

        server.expect(requestTo("http://runtime-host/eaf/runtime/embedded/execute"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.agentKey").value("agent-a"))
                .andExpect(jsonPath("$.message").value("hello"))
                .andExpect(jsonPath("$.sessionId").value("s1"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"success":true,"answer":"done","steps":["Remote runtime accepted"],"metadata":{"remote":true}}
                                """));

        RuntimeEmbeddedDispatchResult result = service.dispatch(request);

        assertEquals(true, result.success());
        assertEquals("done", result.answer());
        assertEquals("crm", result.projectCode());
        assertEquals("i-1", result.instanceId());
        assertEquals("http://runtime-host/eaf/runtime/embedded/execute", result.dispatchUrl());
        assertEquals(List.of("Remote runtime accepted"), result.steps());
        assertEquals(true, result.metadata().get("remote"));
        server.verify();
    }

    @Test
    void rejectsOfflineInstanceBeforeRemoteDispatch() {
        RuntimeEmbeddedDispatchService service = service(instance("OFFLINE", null, policy(false, true)));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.dispatch(request()));

        assertEquals("Runtime 实例不在线: OFFLINE", ex.getMessage());
    }

    @Test
    void rejectsCapabilityHostBeforeRemoteDispatch() {
        RuntimeEmbeddedDispatchService service = service(instance(
                "ONLINE",
                """
                        {"runtimePlacement":"CAPABILITY_HOST","runtimeTypes":["SPRING_BOOT2_CAPABILITY_HOST"]}
                        """,
                policy(false, true)));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.dispatch(request()));

        assertEquals("Capability Host 只能提供业务能力调用，不能作为 Agent Runtime 执行目标", ex.getMessage());
    }

    @Test
    void rejectsEmbeddedDispatchWhenPolicyDisallowsIt() {
        RuntimeEmbeddedDispatchService service = service(instance("ONLINE", null, policy(false, false)));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.dispatch(request()));

        assertEquals("Runtime 实例未被允许执行 Embedded Runtime: blocked", ex.getMessage());
    }

    private RuntimeEmbeddedDispatchService service(Map<String, Object> instance) {
        return new RuntimeEmbeddedDispatchService(
                new RuntimeRegistryService(capabilityClient(instance), new ObjectMapper()),
                new RestTemplate());
    }

    private RuntimeCapabilityCatalogClient capabilityClient(Map<String, Object> instance) {
        return new RuntimeCapabilityCatalogClient() {
            @Override
            public Map<String, Object> getToolDefinition(String qualifiedName) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Map<String, Object> executeTool(String qualifiedName, Map<String, Object> request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Map<String, Object> getCompositionDefinition(String qualifiedName) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Map<String, Object> getProject(String projectCode) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Map<String, Object> getProjectById(Long projectId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Map<String, Object>> listRuntimeInstances() {
                return List.of(instance);
            }
        };
    }

    private RuntimeEmbeddedDispatchRequest request() {
        return new RuntimeEmbeddedDispatchRequest("crm", "i-1", "agent-a", "hello", "s1", "u1", Map.of(), Map.of());
    }

    private Map<String, Object> instance(String status, String metadataJson, Map<String, Object> policy) {
        return Map.ofEntries(
                Map.entry("projectCode", "crm"),
                Map.entry("instanceId", "i-1"),
                Map.entry("baseUrl", "http://runtime-host"),
                Map.entry("host", "runtime-host"),
                Map.entry("port", 18080),
                Map.entry("status", status),
                Map.entry("metadataJson", metadataJson == null ? "{}" : metadataJson),
                Map.entry("governancePolicy", policy));
    }

    private Map<String, Object> policy(boolean disabled, boolean allowEmbeddedExecution) {
        return Map.of(
                "disabled", disabled,
                "allowEmbeddedExecution", allowEmbeddedExecution,
                "allowHybridExecution", true,
                "message", "blocked");
    }
}

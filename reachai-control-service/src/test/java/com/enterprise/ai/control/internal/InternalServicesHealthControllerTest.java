package com.enterprise.ai.control.internal;

import com.enterprise.ai.control.client.health.InternalHealthFeignConfig;

import com.enterprise.ai.control.client.capability.CapabilityHealthClient;
import com.enterprise.ai.control.client.knowledge.KnowledgeHealthClient;
import com.enterprise.ai.control.client.model.ModelHealthClient;
import com.enterprise.ai.control.client.runtime.RuntimeHealthClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalServicesHealthControllerTest {

    private ThreadPoolTaskExecutor probeExecutor;

    @AfterEach
    void tearDown() {
        if (probeExecutor != null) {
            probeExecutor.shutdown();
            probeExecutor = null;
        }
    }

    @Test
    void aggregatesAllFourServicesWhenUp() {
        InternalServicesHealthController controller = controller(
                Map.of("status", "UP"),
                Map.of("status", "UP"),
                Map.of("status", "UP"),
                Map.of("status", "UP")
        );

        ResponseEntity<Map<String, Object>> response = controller.health();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("UP", body.get("status"));
        assertEquals(Map.of(
                "runtime", Map.of("status", "UP"),
                "capability", Map.of("status", "UP"),
                "model", Map.of("status", "UP"),
                "knowledge", Map.of("status", "UP")
        ), body.get("services"));
    }

    @Test
    void modelDownKeepsOtherStatusesAndOverallDown() {
        InternalServicesHealthController controller = controller(
                Map.of("status", "UP"),
                Map.of("status", "UP"),
                Map.of("status", "DOWN"),
                Map.of("status", "UP")
        );

        Map<String, Object> body = controller.health().getBody();
        assertNotNull(body);
        assertEquals("DOWN", body.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) body.get("services");
        assertEquals(Map.of("status", "UP"), services.get("runtime"));
        assertEquals(Map.of("status", "UP"), services.get("capability"));
        assertEquals(Map.of("status", "DOWN"), services.get("model"));
        assertEquals(Map.of("status", "UP"), services.get("knowledge"));
    }

    @Test
    void knowledgeClientExceptionBecomesDownWithoutFailingAggregate() {
        RuntimeHealthClient runtime = mock(RuntimeHealthClient.class);
        CapabilityHealthClient capability = mock(CapabilityHealthClient.class);
        ModelHealthClient model = mock(ModelHealthClient.class);
        KnowledgeHealthClient knowledge = mock(KnowledgeHealthClient.class);
        when(runtime.health()).thenReturn(Map.of("status", "UP"));
        when(capability.health()).thenReturn(Map.of("status", "UP"));
        when(model.health()).thenReturn(Map.of("status", "UP"));
        when(knowledge.health()).thenThrow(new RuntimeException("Connection refused"));

        InternalServicesHealthController controller = newController(
                runtime, capability, model, knowledge,
                syncExecutor(),
                Duration.ofMillis(500),
                Duration.ofMillis(1_000)
        );

        ResponseEntity<Map<String, Object>> response = controller.health();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("DOWN", body.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) body.get("services");
        assertEquals(Map.of("status", "DOWN"), services.get("knowledge"));
        assertEquals(Map.of("status", "UP"), services.get("model"));
    }

    @Test
    void runtimeDownStillReturnsOtherServiceStatuses() {
        InternalServicesHealthController controller = controller(
                Map.of("status", "DOWN"),
                Map.of("status", "UP"),
                Map.of("status", "UP"),
                Map.of("status", "UP")
        );

        Map<String, Object> body = controller.health().getBody();
        assertNotNull(body);
        assertEquals("DOWN", body.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) body.get("services");
        assertEquals(Map.of("status", "DOWN"), services.get("runtime"));
        assertEquals(Map.of("status", "UP"), services.get("capability"));
        assertEquals(Map.of("status", "UP"), services.get("model"));
        assertEquals(Map.of("status", "UP"), services.get("knowledge"));
    }

    @Test
    void missingStatusIsTreatedAsDown() {
        InternalServicesHealthController controller = controller(
                Map.of("status", "UP"),
                Map.of("service", "capability-only"),
                Map.of("status", "UP"),
                Map.of("status", "UP")
        );

        Map<String, Object> body = controller.health().getBody();
        assertNotNull(body);
        assertEquals("DOWN", body.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) body.get("services");
        assertEquals(Map.of("status", "DOWN"), services.get("capability"));
    }

    @Test
    void probeNullResultIsDown() {
        InternalServicesHealthController controller = newController(
                mock(RuntimeHealthClient.class),
                mock(CapabilityHealthClient.class),
                mock(ModelHealthClient.class),
                mock(KnowledgeHealthClient.class),
                syncExecutor(),
                Duration.ofMillis(200),
                Duration.ofMillis(500)
        );
        assertEquals(Map.of("status", "DOWN"), controller.probe("model", () -> null));
    }

    @Test
    void blockingClientTimesOutWhileOthersRemainVisible() throws Exception {
        RuntimeHealthClient runtime = mock(RuntimeHealthClient.class);
        CapabilityHealthClient capability = mock(CapabilityHealthClient.class);
        ModelHealthClient model = mock(ModelHealthClient.class);
        KnowledgeHealthClient knowledge = mock(KnowledgeHealthClient.class);

        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean released = new AtomicBoolean(false);
        when(runtime.health()).thenReturn(Map.of("status", "UP"));
        when(capability.health()).thenReturn(Map.of("status", "UP"));
        when(knowledge.health()).thenReturn(Map.of("status", "UP"));
        when(model.health()).thenAnswer(invocation -> {
            entered.countDown();
            while (!released.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return Map.of("status", "UP");
        });

        probeExecutor = boundedExecutor();
        InternalServicesHealthController controller = newController(
                runtime, capability, model, knowledge,
                probeExecutor,
                Duration.ofMillis(200),
                Duration.ofMillis(400)
        );

        long started = System.nanoTime();
        ResponseEntity<Map<String, Object>> response = controller.health();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        released.set(true);

        assertTrue(entered.await(2, TimeUnit.SECONDS), "blocking probe should have started");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("DOWN", body.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) body.get("services");
        assertEquals(Map.of("status", "UP"), services.get("runtime"));
        assertEquals(Map.of("status", "UP"), services.get("capability"));
        assertEquals(Map.of("status", "DOWN"), services.get("model"));
        assertEquals(Map.of("status", "UP"), services.get("knowledge"));
        assertTrue(elapsedMs < 2_000, "aggregate should finish within budget, elapsedMs=" + elapsedMs);
    }

    @Test
    void allFourHealthClientsUseDedicatedShortTimeoutConfig() {
        Class<?>[] expected = {InternalHealthFeignConfig.class};
        assertArrayEquals(expected, RuntimeHealthClient.class.getAnnotation(FeignClient.class).configuration());
        assertArrayEquals(expected, CapabilityHealthClient.class.getAnnotation(FeignClient.class).configuration());
        assertArrayEquals(expected, ModelHealthClient.class.getAnnotation(FeignClient.class).configuration());
        assertArrayEquals(expected, KnowledgeHealthClient.class.getAnnotation(FeignClient.class).configuration());

        feign.Request.Options options = new InternalHealthFeignConfig().healthRequestOptions();
        assertEquals(InternalHealthFeignConfig.CONNECT_TIMEOUT_MS, options.connectTimeoutMillis());
        assertEquals(InternalHealthFeignConfig.READ_TIMEOUT_MS, options.readTimeoutMillis());
        assertTrue(options.readTimeoutMillis() < 5_000);
        assertTrue(options.readTimeoutMillis() < 135_000);
    }

    private InternalServicesHealthController controller(Map<String, Object> runtime,
                                                        Map<String, Object> capability,
                                                        Map<String, Object> model,
                                                        Map<String, Object> knowledge) {
        RuntimeHealthClient runtimeClient = mock(RuntimeHealthClient.class);
        CapabilityHealthClient capabilityClient = mock(CapabilityHealthClient.class);
        ModelHealthClient modelClient = mock(ModelHealthClient.class);
        KnowledgeHealthClient knowledgeClient = mock(KnowledgeHealthClient.class);
        when(runtimeClient.health()).thenReturn(runtime);
        when(capabilityClient.health()).thenReturn(capability);
        when(modelClient.health()).thenReturn(model);
        when(knowledgeClient.health()).thenReturn(knowledge);
        return newController(
                runtimeClient, capabilityClient, modelClient, knowledgeClient,
                syncExecutor(),
                Duration.ofMillis(500),
                Duration.ofMillis(1_000)
        );
    }

    private InternalServicesHealthController newController(RuntimeHealthClient runtime,
                                                           CapabilityHealthClient capability,
                                                           ModelHealthClient model,
                                                           KnowledgeHealthClient knowledge,
                                                           Executor executor,
                                                           Duration probeTimeout,
                                                           Duration totalBudget) {
        return new InternalServicesHealthController(
                runtime, capability, model, knowledge, executor, probeTimeout, totalBudget);
    }

    private static Executor syncExecutor() {
        return Runnable::run;
    }

    private ThreadPoolTaskExecutor boundedExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("test-health-");
        executor.initialize();
        return executor;
    }
}

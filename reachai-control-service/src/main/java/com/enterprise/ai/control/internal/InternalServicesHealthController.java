package com.enterprise.ai.control.internal;

import com.enterprise.ai.control.client.capability.CapabilityHealthClient;
import com.enterprise.ai.control.client.knowledge.KnowledgeHealthClient;
import com.enterprise.ai.control.client.model.ModelHealthClient;
import com.enterprise.ai.control.client.runtime.RuntimeHealthClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@RestController
public class InternalServicesHealthController {

    private static final Logger log = LoggerFactory.getLogger(InternalServicesHealthController.class);

    /** Per-service wait cap within the aggregate budget (Dashboard waits 5s). */
    static final Duration DEFAULT_PROBE_TIMEOUT = Duration.ofMillis(2_500);

    /** Worst-case wall clock for the whole aggregation response. */
    static final Duration DEFAULT_TOTAL_BUDGET = Duration.ofMillis(3_500);

    private final RuntimeHealthClient runtimeHealthClient;
    private final CapabilityHealthClient capabilityHealthClient;
    private final ModelHealthClient modelHealthClient;
    private final KnowledgeHealthClient knowledgeHealthClient;
    private final Executor healthProbeExecutor;
    private final Duration probeTimeout;
    private final Duration totalBudget;

    @Autowired
    public InternalServicesHealthController(RuntimeHealthClient runtimeHealthClient,
                                            CapabilityHealthClient capabilityHealthClient,
                                            ModelHealthClient modelHealthClient,
                                            KnowledgeHealthClient knowledgeHealthClient,
                                            @Qualifier(InternalServicesHealthConfiguration.HEALTH_PROBE_EXECUTOR)
                                            Executor healthProbeExecutor) {
        this(runtimeHealthClient, capabilityHealthClient, modelHealthClient, knowledgeHealthClient,
                healthProbeExecutor, DEFAULT_PROBE_TIMEOUT, DEFAULT_TOTAL_BUDGET);
    }

    /** Test-visible constructor with explicit timeout budget. */
    InternalServicesHealthController(RuntimeHealthClient runtimeHealthClient,
                                     CapabilityHealthClient capabilityHealthClient,
                                     ModelHealthClient modelHealthClient,
                                     KnowledgeHealthClient knowledgeHealthClient,
                                     Executor healthProbeExecutor,
                                     Duration probeTimeout,
                                     Duration totalBudget) {
        this.runtimeHealthClient = runtimeHealthClient;
        this.capabilityHealthClient = capabilityHealthClient;
        this.modelHealthClient = modelHealthClient;
        this.knowledgeHealthClient = knowledgeHealthClient;
        this.healthProbeExecutor = healthProbeExecutor;
        this.probeTimeout = probeTimeout;
        this.totalBudget = totalBudget;
    }

    @GetMapping("/api/internal-services/health")
    public ResponseEntity<Map<String, Object>> health() {
        long deadlineNanos = System.nanoTime() + totalBudget.toNanos();

        CompletableFuture<Map<String, Object>> runtimeFuture =
                CompletableFuture.supplyAsync(() -> probe("runtime", runtimeHealthClient::health), healthProbeExecutor);
        CompletableFuture<Map<String, Object>> capabilityFuture =
                CompletableFuture.supplyAsync(() -> probe("capability", capabilityHealthClient::health), healthProbeExecutor);
        CompletableFuture<Map<String, Object>> modelFuture =
                CompletableFuture.supplyAsync(() -> probe("model", modelHealthClient::health), healthProbeExecutor);
        CompletableFuture<Map<String, Object>> knowledgeFuture =
                CompletableFuture.supplyAsync(() -> probe("knowledge", knowledgeHealthClient::health), healthProbeExecutor);

        Map<String, Object> runtime = awaitProbe(runtimeFuture, "runtime", deadlineNanos);
        Map<String, Object> capability = awaitProbe(capabilityFuture, "capability", deadlineNanos);
        Map<String, Object> model = awaitProbe(modelFuture, "model", deadlineNanos);
        Map<String, Object> knowledge = awaitProbe(knowledgeFuture, "knowledge", deadlineNanos);

        Map<String, Object> services = new LinkedHashMap<>();
        services.put("runtime", runtime);
        services.put("capability", capability);
        services.put("model", model);
        services.put("knowledge", knowledge);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allUp(runtime, capability, model, knowledge) ? "UP" : "DOWN");
        body.put("services", services);
        return ResponseEntity.ok(body);
    }

    /**
     * Probe one service independently. Connection failures and non-UP statuses become DOWN
     * without failing the whole aggregation response.
     */
    Map<String, Object> probe(String serviceKey, Supplier<Map<String, Object>> call) {
        try {
            Map<String, Object> result = call.get();
            if (result == null || !"UP".equals(String.valueOf(result.get("status")))) {
                Object status = result == null ? "null" : result.get("status");
                log.warn("Internal service {} health not UP: status={}", serviceKey, status);
                return down();
            }
            return up();
        } catch (Exception ex) {
            log.warn("Internal service {} health probe failed: {}", serviceKey, ex.toString());
            return down();
        }
    }

    private Map<String, Object> awaitProbe(CompletableFuture<Map<String, Object>> future,
                                           String serviceKey,
                                           long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            future.cancel(true);
            log.warn("Internal service {} health probe skipped: aggregate budget exhausted", serviceKey);
            return down();
        }
        long waitNanos = Math.min(remainingNanos, probeTimeout.toNanos());
        try {
            return future.get(waitNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            log.warn("Internal service {} health probe timed out after {} ms",
                    serviceKey, TimeUnit.NANOSECONDS.toMillis(waitNanos));
            return down();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            log.warn("Internal service {} health probe interrupted", serviceKey);
            return down();
        } catch (ExecutionException ex) {
            log.warn("Internal service {} health probe failed: {}", serviceKey, String.valueOf(ex.getCause()));
            return down();
        }
    }

    private static Map<String, Object> up() {
        return Map.of("status", "UP");
    }

    private static Map<String, Object> down() {
        return Map.of("status", "DOWN");
    }

    private boolean allUp(Map<String, Object> runtime,
                          Map<String, Object> capability,
                          Map<String, Object> model,
                          Map<String, Object> knowledge) {
        return "UP".equals(runtime.get("status"))
                && "UP".equals(capability.get("status"))
                && "UP".equals(model.get("status"))
                && "UP".equals(knowledge.get("status"));
    }
}

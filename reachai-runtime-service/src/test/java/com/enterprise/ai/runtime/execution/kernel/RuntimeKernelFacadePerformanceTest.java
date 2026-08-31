package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Representative end-to-end latency gate for the additive V2 facade/event/trace projection layer.
 * The direct engine is the mechanically moved pre-facade execution baseline; the simulated 20 ms
 * model boundary keeps this benchmark representative of production I/O instead of a parser microbench.
 */
class RuntimeKernelFacadePerformanceTest {

    private static final int WARMUP_ROUNDS = 20;
    private static final int SAMPLE_COUNT = 100;
    private static final long MODEL_LATENCY_NANOS = 20_000_000L;
    private static final long CLOCK_TOLERANCE_NANOS = 250_000L;
    private static final String GRAPH = """
            {
              "schemaVersion":2,
              "entryNodeId":"llm",
              "exitNodeIds":["llm"],
              "nodes":[{"id":"llm","type":"LLM","config":{
                "modelInstanceId":"benchmark-model","userPrompt":"{{ input }}"
              }}],
              "edges":[]
            }
            """;
    private static final Map<String, Object> INPUT = Map.of("message", "benchmark");

    @Test
    void facadeP95AddsNoMoreThanFivePercentToRepresentativeExecution() {
        RuntimeModelServiceClient model = request -> {
            LockSupport.parkNanos(MODEL_LATENCY_NANOS);
            return new ModelChatResult(200, "ok",
                    new ModelChatData("answer", "benchmark-model", "test", null, null, null, "stop"));
        };
        ObjectMapper mapper = new ObjectMapper();
        RuntimeCapabilityCatalogClient capability = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeControlCatalogClient control = mock(RuntimeControlCatalogClient.class);
        RuntimeGraphSpecExecutionEngine baseline =
                new RuntimeGraphSpecExecutionEngine(mapper, model, capability, control);
        RuntimeGraphSpecExecutor candidate =
                new RuntimeGraphSpecExecutor(mapper, model, capability, control);

        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            assertTrue(baseline.execute(GRAPH, INPUT).success());
            assertTrue(candidate.execute(GRAPH, INPUT).success());
        }

        long[] baselineSamples = new long[SAMPLE_COUNT];
        long[] candidateSamples = new long[SAMPLE_COUNT];
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            if ((i & 1) == 0) {
                baselineSamples[i] = measure(() -> baseline.execute(GRAPH, INPUT));
                candidateSamples[i] = measure(() -> candidate.execute(GRAPH, INPUT));
            } else {
                candidateSamples[i] = measure(() -> candidate.execute(GRAPH, INPUT));
                baselineSamples[i] = measure(() -> baseline.execute(GRAPH, INPUT));
            }
        }

        long baselineP95 = percentile95(baselineSamples);
        long candidateP95 = percentile95(candidateSamples);
        double overheadPercent = ((double) candidateP95 / baselineP95 - 1.0d) * 100.0d;
        System.out.printf(
                "Runtime Kernel V2 representative P95: baseline=%.3fms candidate=%.3fms overhead=%.2f%%%n",
                baselineP95 / 1_000_000.0d, candidateP95 / 1_000_000.0d, overheadPercent);
        assertTrue(candidateP95 <= Math.round(baselineP95 * 1.05d) + CLOCK_TOLERANCE_NANOS,
                () -> "Runtime Kernel V2 P95 overhead exceeded 5%: baseline=" + baselineP95
                        + "ns candidate=" + candidateP95 + "ns overhead=" + overheadPercent + "%");
    }

    private static long measure(java.util.function.Supplier<RuntimeGraphSpecExecutionResult> execution) {
        long started = System.nanoTime();
        RuntimeGraphSpecExecutionResult result = execution.get();
        long elapsed = System.nanoTime() - started;
        assertTrue(result.success());
        return elapsed;
    }

    private static long percentile95(long[] samples) {
        long[] sorted = Arrays.copyOf(samples, samples.length);
        Arrays.sort(sorted);
        return sorted[(int) Math.ceil(sorted.length * 0.95d) - 1];
    }
}

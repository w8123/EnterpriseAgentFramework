package com.enterprise.ai.runtime.execution.capability;

import com.enterprise.ai.common.capability.CapabilityInvocationRequest;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogFeignClient;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityInternalAuthSigner;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeBusinessMethodScalarOutputParityTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String GRAPH = """
            {"nodes":[{"id":"method","type":"TOOL","ref":{"kind":"TOOL","qualifiedName":"orders:normalize"},
              "config":{"inputMapping":{"orderNo":"params.orderNo"}}},
              {"id":"variable","type":"VARIABLE_ASSIGN","config":{"assignments":{"normalized":"nodeOutput.method"}}}],
              "edges":[{"from":"method","to":"variable"}],"entryNodeId":"method","exitNodeIds":["variable"]}
            """;

    static Stream<Arguments> declaredScalars() {
        return Stream.of(Arguments.of("java.lang.Integer", 2),
                Arguments.of("java.lang.String", "N-A-1024"), Arguments.of("java.lang.Boolean", false));
    }

    @ParameterizedTest
    @MethodSource("declaredScalars")
    void savedDraftAndPublishedExecutionAssignTheSameDeclaredScalarRoot(String returnType, Object value) throws Exception {
        var owner = new ConsoleCapabilityInvocationContracts.InvocationContext(1,
                "orders_normalize", "orders:normalize", "orders:normalize", "BUSINESS_METHOD", 41L, "orders",
                "a".repeat(64), "a".repeat(64), "a".repeat(64), "READY", true, "READ_ONLY",
                List.of(new ConsoleCapabilityInvocationContracts.Parameter("orderNo", "String", null,
                        true, "BODY", List.of(), Map.of())), null, returnType, null, null,
                "UNKNOWN", true, false, true, null, null, 30_000, "d".repeat(64));
        var transport = mock(RuntimeCapabilityCatalogFeignClient.class);
        when(transport.businessMethodExecutionContext(anyString(), anyMap(), any(byte[].class))).thenReturn(owner);
        when(transport.invokeCapability(anyMap(), any(byte[].class))).thenAnswer(invocation -> {
            var request = json.readValue((byte[]) invocation.getArgument(1), CapabilityInvocationRequest.class);
            return new CapabilityInvocationResponse(1, request.invocationId(), request.qualifiedName(),
                    "orders_normalize", "Normalize order", CapabilityInvocationStatus.SUCCEEDED, true,
                    value, null, null, null, false, 1L, 1, null, Map.of());
        });
        var gateway = new RuntimeCapabilityCatalogGateway(transport,
                new RuntimeCapabilityInternalAuthSigner("scalar-output-parity-auth-secret-32bytes"), json);
        var executor = new RuntimeGraphSpecExecutor(json, mock(RuntimeModelServiceClient.class), gateway,
                mock(RuntimeControlCatalogClient.class));
        var input = Map.<String, Object>of("params", Map.of("orderNo", "A-1024"));
        var studioIdentity = WorkflowExecutionIdentity.fromAttestedStudioProjectTest(41L, "orders", "42");
        var pin = new WorkflowReadOnlyTrialPolicy.BusinessMethodTrialScope(41L, "orders", Map.of("method", owner),
                System.currentTimeMillis() + 40_000);
        var trial = gateway.withBusinessMethodDraftScope(pin, () -> true,
                () -> executor.execute(GRAPH, input, studioIdentity));
        var published = executor.execute(GRAPH, input,
                WorkflowExecutionIdentity.fromAutomation("default", 41L, "orders", "automation-experiment"));
        assertTrue(trial.success(), trial.answer());
        assertTrue(published.success(), published.answer());
        assertEquals(value, json.readTree(trial.answer()).path("assignments").path("var.normalized").traverse(json).readValueAs(Object.class));
        assertEquals(value, json.readTree(published.answer()).path("assignments").path("var.normalized").traverse(json).readValueAs(Object.class));
        assertEquals(published.answer(), trial.answer());
    }
}

package com.enterprise.ai.runtime.eval;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContext;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContextResolver;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeEvalTargetSnapshotServiceTest {

    @Test
    void capturesExactDraftConfigAndNormalizesEmbeddedJsonBeforeFingerprinting() {
        RuntimeAgentExecutionContextResolver resolver = mock(RuntimeAgentExecutionContextResolver.class);
        RuntimeEvalTargetSnapshotMapper mapper = mock(RuntimeEvalTargetSnapshotMapper.class);
        RuntimeEvalTargetSnapshotService service = new RuntimeEvalTargetSnapshotService(
                resolver, mapper, new ObjectMapper());
        RuntimeAgentExecutionContext first = context("{\"policy\":{\"b\":2,\"a\":1}}");
        RuntimeAgentExecutionContext reordered = context(" { \"policy\" : { \"a\" : 1, \"b\" : 2 } } ");
        when(resolver.resolveForEvaluation("agent-1", 12L))
                .thenReturn(Optional.of(first), Optional.of(reordered));
        when(mapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            RuntimeEvalTargetSnapshotEntity entity = invocation.getArgument(0);
            entity.setId(100L);
            return 1;
        }).when(mapper).insert(any());

        RuntimeEvalTargetSnapshotService.CapturedTarget firstCapture =
                service.captureAgent("agent-1", 12L, "tenant-a", "tester");
        RuntimeEvalTargetSnapshotService.CapturedTarget secondCapture =
                service.captureAgent("agent-1", 12L, "tenant-a", "tester");

        assertEquals("DRAFT", firstCapture.snapshot().getSourceStatus());
        assertEquals(12L, firstCapture.snapshot().getAgentConfigVersionId());
        assertEquals(64, firstCapture.snapshot().getFingerprintSha256().length());
        assertEquals(firstCapture.snapshot().getFingerprintSha256(),
                secondCapture.snapshot().getFingerprintSha256());
        assertEquals(firstCapture.snapshot().getSnapshotJson(), secondCapture.snapshot().getSnapshotJson());
        assertTrue(firstCapture.snapshot().getSnapshotJson().contains("\"sourceStatus\":\"DRAFT\""));
        assertFalse(firstCapture.snapshot().getSnapshotJson().contains("createdAt"));

        when(mapper.selectById(100L)).thenReturn(firstCapture.snapshot());
        RuntimeEvalTargetSnapshotService.CapturedTarget restored = service.loadAgentSnapshot(100L);
        assertEquals("agent-1", restored.executionContext().agent().id());
        assertEquals(12L, restored.executionContext().config().getId());
        assertEquals("DRAFT", restored.executionContext().config().getStatus());
        assertEquals("{\"policy\":{\"a\":1,\"b\":2}}",
                restored.executionContext().config().getConfigJson());

        firstCapture.snapshot().setSnapshotJson(firstCapture.snapshot().getSnapshotJson() + " ");
        IllegalStateException tampered = assertThrows(IllegalStateException.class,
                () -> service.loadAgentSnapshot(100L));
        assertTrue(tampered.getMessage().contains("fingerprint verification failed"));
        verify(resolver, org.mockito.Mockito.times(2)).resolveForEvaluation("agent-1", 12L);
    }

    private RuntimeAgentExecutionContext context(String configJson) {
        RuntimeAgentExecutionView agent = new RuntimeAgentExecutionView(
                "agent-1", 7L, "orders", "orders-agent", "Orders Agent", "query orders",
                "PROJECT", "[\"orders:user\"]", true, 11L, null, null);
        RuntimeAgentConfigVersionEntity config = new RuntimeAgentConfigVersionEntity();
        config.setId(12L);
        config.setAgentId("agent-1");
        config.setVersionNo(2);
        config.setStatus("DRAFT");
        config.setRuntimeType("AGENTSCOPE");
        config.setPolicyProfile("STANDARD");
        config.setToolCatalogMode("ALLOW_LIST");
        config.setConfigJson(configJson);
        return new RuntimeAgentExecutionContext(
                agent, config, List.of(), List.of(), List.of(), List.of(),
                new RuntimeAgentExecutionContext.ResolveTimings(0, 0, 0, 0, 0));
    }
}

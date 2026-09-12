package com.enterprise.ai.runtime.eval;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
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
    void replayPreservesPublishedDefaultsForEachPinnedVersionOfTheSameWorkflow() {
        var resolver = mock(RuntimeAgentExecutionContextResolver.class);
        var mapper = mock(RuntimeEvalTargetSnapshotMapper.class);
        var service = new RuntimeEvalTargetSnapshotService(resolver, mapper, new ObjectMapper());
        var empty = context("{}");
        var first = tool(9L); var second = tool(10L);
        var workflow = com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionView.builder()
                .id("w1").name("Orders").status("ACTIVE").defaultModelInstanceId("unpublished-model").build();
        var targets = java.util.stream.Stream.of(first, second).map(tool -> {
            var version = com.enterprise.ai.runtime.workflow.RuntimeWorkflowPublishedVersionView.builder()
                    .id(tool.getWorkflowVersionId()).workflowId("w1").version("v" + tool.getWorkflowVersionId())
                    .status("RETIRED").graphSpecSnapshotJson("{\"nodes\":[]}")
                    .snapshotJson("{\"id\":\"w1\",\"defaultModelInstanceId\":\"published-" + tool.getWorkflowVersionId() + "\"}").build();
            return new com.enterprise.ai.runtime.agent.RuntimeResolvedWorkflowTarget(tool, workflow, version);
        }).toList();
        var resolved = new RuntimeAgentExecutionContext(empty.agent(), empty.config(), List.of(first, second), List.of(), targets, empty.timings());
        when(resolver.resolveForEvaluation("agent-1", 12L)).thenReturn(Optional.of(resolved));
        doAnswer(call -> { ((RuntimeEvalTargetSnapshotEntity) call.getArgument(0)).setId(100L); return 1; }).when(mapper).insert(any());
        var captured = service.captureAgent("agent-1", 12L, "tenant-a", "tester");
        when(mapper.selectById(100L)).thenReturn(captured.snapshot());
        var replay = service.loadAgentSnapshot(100L).executionContext();
        assertEquals(java.util.Map.of(9L, "published-9", 10L, "published-10"), replay.resolvedTargets().stream()
                .collect(java.util.stream.Collectors.toMap(target -> target.version().getId(), target ->
                        com.enterprise.ai.runtime.workflow.RuntimePublishedWorkflowSnapshot.read(target.version()).defaultModelInstanceId())));
    }

    private com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot tool(Long versionId) {
        com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot tool = com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot.builder()
                .workflowId("w1")
                .workflowVersionId(versionId)
                .toolName("orders_" + versionId)
                .enabled(true)
                .build(); return tool;
    }

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
        RuntimeAgentConfigSnapshot config = RuntimeAgentConfigSnapshot.builder()
                .id(12L)
                .agentId("agent-1")
                .versionNo(2)
                .status("DRAFT")
                .runtimeType("AGENTSCOPE")
                .policyProfile("STANDARD")
                .toolCatalogMode("ALLOW_LIST")
                .configJson(configJson)
                .build();
        return new RuntimeAgentExecutionContext(
                agent, config, List.of(), List.of(), List.of(), List.of(),
                new RuntimeAgentExecutionContext.ResolveTimings(0, 0, 0, 0, 0));
    }
}

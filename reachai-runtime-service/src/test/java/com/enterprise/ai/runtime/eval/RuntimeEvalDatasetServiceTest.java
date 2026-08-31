package com.enterprise.ai.runtime.eval;

import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsQueryService;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDetailView;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsSummaryView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeEvalDatasetServiceTest {

    @Test
    void publishesCanonicalImmutableVersionRejectsNoopAndDetectsItemTampering() {
        RuntimeEvalDatasetMapper datasetMapper = mock(RuntimeEvalDatasetMapper.class);
        RuntimeEvalDatasetVersionMapper versionMapper = mock(RuntimeEvalDatasetVersionMapper.class);
        RuntimeEvalDatasetItemMapper itemMapper = mock(RuntimeEvalDatasetItemMapper.class);
        RuntimeEvalJsonSupport json = new RuntimeEvalJsonSupport(new ObjectMapper());
        RuntimeEvalDatasetService service = new RuntimeEvalDatasetService(
                datasetMapper,
                versionMapper,
                itemMapper,
                mock(RuntimeAgentMapper.class),
                mock(RuntimeRunOpsQueryService.class),
                json);

        RuntimeEvalDatasetEntity dataset = new RuntimeEvalDatasetEntity();
        dataset.setId(1L);
        dataset.setTenantId("default");
        dataset.setProjectCode("orders");
        dataset.setTargetType("AGENT");
        dataset.setTargetId("agent-1");
        dataset.setName("Regression");
        dataset.setStatus("ACTIVE");
        dataset.setVersionCount(0);
        dataset.setCreatedBy("tester");
        when(datasetMapper.selectByIdForUpdate(1L)).thenReturn(dataset);

        AtomicReference<RuntimeEvalDatasetVersionEntity> insertedVersion = new AtomicReference<>();
        doAnswer(invocation -> {
            RuntimeEvalDatasetVersionEntity value = invocation.getArgument(0);
            value.setId(10L);
            insertedVersion.set(value);
            return 1;
        }).when(versionMapper).insert(any());
        when(versionMapper.selectById(10L)).thenAnswer(invocation -> insertedVersion.get());

        AtomicLong nextItemId = new AtomicLong(100L);
        List<RuntimeEvalDatasetItemEntity> insertedItems = new ArrayList<>();
        doAnswer(invocation -> {
            RuntimeEvalDatasetItemEntity value = invocation.getArgument(0);
            value.setId(nextItemId.getAndIncrement());
            insertedItems.add(value);
            return 1;
        }).when(itemMapper).insert(any());
        when(itemMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(insertedItems));
        when(itemMapper.selectById(any())).thenAnswer(invocation -> insertedItems.stream()
                .filter(value -> value.getId().equals(invocation.<Long>getArgument(0)))
                .findFirst()
                .orElse(null));

        Map<String, Object> request = Map.of(
                "changeNote", "initial",
                "createdBy", "tester",
                "items", List.of(
                        Map.of(
                                "itemKey", "success",
                                "message", "query order",
                                "input", Map.of("orderId", 7, "message", "query order"),
                                "expected", Map.of("success", true),
                                "tags", List.of("smoke"),
                                "enabled", true),
                        Map.of(
                                "itemKey", "write-blocked",
                                "message", "delete order",
                                "input", Map.of("message", "delete order"),
                                "expected", Map.of("success", false, "code", "EVAL_SIDE_EFFECT_BLOCKED"),
                                "tags", List.of("safety"),
                                "enabled", true)));

        RuntimeEvalDatasetVersionView created = service.createVersion(1L, request);

        assertEquals(10L, created.id());
        assertEquals(2, created.itemCount());
        assertEquals(64, created.fingerprintSha256().length());
        assertEquals(2, created.items().size());
        assertTrue(created.items().stream().allMatch(value -> value.contentSha256().length() == 64));
        assertEquals(10L, dataset.getCurrentVersionId());
        assertEquals(1, dataset.getVersionCount());

        IllegalArgumentException noChange = assertThrows(IllegalArgumentException.class,
                () -> service.createVersion(1L, request));
        assertTrue(noChange.getMessage().contains("no material content change"));

        RuntimeEvalDatasetItemEntity first = insertedItems.get(0);
        assertEquals(first.getId(), service.requiredVerifiedItem(10L, first.getId()).getId());
        first.setMessage("tampered message");
        IllegalStateException tampered = assertThrows(IllegalStateException.class,
                () -> service.requiredVerifiedItem(10L, first.getId()));
        assertTrue(tampered.getMessage().contains("fingerprint verification failed"));
    }

    @Test
    void refusesToTreatAnUnattributedRunOpsTraceAsAgentEvidence() {
        RuntimeEvalDatasetMapper datasetMapper = mock(RuntimeEvalDatasetMapper.class);
        RuntimeRunOpsQueryService runOps = mock(RuntimeRunOpsQueryService.class);
        RuntimeEvalDatasetService service = new RuntimeEvalDatasetService(
                datasetMapper,
                mock(RuntimeEvalDatasetVersionMapper.class),
                mock(RuntimeEvalDatasetItemMapper.class),
                mock(RuntimeAgentMapper.class),
                runOps,
                new RuntimeEvalJsonSupport(new ObjectMapper()));
        RuntimeEvalDatasetEntity dataset = new RuntimeEvalDatasetEntity();
        dataset.setId(1L);
        dataset.setTargetId("agent-1");
        dataset.setStatus("ACTIVE");
        dataset.setCurrentVersionId(10L);
        when(datasetMapper.selectByIdForUpdate(1L)).thenReturn(dataset);
        RuntimeRunOpsDetailView detail = mock(RuntimeRunOpsDetailView.class);
        RuntimeRunOpsSummaryView summary = mock(RuntimeRunOpsSummaryView.class);
        when(detail.summary()).thenReturn(summary);
        when(summary.agentId()).thenReturn(null);
        when(runOps.detail("trace-1")).thenReturn(detail);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createVersionFromTrace(1L, Map.of("traceId", "trace-1")));

        assertTrue(error.getMessage().contains("not attributable to an Agent"));
    }
}

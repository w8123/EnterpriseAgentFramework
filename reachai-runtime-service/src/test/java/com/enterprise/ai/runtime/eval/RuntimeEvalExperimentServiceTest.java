package com.enterprise.ai.runtime.eval;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContext;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionView;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeEvalExperimentServiceTest {

    @Test
    void capturesBaselineAndDraftCandidateThenFansOutDurableTasks() {
        RuntimeEvalExperimentMapper experimentMapper = mock(RuntimeEvalExperimentMapper.class);
        RuntimeEvalExperimentVariantMapper variantMapper = mock(RuntimeEvalExperimentVariantMapper.class);
        RuntimeEvalExperimentItemMapper itemMapper = mock(RuntimeEvalExperimentItemMapper.class);
        RuntimeEvalScoreMapper scoreMapper = mock(RuntimeEvalScoreMapper.class);
        RuntimeEvalTaskMapper taskMapper = mock(RuntimeEvalTaskMapper.class);
        RuntimeEvalDatasetService datasetService = mock(RuntimeEvalDatasetService.class);
        RuntimeEvalDatasetMapper datasetMapper = mock(RuntimeEvalDatasetMapper.class);
        RuntimeEvalEvaluatorSuiteService suiteService = mock(RuntimeEvalEvaluatorSuiteService.class);
        RuntimeEvalTargetSnapshotService snapshotService = mock(RuntimeEvalTargetSnapshotService.class);
        RuntimeEvalJsonSupport json = new RuntimeEvalJsonSupport(new ObjectMapper());
        RuntimeEvalExperimentService service = new RuntimeEvalExperimentService(
                experimentMapper,
                variantMapper,
                itemMapper,
                scoreMapper,
                taskMapper,
                datasetService,
                datasetMapper,
                suiteService,
                snapshotService,
                mock(RuntimeAgentMapper.class),
                json);

        RuntimeEvalDatasetVersionEntity datasetVersion = new RuntimeEvalDatasetVersionEntity();
        datasetVersion.setId(10L);
        datasetVersion.setDatasetId(20L);
        datasetVersion.setVersionNo(3);
        datasetVersion.setStatus("PUBLISHED");
        datasetVersion.setFingerprintSha256("dataset-fingerprint");
        datasetVersion.setItemCount(2);
        when(datasetService.requiredVersion(10L)).thenReturn(datasetVersion);

        RuntimeEvalDatasetEntity dataset = new RuntimeEvalDatasetEntity();
        dataset.setId(20L);
        dataset.setTenantId("default");
        dataset.setProjectCode("orders");
        dataset.setTargetType("AGENT");
        dataset.setTargetId("agent-1");
        dataset.setName("Orders regression");
        when(datasetMapper.selectById(20L)).thenReturn(dataset);

        RuntimeEvalDatasetItemEntity firstItem = datasetItem(31L, 10L, "first");
        RuntimeEvalDatasetItemEntity secondItem = datasetItem(32L, 10L, "second");
        when(datasetService.enabledItems(10L)).thenReturn(List.of(firstItem, secondItem));

        RuntimeEvalEvaluatorSuiteVersionEntity suite = new RuntimeEvalEvaluatorSuiteVersionEntity();
        suite.setId(40L);
        suite.setTenantId("default");
        suite.setName("deterministic-default");
        suite.setVersionNo(1);
        suite.setStatus("PUBLISHED");
        suite.setConfigJson("{}");
        suite.setFingerprintSha256(json.sha256("{}"));
        when(suiteService.resolve(isNull(), eq("default"), eq("platform:42:alice"))).thenReturn(suite);

        RuntimeEvalTargetSnapshotService.CapturedTarget baseline = capturedTarget(
                51L, 100L, "ACTIVE", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        RuntimeEvalTargetSnapshotService.CapturedTarget candidate = capturedTarget(
                52L, 101L, "DRAFT", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        when(snapshotService.captureAgent("orders-agent", 100L, "default", "platform:42:alice"))
                .thenReturn(baseline);
        when(snapshotService.captureAgent("orders-agent", 101L, "default", "platform:42:alice"))
                .thenReturn(candidate);

        AtomicReference<RuntimeEvalExperimentEntity> insertedExperiment = new AtomicReference<>();
        doAnswer(invocation -> {
            RuntimeEvalExperimentEntity value = invocation.getArgument(0);
            value.setId(99L);
            insertedExperiment.set(value);
            return 1;
        }).when(experimentMapper).insert(any());
        when(experimentMapper.selectById(99L)).thenAnswer(invocation -> insertedExperiment.get());

        AtomicLong nextVariantId = new AtomicLong(201L);
        List<RuntimeEvalExperimentVariantEntity> insertedVariants = new ArrayList<>();
        doAnswer(invocation -> {
            RuntimeEvalExperimentVariantEntity value = invocation.getArgument(0);
            value.setId(nextVariantId.getAndIncrement());
            insertedVariants.add(value);
            return 1;
        }).when(variantMapper).insert(any());
        when(variantMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(insertedVariants));

        AtomicLong nextExperimentItemId = new AtomicLong(301L);
        List<RuntimeEvalExperimentItemEntity> insertedItems = new ArrayList<>();
        doAnswer(invocation -> {
            RuntimeEvalExperimentItemEntity value = invocation.getArgument(0);
            value.setId(nextExperimentItemId.getAndIncrement());
            insertedItems.add(value);
            return 1;
        }).when(itemMapper).insert(any());
        when(itemMapper.selectCount(any())).thenAnswer(invocation -> (long) insertedItems.size());
        when(itemMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(insertedItems));

        List<RuntimeEvalTaskEntity> insertedTasks = new ArrayList<>();
        doAnswer(invocation -> {
            RuntimeEvalTaskEntity value = invocation.getArgument(0);
            value.setId(400L + insertedTasks.size());
            insertedTasks.add(value);
            return 1;
        }).when(taskMapper).insert(any());
        when(scoreMapper.selectList(any())).thenReturn(List.of());
        when(datasetService.getVersion(10L)).thenReturn(new RuntimeEvalDatasetVersionView(
                10L, 20L, 3, "PUBLISHED", "dataset-fingerprint", 2,
                "candidate comparison", "platform:42:alice", null, null, List.of()));

        Map<String, Object> request = Map.of(
                "tenantId", "default",
                "createdBy", "platform:42:alice",
                "targetId", "orders-agent",
                "datasetVersionId", 10L,
                "repeatCount", 2,
                "name", "Active versus draft",
                "variants", List.of(
                        Map.of(
                                "key", "baseline",
                                "displayName", "Active",
                                "role", "BASELINE",
                                "configVersionId", 100L),
                        Map.of(
                                "key", "candidate",
                                "displayName", "Draft",
                                "role", "CANDIDATE",
                                "configVersionId", 101L)));

        RuntimeEvalExperimentDetailView result = service.create(request);

        assertEquals(99L, result.experiment().id());
        assertEquals("agent-1", result.experiment().targetId());
        assertEquals("orders", result.experiment().projectCode());
        assertEquals("QUEUED", result.experiment().status());
        assertEquals(2, result.experiment().variantCount());
        assertEquals(8, result.experiment().taskCount());
        assertEquals(2, insertedVariants.size());
        assertEquals(8, insertedItems.size());
        assertEquals(8, insertedTasks.size());
        assertTrue(insertedTasks.stream().allMatch(value -> "PENDING".equals(value.getStatus())));
        assertEquals("ACTIVE", insertedVariants.get(0).getTargetConfigStatus());
        assertEquals("DRAFT", insertedVariants.get(1).getTargetConfigStatus());
        assertEquals(100L, insertedVariants.get(0).getTargetConfigVersionId());
        assertEquals(101L, insertedVariants.get(1).getTargetConfigVersionId());
        assertEquals(51L, insertedVariants.get(0).getTargetSnapshotId());
        assertEquals(52L, insertedVariants.get(1).getTargetSnapshotId());
        verify(snapshotService).captureAgent("orders-agent", 100L, "default", "platform:42:alice");
        verify(snapshotService).captureAgent("orders-agent", 101L, "default", "platform:42:alice");
    }

    private RuntimeEvalDatasetItemEntity datasetItem(Long id, Long versionId, String itemKey) {
        RuntimeEvalDatasetItemEntity item = new RuntimeEvalDatasetItemEntity();
        item.setId(id);
        item.setDatasetVersionId(versionId);
        item.setItemKey(itemKey);
        item.setEnabled(true);
        return item;
    }

    private RuntimeEvalTargetSnapshotService.CapturedTarget capturedTarget(
            Long snapshotId,
            Long configVersionId,
            String status,
            String fingerprint) {
        RuntimeAgentExecutionView agent = new RuntimeAgentExecutionView(
                "agent-1", 7L, "orders", "orders-agent", "Orders Agent", "query orders",
                "PROJECT", "[\"orders:user\"]", true, 100L, null, null);
        RuntimeAgentConfigVersionEntity config = new RuntimeAgentConfigVersionEntity();
        config.setId(configVersionId);
        config.setAgentId("agent-1");
        config.setVersionNo(configVersionId.intValue());
        config.setStatus(status);
        config.setRuntimeType("AGENTSCOPE");
        config.setPolicyProfile("STANDARD");
        config.setToolCatalogMode("ALLOW_LIST");
        config.setConfigJson("{}");
        RuntimeAgentExecutionContext context = new RuntimeAgentExecutionContext(
                agent, config, List.of(), List.of(), List.of(), List.of(),
                new RuntimeAgentExecutionContext.ResolveTimings(0, 0, 0, 0, 0));
        RuntimeEvalTargetSnapshotEntity snapshot = new RuntimeEvalTargetSnapshotEntity();
        snapshot.setId(snapshotId);
        snapshot.setTenantId("default");
        snapshot.setTargetType("AGENT");
        snapshot.setTargetId("agent-1");
        snapshot.setAgentConfigVersionId(configVersionId);
        snapshot.setSourceStatus(status);
        snapshot.setFingerprintSha256(fingerprint);
        return new RuntimeEvalTargetSnapshotService.CapturedTarget(snapshot, context);
    }
}

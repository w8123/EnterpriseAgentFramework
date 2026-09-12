package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.runtime.execution.RuntimeAgentRunLifecyclePort.AgentTarget;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuntimeRunLifecycleSnapshotTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                RuntimeRunEntity.class);
    }

    private final ObjectMapper json = new ObjectMapper();
    private final RuntimeRunMapper mapper = mock(RuntimeRunMapper.class);
    private final RuntimeRunLifecycleService lifecycle = new RuntimeRunLifecycleService(mapper, json);
    private final AtomicReference<RuntimeRunEntity> stored = new AtomicReference<>();

    @Test
    void agentAuditKeepsFrozenCatalogFactsAndTrustedIdentity() throws Exception {
        captureRun();
        var names = new ArrayList<>(List.of("orders.read"));
        var configuration = new RuntimeRunSnapshots.AgentConfiguration(91L, 4, "AGENTSCOPE", 1, names);
        names.add("later-catalog-edit");
        assertThrows(UnsupportedOperationException.class, () -> configuration.workflowToolNames().clear());
        var agent = new AgentTarget("agent-1", "orders-agent", "Orders Agent", 7L, "orders");

        lifecycle.beginAgent("trace-agent", "span-root", LocalDateTime.now(), agent, configuration,
                Map.of("entryType", "API", "userId", "forged-user", "message", "private input"),
                WorkflowExecutionIdentity.fromAgent("tenant-1", 7L, "orders", "trusted-user"));

        var row = stored.get();
        var snapshot = json.readTree(row.getSnapshotJson());
        assertEquals(91L, row.getAgentConfigVersionId());
        assertEquals(4, row.getAgentConfigVersion());
        assertEquals("AGENTSCOPE", row.getRuntimeType());
        assertEquals("trusted-user", row.getUserId());
        assertEquals(1, snapshot.path("workflowToolCount").asInt());
        assertEquals(json.valueToTree(List.of("orders.read")), snapshot.path("workflowToolNames"));
        assertFalse(row.getSnapshotJson().contains("later-catalog-edit"));
        assertFalse(row.getInputSummary().contains("private input"));
        assertFalse(row.getInputSummary().contains("forged-user"));
    }

    @Test
    void publishedWorkflowAuditUsesPinnedFactsWithoutPersistingExecutableContent() throws Exception {
        captureRun();
        String graph = "{\"nodes\":[{\"id\":\"read\",\"type\":\"TOOL\",\"config\":{\"token\":\"private-config\"}}]}";
        var workflow = new RuntimeRunSnapshots.PublishedWorkflow("wf-1", "orders", "Published Orders",
                7L, "orders", "GRAPH_SPEC", 44L, "1.0.0", graph);

        lifecycle.beginPublishedWorkflow("trace-workflow", "span-root", "AUTOMATION", workflow,
                Map.of("message", "private input", "userId", "forged-user"),
                WorkflowExecutionIdentity.fromAutomation("tenant-1", 7L, "orders", "automation-principal"));

        var row = stored.get();
        var snapshot = json.readTree(row.getSnapshotJson());
        assertEquals("wf-1", row.getWorkflowId());
        assertEquals("Published Orders", row.getWorkflowName());
        assertEquals(44L, row.getWorkflowVersionId());
        assertEquals("1.0.0", row.getWorkflowVersion());
        assertEquals(7L, row.getProjectId());
        assertEquals("orders", row.getProjectCode());
        assertEquals("AUTOMATION", row.getEntryType());
        assertNull(row.getUserId());
        assertEquals(1, snapshot.path("nodeCount").asInt());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(graph.getBytes(StandardCharsets.UTF_8))), snapshot.path("graphSpecDigest").asText());
        assertFalse(row.getSnapshotJson().contains("private-config"));
        assertFalse(row.getInputSummary().contains("private input"));
    }

    @Test
    void skillAuditPreservesVersionAndScopeWithAnExplicitFieldSet() throws Exception {
        var row = new RuntimeRunEntity();
        row.setId(1L);
        row.setSnapshotJson("{\"agentConfigVersionId\":91}");
        when(mapper.selectOne(any())).thenReturn(row);
        var binding = new RuntimeRunSnapshots.SkillBinding(11L, 21L, "reachai", "demo",
                "PROJECT", "orders", "1.2.3", "a".repeat(64), "MODEL_SELECTED", "DENY", true);

        lifecycle.recordSkillBindings("trace-agent", List.of(binding));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<RuntimeRunEntity>> writes = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), writes.capture());
        assertTrue(writes.getValue().getSqlSet().contains("snapshot_json="));
        assertFalse(writes.getValue().getSqlSet().contains("status="));
        var snapshotJson = writes.getValue().getParamNameValuePairs().values().stream()
                .filter(value -> value instanceof String text && text.startsWith("{"))
                .map(String.class::cast).findFirst().orElseThrow();
        var snapshot = json.readTree(snapshotJson);
        assertEquals(91L, snapshot.path("agentConfigVersionId").asLong());
        assertEquals(json.readTree(json.writeValueAsString(binding.runMetadata())), snapshot.path("skillBindings").get(0));
        assertEquals(Set.of("skillId", "skillVersionId", "publisher", "name", "version", "sourceSha256",
                "activationMode", "scriptPolicy", "visibility", "projectCode"), binding.runMetadata().keySet());
        assertEquals("reachai/demo@1.2.3#" + "a".repeat(64), binding.traceMetadata().get("identity"));
        assertEquals(true, binding.traceMetadata().get("required"));
        assertThrows(UnsupportedOperationException.class, () -> binding.runMetadata().clear());
        assertThrows(UnsupportedOperationException.class, () -> binding.traceMetadata().clear());
        var publicBinding = new RuntimeRunSnapshots.SkillBinding(11L, 21L, "reachai", "demo",
                "PUBLIC", null, "1.2.3", "a".repeat(64), "MODEL_SELECTED", "DENY", false);
        assertFalse(publicBinding.runMetadata().containsKey("projectCode"));
    }

    private void captureRun() {
        when(mapper.insert(any())).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        });
    }
}

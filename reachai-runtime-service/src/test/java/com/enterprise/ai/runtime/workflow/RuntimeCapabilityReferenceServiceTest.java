package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.internal.RuntimeCapabilityReferenceService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.runtime.agent.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuntimeCapabilityReferenceServiceTest {
    final RuntimeWorkflowReferenceIndex index = mock(RuntimeWorkflowReferenceIndex.class);
    final RuntimeWorkflowVersionMapper versions = mock(RuntimeWorkflowVersionMapper.class);
    final RuntimeAgentMapper agents = mock(RuntimeAgentMapper.class);
    final RuntimeAgentWorkflowToolMapper bindings = mock(RuntimeAgentWorkflowToolMapper.class);
    final RuntimeAgentConfigVersionMapper configs = mock(RuntimeAgentConfigVersionMapper.class);
    final RuntimeCapabilityReferenceService service = new RuntimeCapabilityReferenceService(index,
            new RuntimeAgentWorkflowUsageReader(agents, bindings, configs));
    final RuntimeCapabilityReferenceService.CapabilityKey key = new RuntimeCapabilityReferenceService.CapabilityKey("orders:read", "orders_read");

    @BeforeEach void completeEmptyIndex() {
        when(index.versionOwners(any())).thenAnswer(call ->
                new RuntimeWorkflowReferenceIndex(null, null, versions, null).versionOwners(call.getArgument(0)));
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "reference-test"),
                RuntimeWorkflowVersionEntity.class);
        when(index.inspect(any())).thenReturn(new RuntimeWorkflowReferenceIndex.Evidence(List.of(), Set.of()));
    }

    @Test void resolvesDraftRetiredReleaseAndExternallyPinnedAgent() {
        var version = new RuntimeWorkflowVersionEntity(); version.setId(9L); version.setWorkflowId("w1");
        var agent = new RuntimeAgentEntity(); agent.setId("a1"); agent.setName("客服"); agent.setEnabled(true); agent.setActiveConfigVersionId(3L);
        var oldConfig = new RuntimeAgentConfigVersionEntity(); oldConfig.setId(2L); oldConfig.setAgentId("a1");
        var currentConfig = new RuntimeAgentConfigVersionEntity(); currentConfig.setId(3L); currentConfig.setAgentId("a1");
        var binding = new RuntimeAgentWorkflowToolEntity(); binding.setAgentId("a1"); binding.setAgentConfigVersionId(2L); binding.setWorkflowId("w1"); binding.setWorkflowVersionId(9L); binding.setEnabled(true);
        when(index.inspect(any())).thenReturn(new RuntimeWorkflowReferenceIndex.Evidence(List.of(
                new RuntimeWorkflowReferenceIndex.Hit("orders_read", "w1", "查询", null, null, null, "read"),
                new RuntimeWorkflowReferenceIndex.Hit("orders_read", "w1", "查询", 9L, "v1", "RETIRED", "read")), Set.of()));
        when(versions.selectList(any())).thenReturn(List.of(version));
        when(agents.selectList(any())).thenReturn(List.of(agent));
        when(configs.selectBatchIds(any())).thenReturn(List.of(oldConfig, currentConfig));
        when(bindings.selectList(any())).thenReturn(List.of(binding));
        var evidence = service.inspect(new RuntimeCapabilityReferenceService.Query("orders", List.of(key), List.of(2L)));
        assertEquals("COMPLETE", evidence.state()); assertEquals(3, evidence.usages().size());
        assertTrue(evidence.usages().stream().anyMatch(usage -> "AGENT".equals(usage.kind())
                && "EXTERNAL_PIN".equals(usage.stage()) && usage.agentConfigVersionId() == 2L));
    }

    @Test void missingExternalConfigIsExplicitAndScopeCannotBeForged() {
        var evidence = service.inspect(new RuntimeCapabilityReferenceService.Query("orders", List.of(key), List.of(18L)));
        assertEquals("PARTIAL", evidence.state()); assertTrue(evidence.warnings().contains("AGENT_CONFIG_VERSION_MISSING"));
        assertThrows(IllegalArgumentException.class, () -> service.inspect(new RuntimeCapabilityReferenceService.Query("other", List.of(key), List.of())));
        assertThrows(IllegalArgumentException.class, () -> service.inspect(new RuntimeCapabilityReferenceService.Query("orders",
                List.of(key, new RuntimeCapabilityReferenceService.CapabilityKey("orders:other", "orders_read")), List.of())));
    }

    @Test void incompleteIndexCannotBecomeZeroUsageProof() {
        when(index.inspect(any())).thenReturn(new RuntimeWorkflowReferenceIndex.Evidence(List.of(), Set.of("REFERENCE_INDEX_INCOMPLETE")));
        assertEquals("PARTIAL", service.inspect(new RuntimeCapabilityReferenceService.Query("orders", List.of(key), List.of())).state());
    }

    @Test void wrongWorkflowOwnerCannotBecomeAnAgentCapabilityReference() {
        var version = new RuntimeWorkflowVersionEntity();
        version.setId(9L);
        version.setWorkflowId("different-workflow");
        when(versions.selectList(any())).thenReturn(List.of(version));
        when(index.inspect(any())).thenReturn(new RuntimeWorkflowReferenceIndex.Evidence(List.of(
                new RuntimeWorkflowReferenceIndex.Hit("orders_read", "w1", "Query", 9L, "v1", "RETIRED", "read")), Set.of()));
        var agentUsage = mock(RuntimeAgentWorkflowUsageQuery.class);
        when(agentUsage.publishedBindings(any())).thenReturn(new RuntimeAgentWorkflowUsageQuery.Evidence(
                List.of(new RuntimeAgentWorkflowUsageQuery.Binding("a1", "Agent", 2L, "w1", 9L, false)), Set.of()));
        var evidence = new RuntimeCapabilityReferenceService(index, agentUsage)
                .inspect(new RuntimeCapabilityReferenceService.Query("orders", List.of(key), List.of(2L)));
        assertEquals("PARTIAL", evidence.state());
        assertEquals(List.of("AGENT_WORKFLOW_VERSION_MISSING"), evidence.warnings());
        assertEquals(1, evidence.usages().size());
        assertEquals("WORKFLOW", evidence.usages().get(0).kind());
        assertEquals("HISTORICAL", evidence.usages().get(0).stage());
    }

    @Test void ownershipQueryAvoidsEmptySqlAndReturnsReadOnlyEvidence() {
        var query = new RuntimeWorkflowReferenceIndex(null, null, versions, null);
        assertEquals(java.util.Map.of(), query.versionOwners(List.of()));
        verifyNoInteractions(versions);
        var version = new RuntimeWorkflowVersionEntity();
        version.setId(9L);
        version.setWorkflowId("w1");
        when(versions.selectList(any())).thenReturn(List.of(version));
        var owners = query.versionOwners(List.of(9L));
        assertEquals("w1", owners.get(9L));
        assertThrows(UnsupportedOperationException.class, () -> owners.put(9L, "forged"));
    }

    @Test void agentFanoutCannotTurnBoundedIndexHitsIntoUnboundedEvidence() {
        var hits = java.util.stream.IntStream.range(0, 6_000).mapToObj(node ->
                new RuntimeWorkflowReferenceIndex.Hit("orders_read", "w1", "查询", 9L, "v1", "ACTIVE", "n" + node)).toList();
        when(index.inspect(any())).thenReturn(new RuntimeWorkflowReferenceIndex.Evidence(hits, Set.of()));
        var version = new RuntimeWorkflowVersionEntity(); version.setId(9L); version.setWorkflowId("w1");
        when(versions.selectList(any())).thenReturn(List.of(version));
        var agentUsage = mock(RuntimeAgentWorkflowUsageQuery.class);
        when(agentUsage.publishedBindings(any())).thenReturn(new RuntimeAgentWorkflowUsageQuery.Evidence(
                List.of(new RuntimeAgentWorkflowUsageQuery.Binding("a1", "客服", 3L, "w1", 9L, true)), Set.of()));
        var bounded = new RuntimeCapabilityReferenceService(index, agentUsage);
        var evidence = bounded.inspect(new RuntimeCapabilityReferenceService.Query("orders", List.of(key), List.of()));
        assertEquals(10_000, evidence.usages().size());
        assertEquals("PARTIAL", evidence.state());
        assertTrue(evidence.warnings().contains("REFERENCE_SCAN_LIMIT"));
        assertTrue(evidence.usages().stream().anyMatch(usage -> "AGENT".equals(usage.kind())));
    }
}

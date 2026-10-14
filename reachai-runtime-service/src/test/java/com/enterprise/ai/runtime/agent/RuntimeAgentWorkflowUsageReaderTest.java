package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeAgentWorkflowUsageReaderTest {
    final RuntimeAgentMapper agents = mock(RuntimeAgentMapper.class);
    final RuntimeAgentWorkflowToolMapper tools = mock(RuntimeAgentWorkflowToolMapper.class);
    final RuntimeAgentConfigVersionMapper configs = mock(RuntimeAgentConfigVersionMapper.class);
    final RuntimeAgentWorkflowUsageReader reader = new RuntimeAgentWorkflowUsageReader(agents, tools, configs);

    @BeforeEach void metadata() {
        for (Class<?> type : List.of(RuntimeAgentEntity.class, RuntimeAgentWorkflowToolEntity.class, RuntimeAgentConfigVersionEntity.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "usage-reader-test"), type);
        when(configs.inspectPinnedCoverage(any())).thenAnswer(call -> {
            var coverage = new RuntimeAgentConfigVersionMapper.PinCoverage();
            coverage.setExistingCount(((java.util.Collection<?>) call.getArgument(0)).size());
            return coverage;
        });
    }

    @Test void eightThousandUnrelatedExternalPinsNeedOnlyAggregateCoverageWithoutHydration() {
        var pins = java.util.stream.LongStream.rangeClosed(1, 8_000).boxed().toList();
        var result = reader.publishedBindings(pins, Map.of());
        assertEquals(List.of(), result.bindings()); assertEquals(Set.of(), result.warnings());
        verify(configs).inspectPinnedCoverage(argThat(ids -> ids.size() == 8_000));
        verify(configs, never()).selectList(any());
        verify(agents, times(1)).selectList(argThat(query -> "id".equals(query.getSqlSelect()) && query.getSqlSegment().contains("limit 1")));
    }

    @Test void unrelatedMissingPinAndOwnerStillProducePartialWarningsWithoutHydration() {
        var coverage = new RuntimeAgentConfigVersionMapper.PinCoverage(); coverage.setExistingCount(1); coverage.setMissingOwnerCount(1);
        doReturn(coverage).when(configs).inspectPinnedCoverage(any());
        var result = reader.publishedBindings(List.of(1L, 999L), Map.of());
        assertEquals(Set.of("AGENT_CONFIG_VERSION_MISSING", "AGENT_CONFIG_OWNER_MISSING"), result.warnings());
        verify(configs, never()).selectList(any());
    }

    @Test void matchingHistoricalExternalPinHydratesOnlyItsConfiguration() {
        var pins = java.util.stream.LongStream.rangeClosed(1, 8_000).boxed().toList();
        var binding = binding("a1", 2L);
        when(tools.selectList(any())).thenReturn(List.of(), List.of(binding));
        var config = new RuntimeAgentConfigVersionEntity(); config.setId(2L); config.setAgentId("a1"); config.setConfigJson("invalid unrelated presentation JSON");
        when(configs.selectList(any())).thenReturn(List.of(config));
        var agent = new RuntimeAgentEntity(); agent.setId("a1"); agent.setEnabled(false); agent.setName("Historical");
        when(agents.selectList(any())).thenReturn(List.of(), List.of(agent));
        var result = reader.publishedBindings(pins, Map.of(9L, "w1"));
        assertEquals(Set.of(), result.warnings()); assertEquals(1, result.bindings().size()); assertFalse(result.bindings().get(0).active());
        verify(configs).selectList(argThat(query -> query instanceof com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?> selected
                && "id,agent_id".equals(selected.getSqlSelect()) && selected.getSqlSegment().contains("id IN")
                && selected.getParamNameValuePairs().size() == 1 && selected.getParamNameValuePairs().containsValue(2L)));
    }

    @Test void aDifferentConfigurationOwnerCannotBecomeAnAgentReference() {
        when(tools.selectList(any())).thenReturn(List.of(), List.of(binding("a1", 2L)));
        var config = new RuntimeAgentConfigVersionEntity(); config.setId(2L); config.setAgentId("other");
        when(configs.selectList(any())).thenReturn(List.of(config));
        var other = new RuntimeAgentEntity(); other.setId("other"); other.setEnabled(true); other.setActiveConfigVersionId(2L);
        when(agents.selectList(any())).thenReturn(List.of(), List.of(other));
        var result = reader.publishedBindings(List.of(2L), Map.of(9L, "w1"));
        assertEquals(List.of(), result.bindings()); assertTrue(result.warnings().contains("AGENT_CONFIG_OWNER_MISSING"));
    }

    private RuntimeAgentWorkflowToolEntity binding(String owner, long config) {
        var binding = new RuntimeAgentWorkflowToolEntity(); binding.setId(1L); binding.setAgentId(owner); binding.setAgentConfigVersionId(config);
        binding.setWorkflowId("w1"); binding.setWorkflowVersionId(9L); binding.setEnabled(true); return binding;
    }
}

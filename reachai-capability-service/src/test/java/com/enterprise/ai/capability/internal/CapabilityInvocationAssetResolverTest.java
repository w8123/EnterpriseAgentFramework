package com.enterprise.ai.capability.internal;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.enterprise.ai.agent.capability.ToolAssetEntity;
import com.enterprise.ai.agent.capability.ToolAssetMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityInvocationAssetResolverTest {

    @Test
    void prefersThePublishedToolCatalog() {
        ToolDefinitionMapper definitions = mock(ToolDefinitionMapper.class);
        ToolAssetMapper assets = mock(ToolAssetMapper.class);
        ToolDefinitionEntity definition = new ToolDefinitionEntity();
        definition.setQualifiedName("orders:queryOrder");
        definition.setName("queryOrder");
        definition.setTitle("Query order");
        definition.setProjectCode("orders");
        definition.setSideEffect("READ_ONLY");
        definition.setEnabled(true);
        when(definitions.selectOne(any(Wrapper.class))).thenReturn(definition);

        CapabilityInvocationAsset resolved =
                new CapabilityInvocationAssetResolver(definitions, assets)
                        .require("orders:queryOrder");

        assertEquals(CapabilityInvocationAsset.Source.TOOL_CATALOG, resolved.source());
        assertEquals("CATALOG_HTTP", resolved.executorType());
        assertEquals("Query order", resolved.title());
        verify(assets, never()).selectOne(any(Wrapper.class));
    }

    @Test
    void fallsBackToKernelAssetWhenThePublishedCatalogHasNoMatch() {
        ToolDefinitionMapper definitions = mock(ToolDefinitionMapper.class);
        ToolAssetMapper assets = mock(ToolAssetMapper.class);
        when(definitions.selectOne(any(Wrapper.class))).thenReturn(null);
        ToolAssetEntity asset = new ToolAssetEntity();
        asset.setQualifiedName("system.echo");
        asset.setToolCode("echo");
        asset.setCapabilityCode("system");
        asset.setExecutorType("echo");
        asset.setSideEffect("read");
        asset.setEnabled(true);
        when(assets.selectOne(any(Wrapper.class))).thenReturn(asset);

        CapabilityInvocationAsset resolved =
                new CapabilityInvocationAssetResolver(definitions, assets).require("system.echo");

        assertEquals(CapabilityInvocationAsset.Source.KERNEL_ASSET, resolved.source());
        assertEquals("system.echo", resolved.qualifiedName());
        assertEquals("ECHO", resolved.executorType());
        assertEquals("READ", resolved.sideEffect());
        assertTrue(resolved.enabled());
    }
}

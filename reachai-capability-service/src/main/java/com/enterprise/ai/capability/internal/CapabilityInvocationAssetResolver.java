package com.enterprise.ai.capability.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.ToolAssetEntity;
import com.enterprise.ai.agent.capability.ToolAssetMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Resolves the two existing Capability stores behind one invocation boundary. */
@Component
public class CapabilityInvocationAssetResolver {

    private final ToolDefinitionMapper toolDefinitionMapper;
    private final ToolAssetMapper toolAssetMapper;

    public CapabilityInvocationAssetResolver(ToolDefinitionMapper toolDefinitionMapper,
                                             ToolAssetMapper toolAssetMapper) {
        this.toolDefinitionMapper = toolDefinitionMapper;
        this.toolAssetMapper = toolAssetMapper;
    }

    public CapabilityInvocationAsset require(String qualifiedName) {
        if (!StringUtils.hasText(qualifiedName)) {
            throw new CapabilityInvocationPolicyException(
                    "CAPABILITY_TOOL_NOT_FOUND",
                    com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory.NOT_FOUND,
                    "Capability qualifiedName is required");
        }
        String key = qualifiedName.trim();
        ToolDefinitionEntity catalog = toolDefinitionMapper.selectOne(
                Wrappers.<ToolDefinitionEntity>lambdaQuery()
                        .eq(ToolDefinitionEntity::getQualifiedName, key)
                        .last("limit 1"));
        if (catalog == null) {
            catalog = toolDefinitionMapper.selectOne(
                    Wrappers.<ToolDefinitionEntity>lambdaQuery()
                            .eq(ToolDefinitionEntity::getName, key)
                            .last("limit 1"));
        }
        if (catalog != null) {
            String resolvedName = text(catalog.getName(), key);
            return new CapabilityInvocationAsset(
                    CapabilityInvocationAsset.Source.TOOL_CATALOG,
                    text(catalog.getQualifiedName(), key),
                    resolvedName,
                    text(catalog.getTitle(), resolvedName),
                    text(catalog.getProjectCode(), null),
                    "CATALOG_HTTP",
                    text(catalog.getSideEffect(), "WRITE"),
                    Boolean.TRUE.equals(catalog.getEnabled()));
        }

        ToolAssetEntity asset = toolAssetMapper.selectOne(
                Wrappers.<ToolAssetEntity>lambdaQuery()
                        .eq(ToolAssetEntity::getQualifiedName, key)
                        .last("limit 1"));
        if (asset != null) {
            String resolvedName = text(asset.getName(), asset.getToolCode(), key);
            return new CapabilityInvocationAsset(
                    CapabilityInvocationAsset.Source.KERNEL_ASSET,
                    text(asset.getQualifiedName(), key),
                    resolvedName,
                    resolvedName,
                    text(asset.getCapabilityCode(), null),
                    upper(asset.getExecutorType(), "UNSUPPORTED"),
                    upper(asset.getSideEffect(), "WRITE"),
                    Boolean.TRUE.equals(asset.getEnabled()));
        }
        throw new CapabilityInvocationPolicyException(
                "CAPABILITY_TOOL_NOT_FOUND",
                com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory.NOT_FOUND,
                "Capability Tool not found: " + key);
    }

    private static String upper(String value, String fallback) {
        String text = text(value, fallback);
        return text == null ? null : text.toUpperCase(java.util.Locale.ROOT);
    }

    private static String text(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }
}

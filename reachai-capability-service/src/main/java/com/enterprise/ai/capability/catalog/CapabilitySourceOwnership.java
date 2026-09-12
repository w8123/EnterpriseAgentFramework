package com.enterprise.ai.capability.catalog;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import org.springframework.util.StringUtils;

/** Source identity is assigned by registry governance, never by catalog edit requests. */
public final class CapabilitySourceOwnership {
    private CapabilitySourceOwnership() { }

    public static void requireCatalogWritable(ScanProjectToolEntity row) {
        if (row != null) requireCatalogWritable(row.getSourceQualifiedName(), row.getSourceLocation());
    }

    public static void requireCatalogWritable(ToolDefinitionEntity tool) {
        if (tool != null) requireCatalogWritable(tool.getSourceQualifiedName(), tool.getSourceLocation());
    }

    public static void requireUnmanagedLocation(String location) {
        requireCatalogWritable(null, location);
    }

    private static void requireCatalogWritable(String sourceQualifiedName, String location) {
        if (StringUtils.hasText(sourceQualifiedName) || isSdkLocation(location)) {
            throw new IllegalArgumentException(
                    "CAPABILITY_SOURCE_OWNED: SDK 能力由来源同步和变化处理维护，请在能力目录处理来源变化");
        }
    }

    public static boolean isSdkLocation(String location) {
        return location != null && location.trim().regionMatches(true, 0, "sdk:", 0, 4);
    }
}

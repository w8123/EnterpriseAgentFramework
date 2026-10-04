package com.enterprise.ai.capability.catalog.businessmethod;

import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

/**
 * Small, shared interpretation of the SDK declaration facts that affect a Console call.
 * Invalid owner metadata is deliberately fail-closed; an absent or empty role declaration is not.
 */
public final class ConsoleBusinessMethodDeclaration {

    private ConsoleBusinessMethodDeclaration() {
    }

    public static boolean hasRequiredRoles(ToolDefinitionEntity tool, ObjectMapper objectMapper) {
        if (tool == null || !StringUtils.hasText(tool.getCapabilityMetadataJson())) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(tool.getCapabilityMetadataJson());
            if (root == null || !root.isObject()) {
                return true;
            }
            JsonNode roles = root.get("requiredRoles");
            if (roles == null || roles.isNull()) {
                return false;
            }
            if (roles.isArray()) {
                for (JsonNode role : roles) {
                    if (role != null && !role.isNull() && StringUtils.hasText(role.asText())) {
                        return true;
                    }
                }
                return false;
            }
            return roles.isTextual() ? StringUtils.hasText(roles.asText()) : true;
        } catch (Exception invalid) {
            return true;
        }
    }

    public static long timeoutMillis(ToolDefinitionEntity tool, ObjectMapper objectMapper) {
        final long defaultTimeout = 30_000L;
        final long maximumTimeout = 60_000L;
        if (tool == null || !StringUtils.hasText(tool.getCapabilityMetadataJson())) {
            return defaultTimeout;
        }
        try {
            JsonNode root = objectMapper.readTree(tool.getCapabilityMetadataJson());
            JsonNode raw = root == null ? null : root.get("timeoutMs");
            if (raw == null || !raw.canConvertToLong() || raw.asLong() <= 0L) {
                return defaultTimeout;
            }
            return Math.min(maximumTimeout, raw.asLong());
        } catch (Exception ignored) {
            return defaultTimeout;
        }
    }
}

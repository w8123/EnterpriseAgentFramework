package com.enterprise.ai.control.mcp.domain.client;

import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.McpDomainText;

import java.time.LocalDateTime;
import java.util.List;

/** MCP Client credential bound to exactly one publication; terminal states are non-reversible. */
public record McpClient(
        Long id,
        long publicationId,
        String name,
        Long projectId,
        String projectCode,
        String environment,
        String tenantId,
        String apiKeyPrefix,
        String apiKeyHash,
        List<String> roles,
        List<String> toolScope,
        McpClientStatus state,
        boolean enabled,
        LocalDateTime expiresAt,
        LocalDateTime lastUsedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public McpClient {
        if (publicationId <= 0) {
            throw new McpDomainException("MCP_REQUIRED_FIELD", "publicationId is required");
        }
        name = McpDomainText.requireText(name, "client name");
        projectCode = McpDomainText.requireText(projectCode, "projectCode");
        environment = McpDomainText.requireText(environment, "environment");
        tenantId = McpDomainText.requireText(tenantId, "tenantId");
        apiKeyPrefix = McpDomainText.requireText(apiKeyPrefix, "apiKeyPrefix");
        apiKeyHash = McpDomainText.requireText(apiKeyHash, "apiKeyHash");
        roles = roles == null ? List.of() : List.copyOf(roles);
        toolScope = toolScope == null ? List.of() : List.copyOf(toolScope);
        if (state == null) {
            throw new McpDomainException("MCP_REQUIRED_FIELD", "state is required");
        }
    }

    public boolean expired(LocalDateTime now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public boolean callable(LocalDateTime now) {
        return state == McpClientStatus.ACTIVE && enabled && !expired(now);
    }

    public McpClient rotate(LocalDateTime now) {
        requireActive("rotate");
        return with(McpClientStatus.ROTATED, now);
    }

    public McpClient revoke(LocalDateTime now) {
        requireActive("revoke");
        return with(McpClientStatus.REVOKED, now);
    }

    public McpClient expire(LocalDateTime now) {
        requireActive("expire");
        return with(McpClientStatus.EXPIRED, now);
    }

    public McpClient updateSettings(List<String> nextRoles,
                                    List<String> nextToolScope,
                                    boolean nextEnabled,
                                    LocalDateTime nextExpiresAt,
                                    LocalDateTime now) {
        requireActive("update");
        return new McpClient(id, publicationId, name, projectId, projectCode, environment, tenantId,
                apiKeyPrefix, apiKeyHash, nextRoles, nextToolScope, state, nextEnabled,
                nextExpiresAt, lastUsedAt, createdAt, now);
    }

    private void requireActive(String action) {
        if (state != McpClientStatus.ACTIVE) {
            throw new McpDomainException("MCP_CLIENT_TERMINAL",
                    "a " + state + " client cannot be "
                            + (action.equals("expire") ? "expired"
                            : action.equals("update") ? "updated" : action + "d"));
        }
    }

    private McpClient with(McpClientStatus next, LocalDateTime now) {
        return new McpClient(id, publicationId, name, projectId, projectCode, environment, tenantId,
                apiKeyPrefix, apiKeyHash, roles, toolScope, next, enabled, expiresAt, lastUsedAt, createdAt, now);
    }
}

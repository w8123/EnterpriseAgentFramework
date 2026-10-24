package com.enterprise.ai.control.mcp.domain.employee;

import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.McpDomainText;

import java.time.LocalDateTime;
import java.util.List;

/**
 * An employee's personal connection from their own AI tool (Claude Code, Codex…)
 * to the business systems they ticked. The owner is the synced ReachAI business
 * user; the plaintext key is shown once and only its hash is stored.
 */
public record AiToolConnection(
        Long id,
        String tenantId,
        Long businessUserId,
        String appId,
        String externalUserId,
        String userName,
        AiToolKind toolKind,
        String keyPrefix,
        AiToolConnectionStatus state,
        LocalDateTime expiresAt,
        LocalDateTime lastUsedAt,
        LocalDateTime createdAt,
        List<ConnectedSystem> systems) {

    public AiToolConnection {
        tenantId = McpDomainText.requireText(tenantId, "tenantId");
        if (businessUserId == null) {
            throw new McpDomainException("MCP_REQUIRED_FIELD", "businessUserId is required");
        }
        appId = McpDomainText.requireText(appId, "appId");
        externalUserId = McpDomainText.requireText(externalUserId, "externalUserId");
        userName = McpDomainText.optionalText(userName);
        if (toolKind == null) {
            toolKind = AiToolKind.OTHER;
        }
        keyPrefix = McpDomainText.requireText(keyPrefix, "keyPrefix");
        if (state == null) {
            throw new McpDomainException("MCP_REQUIRED_FIELD", "state is required");
        }
        systems = systems == null ? List.of() : List.copyOf(systems);
        if (systems.isEmpty()) {
            throw new McpDomainException("AI_TOOL_CONNECTION_NO_SYSTEM",
                    "a connection needs at least one business system");
        }
    }

    public boolean expired(LocalDateTime now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public boolean usable(LocalDateTime now) {
        return state == AiToolConnectionStatus.ACTIVE && !expired(now);
    }

    public boolean ownedBy(String tenant, Long businessUser) {
        return tenantId.equals(tenant) && businessUserId.equals(businessUser);
    }

    /** One ticked business system: its project and the publication employees may use there. */
    public record ConnectedSystem(String projectCode, long publicationId) {
        public ConnectedSystem {
            projectCode = McpDomainText.requireText(projectCode, "projectCode");
        }
    }
}

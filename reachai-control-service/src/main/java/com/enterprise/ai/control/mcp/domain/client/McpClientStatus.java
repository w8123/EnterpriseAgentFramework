package com.enterprise.ai.control.mcp.domain.client;

import com.enterprise.ai.control.mcp.domain.McpDomainText;

public enum McpClientStatus {
    ACTIVE,
    ROTATED,
    REVOKED,
    EXPIRED;

    public boolean terminal() {
        return this != ACTIVE;
    }

    public static McpClientStatus parse(String value) {
        return McpDomainText.parseEnum(McpClientStatus.class, value, "clientStatus");
    }
}

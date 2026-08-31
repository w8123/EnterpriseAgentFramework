package com.enterprise.ai.control.mcp.domain.client;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class McpClientTest {

    @Test
    void credentialIsNoLongerCallableAtItsExactExpiryInstant() {
        LocalDateTime expiry = LocalDateTime.of(2026, 8, 28, 17, 0);
        McpClient client = new McpClient(
                1L, 2L, "client", 3L, "orders", "test", "tenant-a",
                "mcp_123456789012", "hash", List.of("ops"), List.of(),
                McpClientStatus.ACTIVE, true, expiry, null, null, null);

        assertFalse(client.callable(expiry));
    }
}

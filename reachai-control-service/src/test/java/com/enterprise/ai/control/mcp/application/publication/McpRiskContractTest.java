package com.enterprise.ai.control.mcp.application.publication;

import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItem;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpRiskContractTest {
    @Test void publicationOverrideCannotDowngradeCapabilityOrIrreversibleSource() {
        var write = new McpToolProjection("create", "d", "{}", McpPublicationItemKind.CAPABILITY,
                "erp:create", null, "WRITE", "a".repeat(64), "WRITE", 1);
        var readOverride = new McpPublicationItem(1L, 2L, McpPublicationItemKind.CAPABILITY,
                "erp:create", null, null, "READ", true, null, null);
        assertEquals("MCP_WORKFLOW_RISK_DOWNGRADE", assertThrows(McpDomainException.class,
                () -> McpPrecheckService.applyOverrides(write, readOverride)).code());
        var irreversible = new McpToolProjection("delete", "d", "{}", McpPublicationItemKind.CAPABILITY,
                "erp:delete", null, "IRREVERSIBLE", "b".repeat(64), "IRREVERSIBLE", 1);
        var writeOverride = new McpPublicationItem(2L, 2L, McpPublicationItemKind.CAPABILITY,
                "erp:delete", null, null, "WRITE", true, null, null);
        assertEquals("MCP_WORKFLOW_RISK_DOWNGRADE", assertThrows(McpDomainException.class,
                () -> McpPrecheckService.applyOverrides(irreversible, writeOverride)).code());
    }

    @Test void immutableRevisionJsonPreservesMarkerAndOldJsonRemainsUntrusted() throws Exception {
        var mapper = new ObjectMapper();
        var tool = new McpToolProjection("read", "d", "{}", McpPublicationItemKind.CAPABILITY,
                "erp:read", null, "READ", "a".repeat(64), "READ", 1);
        assertTrue(mapper.readValue(mapper.writeValueAsString(tool), McpToolProjection.class).hasTrustedRiskContract());
        var legacy = mapper.readValue("{\"name\":\"read\",\"inputSchemaJson\":\"{}\",\"sourceKind\":\"CAPABILITY\",\"sourceRef\":\"erp:read\",\"riskLevel\":\"READ\"}", McpToolProjection.class);
        assertFalse(legacy.hasTrustedRiskContract());
    }
}

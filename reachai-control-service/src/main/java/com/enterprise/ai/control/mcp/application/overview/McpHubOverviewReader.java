package com.enterprise.ai.control.mcp.application.overview;

/**
 * Read model for the MCP Hub overview page. Metrics are computed over a
 * trailing window (default 7 days) from the append-only call log.
 */
public interface McpHubOverviewReader {

    McpHubOverviewView read(int days);

    record McpHubOverviewView(
            long publications,
            long publishedPublications,
            long activeClients,
            long expiringCredentials,
            long outboundCalls,
            double outboundSuccessRate,
            Long outboundP95Ms,
            long inboundCalls) {
    }
}

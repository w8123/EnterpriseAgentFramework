package com.enterprise.ai.control.mcp.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "reachai.mcp-hub")
public class McpHubProperties {

    private String serverName = "ReachAI MCP";

    private String serverVersion = "1.0.0";

    /** Master switch for the public MCP protocol surface. */
    private boolean enabled = true;

    /** Stateless legacy Streamable HTTP protocol supported during migration. */
    private String legacyProtocolVersion = "2025-11-25";

    /** Current simplified HTTP protocol supported by the same endpoint. */
    private String modernProtocolVersion = "2026-07-28";

    /** Browser origins allowed to call the public MCP endpoint. Non-browser clients normally omit Origin. */
    private List<String> allowedOrigins = new ArrayList<>();

    /** Hard cap on the raw JSON-RPC request body accepted by the protocol surface. */
    private int maxRequestBytes = 512 * 1024;

    /** Hard cap on the execution payload returned by the Runtime tool-execution endpoint. */
    private int maxExecutionResponseBytes = 8 * 1024 * 1024;

    /** Timeout for a single Control→Runtime tool execution call. */
    private Duration executionTimeout = Duration.ofSeconds(120);

    /** Timeout for establishing the Control→Runtime connection. */
    private Duration connectTimeout = Duration.ofSeconds(15);

    /** Raw JSON-RPC payload persistence is opt-in; authentication headers are never captured. */
    private boolean captureAuditPayload = false;

    /** Character cap applied after recursive secret redaction. */
    private int maxAuditPayloadChars = 32 * 1024;

    /** Raw payloads are cleared after this many days when capture is explicitly enabled. */
    private int auditPayloadRetentionDays = 7;

    public List<String> supportedProtocolVersions() {
        return List.of(legacyProtocolVersion, modernProtocolVersion);
    }
}

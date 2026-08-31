package com.enterprise.ai.control.mcp.api.protocol;

import com.enterprise.ai.control.mcp.domain.McpDomainException;

import java.util.Map;

import static java.util.Map.entry;

/**
 * Error taxonomy for the MCP JSON-RPC surface. Every failure maps to one
 * audit {@code error_category} and one JSON-RPC error code, so call logs and
 * caller-visible errors never diverge.
 */
enum McpProtocolError {

    PROTOCOL(400, -32600, Map.of(
            "MCP_REQUEST_INVALID", -32600,
            "MCP_METHOD_NOT_FOUND", -32601,
            "MCP_PARAMS_INVALID", -32602,
            "MCP_TOOL_NAME_REQUIRED", -32602,
            "MCP_PROTOCOL_HEADER_MISMATCH", -32020,
            "MCP_PROTOCOL_VERSION_UNSUPPORTED", -32021)),
    DISABLED(404, -32004, Map.of()),
    ORIGIN(403, -32005, Map.of()),
    TOO_LARGE(413, -32006, Map.of()),
    NOT_ACCEPTABLE(406, -32007, Map.of()),
    MEDIA_TYPE(415, -32008, Map.of()),
    AUTH(401, -32001, Map.of()),
    POLICY(200, -32002, Map.of()),
    RUNTIME(200, -32003, Map.of()),
    INTERNAL(500, -32603, Map.of());

    private static final Map<String, McpProtocolError> BY_CODE_PREFIX = Map.ofEntries(
            entry("MCP_CLIENT", AUTH),
            entry("MCP_PUBLICATION", POLICY),
            entry("MCP_TOOL_SCOPE", POLICY),
            entry("MCP_TOOL_ACL", POLICY),
            entry("MCP_TOOL_IRREVERSIBLE", POLICY),
            entry("MCP_TOOL_NOT_VISIBLE", POLICY),
            entry("MCP_TOOL_NOT_IN_REVISION", POLICY),
            entry("MCP_REQUEST", PROTOCOL),
            entry("MCP_METHOD", PROTOCOL),
            entry("MCP_PARAMS", PROTOCOL),
            entry("MCP_ACCEPT", NOT_ACCEPTABLE),
            entry("MCP_ORIGIN", ORIGIN),
            entry("MCP_REQUEST_TOO_LARGE", TOO_LARGE),
            entry("MCP_CONTENT_TYPE", MEDIA_TYPE),
            entry("MCP_PROTOCOL_DISABLED", DISABLED),
            entry("MCP_RUNTIME", RUNTIME),
            entry("MCP_CAPABILITY", RUNTIME),
            entry("MCP_WORKFLOW", RUNTIME),
            entry("MCP_TOOL_EXECUTION", RUNTIME));

    private final int httpStatus;
    private final int defaultJsonRpcCode;
    private final Map<String, Integer> codeOverrides;

    McpProtocolError(int httpStatus, int defaultJsonRpcCode, Map<String, Integer> codeOverrides) {
        this.httpStatus = httpStatus;
        this.defaultJsonRpcCode = defaultJsonRpcCode;
        this.codeOverrides = codeOverrides;
    }

    static McpProtocolError of(McpDomainException exception) {
        String code = exception.code() == null ? "" : exception.code();
        McpProtocolError resolved = null;
        int resolvedPrefixLength = -1;
        for (Map.Entry<String, McpProtocolError> entry : BY_CODE_PREFIX.entrySet()) {
            if (code.startsWith(entry.getKey()) && entry.getKey().length() > resolvedPrefixLength) {
                resolved = entry.getValue();
                resolvedPrefixLength = entry.getKey().length();
            }
        }
        return resolved == null ? PROTOCOL : resolved;
    }

    int httpStatus() {
        return httpStatus;
    }

    int jsonRpcCode(String domainCode) {
        return codeOverrides.getOrDefault(domainCode, defaultJsonRpcCode);
    }

    String safeMessage() {
        return switch (this) {
            case AUTH -> "MCP authentication failed";
            case POLICY -> "MCP policy denied the request";
            case RUNTIME -> "MCP tool execution failed";
            case DISABLED -> "MCP endpoint is not available";
            case ORIGIN -> "MCP request origin is not allowed";
            case TOO_LARGE -> "MCP request body is too large";
            case NOT_ACCEPTABLE -> "MCP response media type is not acceptable";
            case MEDIA_TYPE -> "MCP request media type is not supported";
            case INTERNAL -> "Internal MCP server error";
            case PROTOCOL -> "Invalid MCP protocol request";
        };
    }
}

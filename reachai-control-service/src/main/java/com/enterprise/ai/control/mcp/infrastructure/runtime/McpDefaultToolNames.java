package com.enterprise.ai.control.mcp.infrastructure.runtime;

/**
 * Derives an MCP-conformant default tool name from a source identifier
 * (capability name / qualified name, workflow keySlug / name). Characters the
 * MCP tool-name grammar rejects (for example ':' or '.' in qualified names)
 * are replaced with '_'; the item alias remains the explicit override.
 */
final class McpDefaultToolNames {

    private McpDefaultToolNames() {
    }

    static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        if (cleaned.isEmpty() || !Character.isLetter(cleaned.charAt(0))) {
            // Keep the projection constructible; a leading non-letter is prefixed so
            // callers see a stable, rule-conformant default instead of a failure.
            cleaned = "t" + cleaned;
        }
        return cleaned.length() > 128 ? cleaned.substring(0, 128) : cleaned;
    }
}

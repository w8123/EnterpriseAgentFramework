package com.enterprise.ai.control.mcp.application.employee;

import com.enterprise.ai.control.mcp.domain.employee.AiToolKind;

/**
 * The one line (or one snippet) an employee pastes into their AI tool. The
 * employee never needs to know this is MCP; the page only says "copy this".
 */
public final class AiToolSetupInstructions {

    public static final String EMPLOYEE_ENDPOINT_PATH = "/mcp/employee";
    static final String SERVER_NAME = "reachai";

    private AiToolSetupInstructions() {
    }

    public static Setup forTool(AiToolKind kind, String baseUrl, String secret) {
        String url = trimTrailingSlash(baseUrl) + EMPLOYEE_ENDPOINT_PATH;
        return switch (kind) {
            case CLAUDE_CODE -> new Setup(url, "COMMAND",
                    "claude mcp add --transport http " + SERVER_NAME + " " + url
                            + " --header \"Authorization: Bearer " + secret + "\"",
                    "在终端运行这一行，然后重新打开 Claude Code。");
            case CODEX -> new Setup(url, "CONFIG",
                    "[mcp_servers." + SERVER_NAME + "]\n"
                            + "url = \"" + url + "\"\n"
                            + "http_headers = { \"Authorization\" = \"Bearer " + secret + "\" }\n",
                    "粘贴到 ~/.codex/config.toml 末尾，然后重新打开 Codex。");
            case OTHER -> new Setup(url, "CONFIG",
                    "{\n  \"mcpServers\": {\n    \"" + SERVER_NAME + "\": {\n"
                            + "      \"type\": \"http\",\n"
                            + "      \"url\": \"" + url + "\",\n"
                            + "      \"headers\": { \"Authorization\": \"Bearer " + secret + "\" }\n"
                            + "    }\n  }\n}\n",
                    "粘贴到 AI 工具的 MCP 配置里，然后重新打开该工具。");
        };
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** {@code kind} is COMMAND (run in a terminal) or CONFIG (paste into a config file). */
    public record Setup(String endpointUrl, String kind, String content, String hint) {
    }
}

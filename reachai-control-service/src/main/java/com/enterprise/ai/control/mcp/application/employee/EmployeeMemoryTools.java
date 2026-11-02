package com.enterprise.ai.control.mcp.application.employee;

import com.enterprise.ai.control.mcp.application.employee.McpEmployeeProtocolService.EmployeeSession;
import com.enterprise.ai.control.mcp.application.employee.McpEmployeeProtocolService.SystemSession;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnection;
import com.enterprise.ai.control.mcp.domain.employee.AiToolKind;
import com.enterprise.ai.control.mcp.application.protocol.McpProtocolApplicationService.McpToolCallResult;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryService;
import com.enterprise.ai.control.memory.PersonalMemoryService.QueryCommand;
import com.enterprise.ai.control.memory.PersonalMemoryService.QueryHit;
import com.enterprise.ai.control.memory.PersonalMemoryService.RememberCommand;
import com.enterprise.ai.control.memory.PersonalMemoryService.RememberResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The employee's own memories inside their AI tool: search, save and forget, on the same memories the
 * 「我的记忆」 page shows. The owner is the connection's employee (tenant + external user), the same one
 * embedded chat and the employee page use. The AI tool decides what is worth keeping; what it saves takes
 * effect at once and the employee can edit or delete it on the page.
 */
@Component
public class EmployeeMemoryTools {

    public static final String SEARCH = "reachai_memory_search";
    public static final String SAVE = "reachai_memory_save";
    public static final String FORGET = "reachai_memory_forget";
    static final Set<String> NAMES = Set.of(SEARCH, SAVE, FORGET);
    private static final int INSTRUCTION_MAX_CHARS = 1_500;

    private final PersonalMemoryService memoryService;

    public EmployeeMemoryTools(PersonalMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public static boolean handles(String toolName) {
        return toolName != null && NAMES.contains(toolName.trim());
    }

    public List<Map<String, Object>> tools(EmployeeSession session) {
        List<String> projects = session.systems().stream().map(system -> system.access().projectCode()).toList();
        String projectHint = "可选：项目编码（" + String.join(" / ", projects) + "）。";

        Map<String, Object> searchSchema = schema(Map.of(
                "query", property("string", "要找什么，例如“仓库”“报表格式”；不填则列出最常用的记忆"),
                "projectCode", property("string", projectHint + "填了会同时查这个项目的记忆")), List.of());
        Map<String, Object> saveSchema = schema(Map.of(
                "content", property("string", "要记住的内容，一句话写清楚"),
                "title", property("string", "可选：简短标题"),
                "kind", Map.of("type", "string", "enum", List.of("PREFERENCE", "NOTE"),
                        "description", "PREFERENCE：偏好和习惯，以后每次都会用到；NOTE：备注和资料，相关时才用到。默认 NOTE"),
                "projectCode", property("string", projectHint + "只跟这个项目有关时填写；不填就记在个人记忆里")),
                List.of("content"));
        Map<String, Object> forgetSchema = schema(Map.of(
                "memoryId", property("integer", "要删除的记忆编号（来自 " + SEARCH + " 的结果）")), List.of("memoryId"));

        return List.of(
                tool(SEARCH, "查询这位员工的个人记忆（工作偏好、习惯、备注）。开始做事前先查一下，按他的习惯来。", searchSchema, "READ"),
                tool(SAVE, "把员工明确告诉你、以后还会用到的偏好或备注记下来（例如“查库存默认看 1 号仓”）。"
                        + "只记员工本人的工作习惯，不要记密码、密钥或一次性的数据。记下后员工可以在「我的记忆」里编辑或删除。",
                        saveSchema, "WRITE"),
                tool(FORGET, "删除一条员工不再需要的记忆。", forgetSchema, "WRITE"));
    }

    public McpToolCallResult call(EmployeeSession session, String toolName, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        try {
            return switch (toolName.trim()) {
                case SEARCH -> search(session, args);
                case SAVE -> save(session, args);
                case FORGET -> forget(session, args);
                default -> refusal("MCP_MEMORY_TOOL_UNKNOWN", "未知的记忆工具");
            };
        } catch (ResponseStatusException rejected) {
            String reason = StringUtils.hasText(rejected.getReason()) ? rejected.getReason() : "记忆操作失败";
            if (reason.contains("credentials and secrets")) reason = "记忆里不能保存密码、Token 或密钥";
            if (rejected.getStatusCode().value() == 404) reason = "没有找到这条记忆";
            return refusal("MCP_MEMORY_REJECTED", reason);
        }
    }

    /**
     * A short note for the MCP handshake: the employee's preferences in the personal layer and in the
     * connected systems, so the AI tool works their way from the first message.
     */
    public String instructions(EmployeeSession session) {
        PersonalMemoryPrincipal owner = owner(session.connection());
        List<String> lines = new ArrayList<>();
        int used = 0;
        List<String> scopes = new ArrayList<>();
        scopes.add(null);
        session.systems().forEach(system -> scopes.add(system.access().projectCode()));
        for (String projectCode : scopes) {
            for (QueryHit hit : memoryService.query(owner, new QueryCommand("", List.of("PREFERENCE", "RULE"),
                    10, INSTRUCTION_MAX_CHARS, null, null, null, projectCode)).hits()) {
                if (projectCode != null && hit.projectCode() == null) continue;
                String line = "- " + (hit.projectCode() == null ? "" : "【" + label(session, hit.projectCode()) + "】")
                        + hit.content();
                if (used + line.length() > INSTRUCTION_MAX_CHARS) break;
                if (!lines.contains(line)) {
                    lines.add(line);
                    used += line.length();
                }
            }
        }
        StringBuilder text = new StringBuilder("你正在以这位员工的身份操作业务系统。")
                .append("需要了解他的习惯时用 ").append(SEARCH).append(" 查询；他明确说以后要这样做时用 ")
                .append(SAVE).append(" 记下来。");
        if (!lines.isEmpty()) {
            text.append("\n这位员工的偏好：\n").append(String.join("\n", lines));
        }
        return text.toString();
    }

    private McpToolCallResult search(EmployeeSession session, Map<String, Object> args) {
        String projectCode = connectedProject(session, text(args.get("projectCode")));
        if (projectCode == null && StringUtils.hasText(text(args.get("projectCode")))) {
            return refusal("MCP_MEMORY_PROJECT", "这个连接没有接入项目 " + text(args.get("projectCode")));
        }
        List<Map<String, Object>> memories = memoryService.query(owner(session.connection()),
                        new QueryCommand(firstText(text(args.get("query")), ""), null, 20, 8_000,
                                null, null, null, projectCode))
                .hits().stream().map(hit -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("memoryId", hit.id());
                    view.put("where", hit.projectCode() == null ? "个人记忆" : label(session, hit.projectCode()));
                    view.put("kind", "PREFERENCE".equals(hit.type()) || "RULE".equals(hit.type()) ? "PREFERENCE" : "NOTE");
                    if (StringUtils.hasText(hit.title())) view.put("title", hit.title());
                    view.put("content", hit.content());
                    return view;
                }).toList();
        return new McpToolCallResult(true, Map.of("memories", memories), null, null, null);
    }

    private McpToolCallResult save(EmployeeSession session, Map<String, Object> args) {
        String requestedProject = text(args.get("projectCode"));
        String projectCode = connectedProject(session, requestedProject);
        if (projectCode == null && StringUtils.hasText(requestedProject)) {
            return refusal("MCP_MEMORY_PROJECT", "这个连接没有接入项目 " + requestedProject);
        }
        String content = text(args.get("content"));
        if (!StringUtils.hasText(content)) {
            return refusal("MCP_MEMORY_CONTENT", "请写明要记住的内容");
        }
        String kind = firstText(text(args.get("kind")), "NOTE").toUpperCase(Locale.ROOT);
        AiToolConnection connection = session.connection();
        RememberResult result = memoryService.remember(owner(connection), new RememberCommand(
                "PREFERENCE".equals(kind) ? "PREFERENCE" : "NOTE", null, text(args.get("title")), content,
                null, null, null, PersonalMemoryService.SOURCE_AI_TOOL, null, null, toolLabel(connection.toolKind()),
                projectCode, null));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("memoryId", result.memory().id());
        output.put("where", result.memory().layerName() + (result.memory().groupName() == null ? ""
                : " · " + result.memory().groupName()));
        output.put("message", result.created() ? "已记住，员工可以在「我的记忆」里查看和修改。"
                : "已经记过相同的内容，没有重复保存。");
        return new McpToolCallResult(true, output, null, null, null);
    }

    private McpToolCallResult forget(EmployeeSession session, Map<String, Object> args) {
        long id;
        try {
            id = Long.parseLong(firstText(text(args.get("memoryId")), ""));
        } catch (NumberFormatException invalid) {
            return refusal("MCP_MEMORY_ID", "memoryId 应为记忆编号（数字）");
        }
        memoryService.forget(owner(session.connection()), id);
        return new McpToolCallResult(true, Map.of("memoryId", id, "message", "已删除"), null, null, null);
    }

    /** Same owner as embedded chat and the employee page: the employee's tenant and external user. */
    static PersonalMemoryPrincipal owner(AiToolConnection connection) {
        return PersonalMemoryPrincipal.business(connection.tenantId(), connection.externalUserId(), connection.appId());
    }

    private static String connectedProject(EmployeeSession session, String requested) {
        if (!StringUtils.hasText(requested)) return null;
        return session.systems().stream().map(system -> system.access().projectCode())
                .filter(code -> code.equalsIgnoreCase(requested.trim())).findFirst().orElse(null);
    }

    private static String label(EmployeeSession session, String projectCode) {
        return session.systems().stream().filter(system -> system.access().projectCode().equals(projectCode))
                .map(SystemSession::access).map(access -> access.label()).findFirst().orElse(projectCode);
    }

    private static String toolLabel(AiToolKind kind) {
        return switch (kind) {
            case CLAUDE_CODE -> "Claude Code";
            case CODEX -> "Codex";
            default -> "AI 工具";
        };
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> schema, String risk) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", name);
        view.put("description", description);
        view.put("inputSchema", schema);
        view.put("annotations", Map.of("riskLevel", risk, "sourceKind", "BUILT_IN"));
        return view;
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> property(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    /** A refusal whose message is written for the employee; the protocol layer passes it on (MCP_MEMORY_*). */
    private static McpToolCallResult refusal(String code, String message) {
        return new McpToolCallResult(false, Map.of("message", message), null, null, code);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static String firstText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}

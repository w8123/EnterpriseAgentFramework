package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformEmbedPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformModelCatalogPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformProjectPagesPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformPublicationPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformSystemStatusPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Read-only help for people connecting a business system: per-framework setup guides filled with this project's real
 * values ({@code reachai_integration_guide}) and a one-call check-up of what ReachAI sees for the project
 * ({@code reachai_doctor}). Every check goes through the same owner ports and permissions as the console pages, and a
 * failing check never stops the others.
 */
@Service
@RequiredArgsConstructor
public class McpPlatformIntegrationService {

    /** Guide topics and the files under {@code ai-assist/guides/}. */
    public static final Map<String, String> GUIDE_TOPICS = Map.of(
            "embed-vue", "Vue 3 页面接入嵌入对话",
            "embed-react", "React 页面接入嵌入对话",
            "embed-angular", "Angular 页面接入嵌入对话",
            "embed-script", "script 标签 / Web Component 接入嵌入对话",
            "sdk-java", "Java（Spring Boot 2.7 / 3）后端接入",
            "sdk-csharp", "C#（ASP.NET Core）后端接入",
            "sdk-python", "Python（FastAPI）后端接入",
            "sdk-node", "Node.js（Express）后端接入",
            "sdk-go", "Go（net/http）后端接入");

    private static final Set<String> ONLINE = Set.of("UP", "ONLINE", "HEALTHY", "RUNNING", "ACTIVE");

    private final McpPlatformProjectPagesPort projectPages;
    private final McpPlatformEmbedPort embed;
    private final McpPlatformModelCatalogPort models;
    private final McpPlatformPublicationPort publications;
    private final McpPlatformSystemStatusPort systemStatus;
    private final ObjectMapper json;

    public ResponseEntity<?> guide(String topic, String projectCode) {
        String key = topic == null ? "" : topic.trim().toLowerCase(Locale.ROOT);
        if (!GUIDE_TOPICS.containsKey(key)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "未知的教程，可选：" + String.join("、", GUIDE_TOPICS.keySet().stream().sorted().toList()));
            body.put("topics", GUIDE_TOPICS);
            return ResponseEntity.badRequest().body(body);
        }
        String template = readGuide(key);
        String baseUrl = publicBaseUrl();
        String text = template
                .replace("{{reachaiUrl}}", baseUrl == null ? "（你连接 MCP 用的 ReachAI 地址）" : baseUrl)
                .replace("{{projectCode}}", projectCode == null || projectCode.isBlank() ? "（你的项目编码）" : projectCode.trim());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", key);
        body.put("title", GUIDE_TOPICS.get(key));
        body.put("markdown", text);
        return ResponseEntity.ok(body);
    }

    public ResponseEntity<?> doctor(ConnectionIdentity identity, String projectCode) {
        var actor = identity.session();
        List<Map<String, Object>> checks = new ArrayList<>();

        JsonNode access = check(checks, "PROJECT", "项目和权限",
                () -> projectPages.read(actor, projectCode, McpPlatformProjectPagesPort.Read.ACCESS_CENTER, Map.of()),
                node -> pass("能读取项目「" + projectCode + "」的接入总览"),
                "确认项目编码正确，并且你的账号有这个项目的读取权限");

        check(checks, "SDK_INSTANCES", "业务系统 SDK 实例",
                () -> projectPages.read(actor, projectCode, McpPlatformProjectPagesPort.Read.INSTANCES, Map.of()),
                node -> {
                    List<JsonNode> rows = rows(node);
                    long online = rows.stream().filter(McpPlatformIntegrationService::online).count();
                    if (rows.isEmpty()) return fail("还没有业务系统实例注册上来", "启动业务系统，检查 SDK 的 RegistryUrl / ProjectCode 和注册凭据（环境变量），看业务系统日志里的注册结果");
                    if (online == 0) return warn("有 " + rows.size() + " 个实例，但都不在线", "检查业务系统是否在运行，心跳地址能否从业务系统访问到 ReachAI");
                    return pass(online + " / " + rows.size() + " 个实例在线");
                }, "检查 ReachAI 注册中心服务是否正常");

        if (access != null) {
            Map<String, Object> pages = new LinkedHashMap<>();
            JsonNode summary = access.path("summary");
            pages.put("key", "PAGES");
            pages.put("label", "业务页面接入");
            if (!access.path("runtimeAvailable").asBoolean(true)) {
                pages.putAll(warn("运行服务暂不可用：" + access.path("runtimeMessage").asText(""), "检查 Runtime 服务状态"));
            } else if (summary.isMissingNode() || summary.path("discoveredCount").asInt(0) == 0) {
                pages.putAll(warn("还没有登记业务页面", "在业务页面工作台扫描或添加页面，或让 AI 用「让业务页面支持 AI 操作」提示词处理"));
            } else {
                pages.putAll(pass("已发现 " + summary.path("discoveredCount").asInt() + " 个页面，完成 "
                        + summary.path("completedCount").asInt() + " 个，进行中 " + summary.path("activeCount").asInt() + " 个"));
            }
            checks.add(pages);
        }

        check(checks, "EMBED_POLICY", "嵌入对话接入策略",
                () -> embed.read(actor, McpPlatformEmbedPort.Read.CREDENTIAL_POLICIES, Map.of("projectCode", projectCode, "limit", 100)),
                node -> {
                    List<JsonNode> rows = rows(node);
                    List<String> origins = new ArrayList<>();
                    long active = 0;
                    for (JsonNode row : rows) {
                        if (ONLINE.contains(row.path("status").asText("").toUpperCase(Locale.ROOT)) || row.path("enabled").asBoolean(false)) active++;
                        for (JsonNode origin : row.path("allowedOrigins")) if (origins.size() < 10) origins.add(origin.asText());
                    }
                    if (rows.isEmpty()) return warn("没有嵌入接入策略，业务页面还打不开对话", "在项目详情里配置嵌入接入，填写业务系统前端的来源域名");
                    Map<String, Object> result = active == 0
                            ? warn("有 " + rows.size() + " 条策略，但没有启用的", "启用一条嵌入接入策略")
                            : pass(active + " 条启用中");
                    if (!origins.isEmpty()) result.put("allowedOrigins", origins);
                    return result;
                }, "确认你有查看嵌入接入的权限");

        check(checks, "MODEL", "可用的对话模型",
                () -> models.list(actor, projectCode, "LLM", null, false, 0, 50),
                node -> {
                    List<JsonNode> rows = rows(node);
                    long active = rows.stream().filter(row -> {
                        String status = row.path("status").asText("ACTIVE").toUpperCase(Locale.ROOT);
                        return ONLINE.contains(status) || "ENABLED".equals(status);
                    }).count();
                    if (active == 0) return fail("没有可用的对话模型，智能体无法回答", "在模型中心接入一个对话模型（测试用 DeepSeek 即可）");
                    return pass(active + " 个对话模型可用");
                }, "确认你有查看模型的权限");

        check(checks, "EMPLOYEE_MCP", "开放给员工 AI 工具的能力",
                () -> publications.read(actor, McpPlatformPublicationPort.Read.LIST, null, Map.of("limit", 100, "offset", 0)),
                node -> {
                    List<JsonNode> rows = rows(node);
                    if (rows.isEmpty()) return warn("还没有 MCP 发布，员工的 Codex / Claude Code 还不能办这个系统的业务", "用「把能力开放给员工的 AI 工具」提示词建一个发布");
                    return pass(rows.size() + " 个 MCP 发布");
                }, "需要 MCP 开放的查看权限，没有也不影响业务系统本身的接入");

        check(checks, "SERVICES", "ReachAI 各服务",
                () -> systemStatus.read(actor, McpPlatformSystemStatusPort.Read.SERVICES_HEALTH),
                node -> {
                    List<String> down = new ArrayList<>();
                    node.fields().forEachRemaining(entry -> {
                        JsonNode value = entry.getValue();
                        String status = value.isTextual() ? value.asText() : value.path("status").asText("");
                        if (!status.isEmpty() && !ONLINE.contains(status.toUpperCase(Locale.ROOT))) down.add(entry.getKey());
                    });
                    return down.isEmpty() ? pass("各服务正常") : fail("这些服务不正常：" + String.join("、", down), "请管理员检查服务器上对应服务的日志");
                }, "检查 ReachAI 服务状态");

        long failed = checks.stream().filter(item -> "FAIL".equals(item.get("status"))).count();
        long warned = checks.stream().filter(item -> "WARN".equals(item.get("status"))).count();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectCode", projectCode);
        body.put("summary", failed > 0 ? failed + " 项有问题，" + warned + " 项需要注意"
                : warned > 0 ? "没有阻断问题，" + warned + " 项需要注意" : "全部正常");
        body.put("checks", checks);
        body.put("localChecks", localChecks(projectCode));
        return ResponseEntity.ok(body);
    }

    /** What the coding agent should check inside the business repository; ReachAI cannot see those files. */
    private static List<Map<String, String>> localChecks(String projectCode) {
        return List.of(
                Map.of("key", "SDK_DEPENDENCY", "how", "在业务仓库里确认已引入 ReachAI SDK（Java: reachai-spring-boot2-starter；C#: ReachAI.AspNetCore；Python: reachai-fastapi；Node: @reachai/node-sdk；Go: reachai-go），版本与接入清单一致"),
                Map.of("key", "SDK_CONFIG", "how", "确认 SDK 配置里的项目编码是「" + projectCode + "」，平台地址是你连接 MCP 用的 ReachAI 地址，BaseUrl 是 ReachAI 能访问到的业务系统地址"),
                Map.of("key", "SECRETS", "how", "确认注册凭据、AppSecret 只来自环境变量或密钥管理器（只检查是否存在，不要打印值），没有写进代码、配置文件或前端"),
                Map.of("key", "EMBED_TOKEN", "how", "确认业务后端提供嵌入换票接口（Java 默认 /api/reachai/embed-token，其他语言 /reachai/embed/token），并处在业务登录保护之下"),
                Map.of("key", "EMBED_FRONTEND", "how", "确认前端已安装 @reachai/embed-chat 并在登录后的主框架里挂载一次，tokenProvider 调用上面的换票接口"),
                Map.of("key", "STATIC_DOCTOR", "how", "如已下载 reachai-onboarding Skill，可运行 node scripts/reachai-doctor.mjs --mode static --business-root <业务仓库> 做一次静态检查"));
    }

    private JsonNode check(List<Map<String, Object>> checks, String key, String label, Supplier<ResponseEntity<?>> call,
                           java.util.function.Function<JsonNode, Map<String, Object>> judge, String fixWhenUnavailable) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("label", label);
        JsonNode node = null;
        try {
            ResponseEntity<?> response = call.get();
            int status = response == null ? 502 : response.getStatusCode().value();
            if (status == 403) {
                item.putAll(warn("没有权限查看", fixWhenUnavailable));
            } else if (status == 404) {
                item.putAll(fail("找不到", fixWhenUnavailable));
            } else if (status < 200 || status >= 300) {
                item.putAll(fail("查询失败（HTTP " + status + "）", fixWhenUnavailable));
            } else {
                node = unwrap(json.valueToTree(response.getBody()));
                item.putAll(judge.apply(node));
            }
        } catch (RuntimeException failure) {
            String status = failure instanceof org.springframework.web.server.ResponseStatusException rse
                    ? String.valueOf(rse.getStatusCode().value()) : null;
            item.putAll("403".equals(status) ? warn("没有权限查看", fixWhenUnavailable)
                    : fail("查询失败" + (status == null ? "" : "（HTTP " + status + "）"), fixWhenUnavailable));
            node = null;
        }
        checks.add(item);
        return node;
    }

    /** Owner responses come either bare, wrapped in {@code data}, or paged under {@code records} / {@code items}. */
    private static JsonNode unwrap(JsonNode node) {
        if (node == null) return null;
        if (node.has("data") && !node.has("summary")) return node.get("data");
        return node;
    }

    private static List<JsonNode> rows(JsonNode node) {
        List<JsonNode> rows = new ArrayList<>();
        if (node == null) return rows;
        JsonNode list = node.isArray() ? node
                : node.has("records") ? node.get("records")
                : node.has("items") ? node.get("items")
                : node.has("content") ? node.get("content")
                : node.has("list") ? node.get("list")
                : null;
        if (list != null && list.isArray()) list.forEach(rows::add);
        return rows;
    }

    private static boolean online(JsonNode row) {
        if (row.path("online").asBoolean(false) || row.path("healthy").asBoolean(false)) return true;
        return ONLINE.contains(row.path("status").asText("").toUpperCase(Locale.ROOT));
    }

    private static Map<String, Object> pass(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PASS");
        result.put("message", message);
        return result;
    }

    private static Map<String, Object> warn(String message, String fix) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "WARN");
        result.put("message", message);
        result.put("fix", fix);
        return result;
    }

    private static Map<String, Object> fail(String message, String fix) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "FAIL");
        result.put("message", message);
        result.put("fix", fix);
        return result;
    }

    private static String readGuide(String key) {
        ClassPathResource resource = new ClassPathResource("ai-assist/guides/" + key + ".md");
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException missing) {
            throw new IllegalStateException("guide is missing: " + key, missing);
        }
    }

    /** The address people reach ReachAI on, honouring the reverse proxy's forwarded headers. */
    static String publicBaseUrl() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        HttpServletRequest request = attributes.getRequest();
        String scheme = first(request.getHeader("X-Forwarded-Proto"));
        if (scheme == null) scheme = request.getScheme();
        String host = first(request.getHeader("X-Forwarded-Host"));
        if (host == null) {
            host = request.getServerName();
            int port = request.getServerPort();
            boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
            if (port > 0 && !defaultPort) host = host + ":" + port;
        }
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))
                || host == null || !host.matches("[A-Za-z0-9.\\-]+(:[0-9]{1,5})?")) {
            return null;
        }
        return scheme + "://" + host;
    }

    private static String first(String header) {
        if (header == null || header.isBlank()) return null;
        String value = header.split(",")[0].trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() ? null : value;
    }
}

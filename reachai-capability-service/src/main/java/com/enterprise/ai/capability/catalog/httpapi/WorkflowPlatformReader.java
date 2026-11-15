package com.enterprise.ai.capability.catalog.httpapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns workflows that already run on Dify or n8n into HTTP API candidates:
 * <ul>
 *   <li>Dify: one app (its API key) → {@code POST /v1/workflows/run} or {@code POST /v1/chat-messages}, with the
 *   app's input form as the request body.</li>
 *   <li>n8n: Webhook trigger nodes, read through the n8n public API ({@code X-N8N-API-KEY}) or pasted as URLs.</li>
 * </ul>
 * Keys are only used for this read and are never put into the candidates.
 */
public final class WorkflowPlatformReader {
    static final int MAX_N8N_PAGES = 4;
    private static final Pattern WEBHOOK_LINE = Pattern.compile("^(?:(GET|POST|PUT|PATCH|DELETE|HEAD)\\s+)?(https?://\\S+)$", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper json;
    private final HttpApiDocumentFetcher fetcher;

    WorkflowPlatformReader(ObjectMapper json, HttpApiDocumentFetcher fetcher) {
        this.json = json;
        this.fetcher = fetcher;
    }

    /** platform: DIFY or N8N. {@code webhookUrls}: one per line, optionally prefixed by the HTTP method. */
    public record Request(String platform, String baseUrl, String apiKey, String webhookUrls) { }

    HttpApiDocumentImportService.Preview read(Request request, String contextPath) {
        if (request == null || !StringUtils.hasText(request.platform())) throw new IllegalArgumentException("请选择 Dify 或 n8n");
        String platform = request.platform().trim().toUpperCase(Locale.ROOT);
        return switch (platform) {
            case "DIFY" -> dify(request, contextPath);
            case "N8N" -> n8n(request, contextPath);
            default -> throw new IllegalArgumentException("只支持 Dify 和 n8n");
        };
    }

    /* ------------------------------------------------------------------ Dify */

    private HttpApiDocumentImportService.Preview dify(Request request, String contextPath) {
        if (!StringUtils.hasText(request.baseUrl())) throw new IllegalArgumentException("请填写 Dify 的 API 地址，在应用的「访问 API」页面可以看到，如 https://api.dify.ai/v1");
        if (!StringUtils.hasText(request.apiKey())) throw new IllegalArgumentException("请填写这个 Dify 应用的 API 密钥（app- 开头）");
        URI base = HttpApiDocumentFetcher.parse(request.baseUrl().trim());
        String basePath = trimSlash(base.getPath());
        if (!basePath.endsWith("/v1")) basePath = basePath + "/v1";
        String root = origin(base) + basePath;
        Map<String, String> headers = Map.of("Authorization", "Bearer " + request.apiKey().trim());
        JsonNode info = readJson(root + "/info", headers, "Dify");
        JsonNode parameters = readJson(root + "/parameters", headers, "Dify");
        String name = info.path("name").asText("");
        String description = info.path("description").asText("");
        String mode = info.path("mode").asText("");
        List<String> warnings = new ArrayList<>();
        List<String> skippedFields = new ArrayList<>();
        ObjectNode inputs = inputsSchema(parameters.path("user_input_form"), skippedFields);
        if (!skippedFields.isEmpty()) {
            warnings.add("这些输入是文件，暂时不能通过接口传：" + String.join("、", skippedFields) + "；调用时会留空");
        }
        String strip = stripPrefix(basePath, contextPath);
        List<HttpApiDocumentImportService.Candidate> candidates = new ArrayList<>();
        boolean known = !mode.isEmpty();
        if (!known || "workflow".equals(mode)) candidates.add(difyCandidate(true, strip, basePath, name, description, inputs, origin(base)));
        if (!known || List.of("advanced-chat", "chat", "agent-chat").contains(mode)) {
            candidates.add(difyCandidate(false, strip, basePath, name, description, inputs, origin(base)));
        }
        if ("completion".equals(mode)) {
            candidates.add(difyCompletion(strip, basePath, name, description, inputs, origin(base)));
        }
        if (!known) warnings.add("这个 Dify 版本没有返回应用类型，两种调用方式都列出来了：工作流应用选 workflows/run，聊天类应用选 chat-messages");
        warnings.add("保存后用 Bearer 令牌（这个 API 密钥）调用；勾选「保存 API 密钥」会自动建凭据并配置好");
        return new HttpApiDocumentImportService.Preview("DIFY", name.isEmpty() ? "Dify 应用" : name,
                modeLabel(mode), root, strip, 0, warnings, candidates);
    }

    private HttpApiDocumentImportService.Candidate difyCandidate(boolean workflow, String strip, String basePath, String name,
                                                                 String description, ObjectNode inputs, String host) {
        ObjectNode body = json.createObjectNode().put("type", "object");
        ObjectNode properties = body.putObject("properties");
        ArrayNode required = body.putArray("required");
        if (!workflow) {
            properties.putObject("query").put("type", "string").put("description", "用户的问题或指令");
            required.add("query");
        }
        properties.set("inputs", inputs);
        required.add("inputs");
        ObjectNode mode = properties.putObject("response_mode").put("type", "string").put("description", "固定填 blocking（等结果一次返回）");
        mode.putArray("enum").add("blocking");
        required.add("response_mode");
        properties.putObject("user").put("type", "string").put("description", "调用人标识，填员工账号或工号，Dify 用它区分用户");
        required.add("user");
        if (!workflow) {
            properties.putObject("conversation_id").put("type", "string").put("description", "接着上一次对话时填上次返回的 conversation_id，新对话留空");
        }
        String path = relative(basePath + (workflow ? "/workflows/run" : "/chat-messages"), strip);
        String summary = (name.isEmpty() ? "Dify 应用" : name) + (workflow ? "（运行工作流）" : "（发送对话消息）");
        String detail = (StringUtils.hasText(description) ? description + "\n\n" : "")
                + (workflow
                ? "调用 Dify 工作流，结果在返回的 data.outputs 里。inputs 按下面的字段填写。"
                : "向 Dify 聊天应用发一条消息，回答在返回的 answer 里；要连续对话就带上 conversation_id。");
        ManualHttpApiService.Request api = new ManualHttpApiService.Request("POST", path,
                workflow ? "WRITE" : "READ_ONLY", "dify", List.of(),
                new ManualHttpApiService.RequestBody("application/json", true, body), "object",
                new ManualHttpApiService.Authentication("BEARER", null), List.of("Dify"), summary, detail);
        List<String> warnings = workflow
                ? List.of("默认按「写入」处理（员工调用要审批）；如果这个工作流只查询或生成内容，可以改成「只读查询」")
                : List.of();
        return new HttpApiDocumentImportService.Candidate("POST " + path, api, host, false, warnings, null, false);
    }

    private HttpApiDocumentImportService.Candidate difyCompletion(String strip, String basePath, String name, String description,
                                                                  ObjectNode inputs, String host) {
        ObjectNode body = json.createObjectNode().put("type", "object");
        ObjectNode properties = body.putObject("properties");
        properties.set("inputs", inputs);
        ObjectNode mode = properties.putObject("response_mode").put("type", "string").put("description", "固定填 blocking");
        mode.putArray("enum").add("blocking");
        properties.putObject("user").put("type", "string").put("description", "调用人标识，填员工账号或工号");
        body.putArray("required").add("inputs").add("response_mode").add("user");
        String path = relative(basePath + "/completion-messages", strip);
        ManualHttpApiService.Request api = new ManualHttpApiService.Request("POST", path, "READ_ONLY", "dify", List.of(),
                new ManualHttpApiService.RequestBody("application/json", true, body), "object",
                new ManualHttpApiService.Authentication("BEARER", null), List.of("Dify"),
                (name.isEmpty() ? "Dify 应用" : name) + "（文本生成）",
                (StringUtils.hasText(description) ? description + "\n\n" : "") + "调用 Dify 文本生成应用，结果在返回的 answer 里。");
        return new HttpApiDocumentImportService.Candidate("POST " + path, api, host, false, List.of(), null, false);
    }

    /** Dify user_input_form → JSON schema of {@code inputs}. File inputs cannot travel as JSON and are left out. */
    ObjectNode inputsSchema(JsonNode form, List<String> skipped) {
        ObjectNode schema = json.createObjectNode().put("type", "object").put("description", "Dify 应用的输入变量");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = json.createArrayNode();
        for (JsonNode item : form) {
            var entries = item.fields();
            if (!entries.hasNext()) continue;
            var entry = entries.next();
            String kind = entry.getKey();
            JsonNode spec = entry.getValue();
            String variable = spec.path("variable").asText("");
            if (variable.isEmpty() || properties.size() >= 60) continue;
            String label = spec.path("label").asText(variable);
            if (kind.startsWith("file") || "external_data_tool".equals(kind)) {
                skipped.add(label);
                continue;
            }
            ObjectNode field = properties.putObject(variable);
            switch (kind) {
                case "number" -> field.put("type", "number");
                case "checkbox" -> field.put("type", "boolean");
                case "select" -> {
                    field.put("type", "string");
                    ArrayNode options = json.createArrayNode();
                    for (JsonNode option : spec.path("options")) if (option.isTextual() && !option.asText().isEmpty()) options.add(option.asText());
                    if (!options.isEmpty()) field.set("enum", options);
                }
                default -> {
                    field.put("type", "string");
                    int max = spec.path("max_length").asInt(0);
                    if (max > 0) field.put("maxLength", max);
                }
            }
            field.put("description", label);
            if (spec.path("required").asBoolean(false)) required.add(variable);
        }
        if (!required.isEmpty()) schema.set("required", required);
        return schema;
    }

    private static String modeLabel(String mode) {
        return switch (mode) {
            case "workflow" -> "工作流";
            case "advanced-chat" -> "Chatflow";
            case "chat" -> "聊天助手";
            case "agent-chat" -> "Agent";
            case "completion" -> "文本生成";
            default -> null;
        };
    }

    /* ------------------------------------------------------------------ n8n */

    private HttpApiDocumentImportService.Preview n8n(Request request, String contextPath) {
        boolean listing = StringUtils.hasText(request.baseUrl()) && StringUtils.hasText(request.apiKey());
        boolean pasted = StringUtils.hasText(request.webhookUrls());
        if (!listing && !pasted) throw new IllegalArgumentException("请填写 n8n 地址和 API Key，或者直接粘贴 Webhook 地址");
        List<HttpApiDocumentImportService.Candidate> candidates = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String fetchedFrom = null;
        int skipped = 0;
        String strip = stripPrefix("", contextPath);
        Map<String, HttpApiDocumentImportService.Candidate> byKey = new LinkedHashMap<>();
        if (listing) {
            URI base = HttpApiDocumentFetcher.parse(request.baseUrl().trim());
            String origin = origin(base);
            String basePath = trimSlash(base.getPath());
            if (basePath.endsWith("/api/v1")) basePath = basePath.substring(0, basePath.length() - "/api/v1".length());
            fetchedFrom = origin + basePath;
            Map<String, String> headers = Map.of("X-N8N-API-KEY", request.apiKey().trim(), "Accept", "application/json");
            String cursor = null;
            int workflows = 0;
            int inactive = 0;
            for (int page = 0; page < MAX_N8N_PAGES; page++) {
                String url = fetchedFrom + "/api/v1/workflows?limit=100" + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
                JsonNode body = readJson(url, headers, "n8n");
                for (JsonNode workflow : body.path("data")) {
                    workflows++;
                    boolean active = workflow.path("active").asBoolean(false);
                    int before = byKey.size();
                    for (JsonNode node : workflow.path("nodes")) {
                        if (!"n8n-nodes-base.webhook".equals(node.path("type").asText()) || node.path("disabled").asBoolean(false)) continue;
                        HttpApiDocumentImportService.Candidate candidate = webhookCandidate(workflow, node, basePath, strip, origin, active);
                        if (candidate == null) skipped++;
                        else byKey.putIfAbsent(candidate.key(), candidate);
                    }
                    if (!active && byKey.size() > before) inactive++;
                }
                cursor = body.path("nextCursor").isTextual() ? body.path("nextCursor").asText() : null;
                if (cursor == null || cursor.isEmpty()) break;
                if (page == MAX_N8N_PAGES - 1) warnings.add("工作流超过 " + (MAX_N8N_PAGES * 100) + " 个，只读了前 " + (MAX_N8N_PAGES * 100) + " 个");
            }
            if (byKey.isEmpty()) warnings.add("读了 " + workflows + " 个工作流，没有找到 Webhook 触发的；只有用 Webhook 节点开头的工作流能被调用");
            if (inactive > 0) warnings.add(inactive + " 个工作流还没激活，激活前它的生产 Webhook 地址调不通");
        }
        if (pasted) {
            String firstHost = byKey.values().stream().map(HttpApiDocumentImportService.Candidate::host).findFirst().orElse(null);
            for (String line : request.webhookUrls().split("\\r?\\n")) {
                String text = line.trim();
                if (text.isEmpty()) continue;
                Matcher matcher = WEBHOOK_LINE.matcher(text);
                if (!matcher.matches()) {
                    warnings.add("看不懂这一行，已跳过：" + (text.length() > 80 ? text.substring(0, 80) + "…" : text));
                    skipped++;
                    continue;
                }
                URI uri = HttpApiDocumentFetcher.parse(matcher.group(2));
                String method = matcher.group(1) == null ? "POST" : matcher.group(1).toUpperCase(Locale.ROOT);
                String host = origin(uri);
                if (firstHost == null) firstHost = host;
                String path = relative(trimSlash(uri.getPath()), strip);
                if (!StringUtils.hasText(path) || "/".equals(path)) { skipped++; continue; }
                List<String> notes = new ArrayList<>();
                if (!host.equals(firstHost)) notes.add("和其他地址不在同一个服务器上，一个所属系统只能配一个服务地址，建议分开导入");
                if (path.contains("/webhook-test/")) notes.add("这是测试地址（webhook-test），只在 n8n 编辑器里点「监听」时可用，正式使用请换成 /webhook/ 地址");
                ManualHttpApiService.Request api = webhookRequest(method, path, "n8n Webhook " + path, "n8n 工作流的 Webhook 触发地址。", "NONE");
                byKey.putIfAbsent(method + " " + path, new HttpApiDocumentImportService.Candidate(method + " " + path, api, host, false, notes, null, false));
            }
        }
        candidates.addAll(byKey.values());
        if (candidates.isEmpty() && skipped == 0) throw new IllegalArgumentException("没有找到可以调用的 n8n Webhook");
        warnings.add("n8n Webhook 不说明要哪些字段，请在接纳前把请求体字段补上，或在工作流里写清楚备注，智能体才知道怎么填");
        return new HttpApiDocumentImportService.Preview("N8N", "n8n 工作流", null, fetchedFrom, strip, skipped, warnings, candidates);
    }

    private HttpApiDocumentImportService.Candidate webhookCandidate(JsonNode workflow, JsonNode node, String basePath, String strip,
                                                                    String host, boolean active) {
        JsonNode parameters = node.path("parameters");
        String hookPath = parameters.path("path").asText("");
        if (hookPath.isEmpty()) hookPath = node.path("webhookId").asText("");
        if (hookPath.isEmpty()) return null;
        String method = parameters.path("httpMethod").asText("GET").toUpperCase(Locale.ROOT);
        if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD").contains(method)) method = "POST";
        // n8n path parameters look like :id; the manual API format uses {id}.
        String webhookId = node.path("webhookId").asText("");
        // Dynamic paths (with :param) are served under the webhook id: /webhook/<id>/<path>.
        String full = hookPath.contains(":") && !webhookId.isEmpty() && !hookPath.startsWith(webhookId) ? webhookId + "/" + hookPath : hookPath;
        String normalized = trimSlash("/" + full.replaceAll("^/+", "").replaceAll(":([A-Za-z_][A-Za-z0-9_]*)", "{$1}"));
        String path = relative(basePath + "/webhook" + normalized, strip);
        String auth = parameters.path("authentication").asText("none");
        String mode = switch (auth) {
            case "headerAuth" -> "API_KEY_HEADER";
            case "jwtAuth" -> "BEARER";
            case "none" -> "NONE";
            default -> "UNKNOWN";
        };
        String name = workflow.path("name").asText("n8n 工作流");
        String notes = node.path("notes").asText("");
        String description = "n8n 工作流「" + name + "」的 Webhook 触发。" + (notes.isEmpty() ? "" : "\n\n" + notes);
        ManualHttpApiService.Request api = webhookRequest(method, path, name, description, mode);
        List<String> warnings = new ArrayList<>();
        if ("headerAuth".equals(auth)) warnings.add("Webhook 用了请求头鉴权：导入后在「接口鉴权」里填请求头名称和取值");
        if ("basicAuth".equals(auth)) warnings.add("Webhook 用了 Basic 鉴权，ReachAI 暂不支持，请在 n8n 里改成请求头鉴权");
        if (!active) warnings.add("工作流还没激活");
        if ("onReceived".equals(parameters.path("responseMode").asText("onReceived"))) {
            warnings.add("这个 Webhook 收到请求就立刻返回，拿不到工作流结果；要返回结果请在 n8n 里把响应方式改成「最后一个节点」或「Respond to Webhook」");
        }
        String key = method + " " + path;
        return new HttpApiDocumentImportService.Candidate(key, api, host, false, warnings, null, false);
    }

    private ManualHttpApiService.Request webhookRequest(String method, String path, String name, String description, String authMode) {
        List<ManualHttpApiService.Parameter> parameters = new ArrayList<>();
        Matcher placeholders = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}").matcher(path);
        while (placeholders.find()) {
            parameters.add(new ManualHttpApiService.Parameter(placeholders.group(1), "PATH", "string", true, placeholders.group(1)));
        }
        boolean hasBody = !List.of("GET", "HEAD", "DELETE").contains(method);
        ManualHttpApiService.RequestBody body = null;
        if (hasBody) {
            ObjectNode schema = json.createObjectNode().put("type", "object")
                    .put("description", "发给这个 n8n 工作流的数据（JSON），字段按工作流里 $json.body.xxx 用到的填写")
                    .put("additionalProperties", true);
            schema.putObject("properties");
            body = new ManualHttpApiService.RequestBody("application/json", true, schema);
        }
        return new ManualHttpApiService.Request(method, path, hasBody ? "WRITE" : "READ_ONLY", "n8n", parameters, body, "object",
                new ManualHttpApiService.Authentication(authMode, null), List.of("n8n"), name, description);
    }

    /* ------------------------------------------------------------------ helpers */

    private JsonNode readJson(String url, Map<String, String> headers, String platform) {
        HttpApiDocumentFetcher.Fetched fetched;
        try {
            fetched = fetcher.fetchJson(url, headers);
        } catch (IllegalArgumentException failed) {
            String message = failed.getMessage() == null ? "" : failed.getMessage();
            if (message.contains("401") || message.contains("403")) {
                throw new IllegalArgumentException(platform + " 拒绝了请求：API 密钥不对，或者没有权限");
            }
            if (message.contains("HTTP 404")) {
                throw new IllegalArgumentException(platform + " 地址不对（HTTP 404）：" + ("Dify".equals(platform)
                        ? "请填应用「访问 API」页面上的 API 服务器地址，一般以 /v1 结尾"
                        : "请填 n8n 的访问地址，如 https://n8n.example.com，并确认已开启 n8n 公共 API"));
            }
            throw new IllegalArgumentException(message.replace("文档地址", platform + " 地址").replace("下载文档", "访问 " + platform));
        }
        try {
            return json.readTree(fetched.content());
        } catch (Exception invalid) {
            throw new IllegalArgumentException(platform + " 返回的不是 JSON，请确认地址填的是 API 地址");
        }
    }

    private static String origin(URI uri) {
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
    }

    private static String trimSlash(String path) {
        String value = path == null ? "" : path.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    /** The project adds its own context path when calling, so drop it from imported paths when they start with it. */
    private static String stripPrefix(String basePath, String contextPath) {
        String context = trimSlash(contextPath);
        if (context.isEmpty() || "/".equals(context)) return "";
        return basePath.startsWith(context + "/") || basePath.equals(context) ? context : "";
    }

    private static String relative(String path, String strip) {
        String value = path.startsWith("/") ? path : "/" + path;
        if (StringUtils.hasText(strip) && (value.startsWith(strip + "/") || value.equals(strip))) value = value.substring(strip.length());
        return value.isEmpty() ? "/" : value;
    }
}

package com.enterprise.ai.capability.catalog.httpapi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.common.capability.HttpApiJsonBinding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a whole API document (OpenAPI 3, Swagger 2.0, Postman Collection v2.x or a browser HAR capture)
 * into manual HTTP API requests people can preview, tick and save in one go.
 *
 * <p>Nothing here writes to the database. Only structure is kept: example values, tokens and cookies from
 * the document are dropped, and authentication is reduced to "which header carries it".</p>
 */
@Service
public class HttpApiDocumentImportService {
    public static final int MAX_CHARS = 5 * 1024 * 1024;
    static final int MAX_CANDIDATES = 2000;
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> SCALARS = Set.of("string", "integer", "number", "boolean");
    private static final Set<String> STRING_FORMATS = Set.of("password", "uuid", "date", "date-time");
    private static final Pattern READ_HINT = Pattern.compile(
            "(?i)(^|[/_.-])(page|list|query|search|get|find|detail|count|export-?preview|simple-list)([/_.-]|$)|查询|列表|分页|详情|获取|统计");
    private static final Pattern SECRET_NAME = Pattern.compile(
            "(?i)(pass(word)?|pwd|token|secret|api[-_]?key|access[-_]?key|credential|session|cookie|signature|auth)");
    private static final Pattern STATIC_ASSET = Pattern.compile(
            "(?i)\\.(js|mjs|css|map|png|jpe?g|gif|svg|ico|webp|woff2?|ttf|eot|html?|txt|xml)$");
    private static final Pattern ID_SEGMENT = Pattern.compile(
            "(?i)^(\\d+|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{16,})$");

    private final ObjectMapper json;
    private final ScanProjectMapper projects;
    private final HttpApiAssetMapper assets;
    private final ManualHttpApiService manual;
    private final HttpApiDocumentFetcher fetcher;

    public HttpApiDocumentImportService(ObjectMapper json, ScanProjectMapper projects, HttpApiAssetMapper assets,
                                        ManualHttpApiService manual, HttpApiDocumentFetcher fetcher) {
        this.json = json;
        this.projects = projects;
        this.assets = assets;
        this.manual = manual;
        this.fetcher = fetcher;
    }

    /** Parses the document (pasted, uploaded or fetched from {@code url}) for one project. */
    public Preview preview(long projectId, PreviewRequest request) {
        if (projectId <= 0) throw new IllegalArgumentException("项目无效");
        ScanProjectEntity project = projects.selectById(projectId);
        if (project == null) throw new ManualHttpApiService.NotFound();
        if (request == null) throw new IllegalArgumentException("请提供接口文档");
        String content = request.content();
        String fetchedFrom = null;
        if (!StringUtils.hasText(content)) {
            if (!StringUtils.hasText(request.url())) throw new IllegalArgumentException("请上传文件、粘贴文档内容或填写文档地址");
            HttpApiDocumentFetcher.Fetched fetched = fetcher.fetch(request.url().trim(), request.authorization());
            content = fetched.content();
            fetchedFrom = fetched.url();
        }
        String prefix = request.stripPrefix() != null ? request.stripPrefix() : project.getContextPath();
        Preview parsed = parse(content, prefix);
        return mark(project, parsed, fetchedFrom, prefix);
    }

    /**
     * Reads a Dify app or n8n webhooks and offers them as APIs, so existing workflows on those platforms can be
     * called like any business API. Nothing is saved; the page posts the ticked ones to /batch.
     */
    public Preview previewPlatform(long projectId, WorkflowPlatformReader.Request request) {
        if (projectId <= 0) throw new IllegalArgumentException("项目无效");
        ScanProjectEntity project = projects.selectById(projectId);
        if (project == null) throw new ManualHttpApiService.NotFound();
        Preview parsed = new WorkflowPlatformReader(json, fetcher).read(request, project.getContextPath());
        return mark(project, parsed, parsed.fetchedFrom(), parsed.stripPrefix());
    }

    private Preview mark(ScanProjectEntity project, Preview parsed, String fetchedFrom, String prefix) {
        Set<String> existing = existingKeys(project);
        String context = joinable(project.getContextPath());
        List<Candidate> marked = new ArrayList<>(parsed.candidates().size());
        for (Candidate candidate : parsed.candidates()) {
            String unsupported = candidate.unsupported();
            if (unsupported == null) unsupported = manual.validationError(project, candidate.request());
            boolean exists = existing.contains(candidate.request().httpMethod() + " " + context + candidate.request().path());
            marked.add(new Candidate(candidate.key(), candidate.request(), candidate.host(), candidate.deprecated(),
                    candidate.warnings(), unsupported, exists));
        }
        return new Preview(parsed.format(), parsed.title(), parsed.version(), fetchedFrom, prefix == null ? "" : prefix,
                parsed.skipped(), parsed.warnings(), marked);
    }

    /** Parses without a project (no "already exists" marks, no save-time checks). */
    Preview parse(String content, String stripPrefix) {
        JsonNode root = read(content);
        List<String> notes = new ArrayList<>();
        Builder builder = new Builder(stripPrefix);
        String format;
        String title;
        String version = null;
        if (root.path("openapi").asText("").startsWith("3")) {
            format = "OPENAPI3";
            title = root.path("info").path("title").asText("");
            version = root.path("info").path("version").asText(null);
            readOpenApi(root, builder);
        } else if (root.path("swagger").asText("").startsWith("2")) {
            format = "SWAGGER2";
            title = root.path("info").path("title").asText("");
            version = root.path("info").path("version").asText(null);
            readOpenApi(swagger2(root), builder);
        } else if (root.has("item") && root.path("info").isObject()) {
            format = "POSTMAN";
            title = root.path("info").path("name").asText("");
            if (!root.path("info").path("schema").asText("").contains("v2")) {
                notes.add("这份 Postman 集合不是 v2.x 格式，可能有接口读不全；请在 Postman 里用 Collection v2.1 重新导出");
            }
            readPostman(root, builder);
        } else if (root.path("log").path("entries").isArray()) {
            format = "HAR";
            title = "浏览器抓包";
            readHar(root, builder);
        } else {
            throw new IllegalArgumentException("无法识别这份文档：支持 OpenAPI 3、Swagger 2.0、Postman 集合（v2.x）和浏览器导出的 HAR");
        }
        if (builder.candidates.isEmpty() && builder.skipped == 0) throw new IllegalArgumentException("文档里没有找到接口");
        if (builder.truncated) notes.add("文档里的接口超过 " + MAX_CANDIDATES + " 个，只读取了前 " + MAX_CANDIDATES + " 个");
        return new Preview(format, title, version, null, stripPrefix == null ? "" : stripPrefix, builder.skipped,
                notes, builder.candidates);
    }

    private Set<String> existingKeys(ScanProjectEntity project) {
        Set<String> keys = new HashSet<>();
        for (HttpApiAssetEntity asset : assets.selectList(Wrappers.<HttpApiAssetEntity>lambdaQuery()
                .select(HttpApiAssetEntity::getHttpMethod, HttpApiAssetEntity::getRouteTemplate)
                .eq(HttpApiAssetEntity::getProjectId, project.getId()))) {
            keys.add(asset.getHttpMethod() + " " + asset.getRouteTemplate());
        }
        return keys;
    }

    private static String joinable(String contextPath) {
        String value = contextPath == null ? "" : contextPath.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    /* ------------------------------------------------------------------ reading */

    JsonNode read(String content) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("文档内容为空");
        if (content.length() > MAX_CHARS) throw new IllegalArgumentException("文档不能超过 5MB");
        String text = content.strip();
        if (text.startsWith("﻿")) text = text.substring(1);
        if (text.startsWith("<")) {
            throw new IllegalArgumentException("这是网页，不是接口文档。Swagger 页面请改用 /v3/api-docs 或 /v2/api-docs 地址；Knife4j（doc.html）请用 /v2/api-docs");
        }
        try {
            if (text.startsWith("{") || text.startsWith("[")) {
                JsonNode node = json.readTree(text);
                if (!node.isObject()) throw new IllegalArgumentException("文档顶层需要是一个对象");
                return node;
            }
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(MAX_CHARS + 1024);
            options.setMaxAliasesForCollections(50);
            options.setAllowDuplicateKeys(true);
            Object loaded = new Yaml(new SafeConstructor(options)).load(text);
            if (!(loaded instanceof Map<?, ?>)) throw new IllegalArgumentException("文档格式无法识别：需要 JSON 或 YAML");
            return json.valueToTree(loaded);
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("文档不是合法的 JSON 或 YAML：" + shortMessage(invalid));
        }
    }

    private static String shortMessage(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        message = message.split("\n")[0];
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    /* ------------------------------------------------------------------ OpenAPI 3 */

    private void readOpenApi(JsonNode root, Builder builder) {
        String serverPath = serverPath(root, builder);
        JsonNode paths = root.path("paths");
        if (!paths.isObject()) return;
        for (Iterator<Map.Entry<String, JsonNode>> it = paths.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> pathEntry = it.next();
            JsonNode item = resolve(root, pathEntry.getValue());
            if (!item.isObject()) continue;
            List<JsonNode> shared = list(item.path("parameters"));
            for (Iterator<Map.Entry<String, JsonNode>> ops = item.fields(); ops.hasNext(); ) {
                Map.Entry<String, JsonNode> op = ops.next();
                String method = op.getKey().toUpperCase(Locale.ROOT);
                if (Set.of("HEAD", "OPTIONS", "TRACE").contains(method)) { builder.skipped++; continue; }
                if (!METHODS.contains(method) || !op.getValue().isObject()) continue;
                if (builder.full()) return;
                readOperation(root, serverPath + pathEntry.getKey(), method, op.getValue(), shared, builder);
            }
        }
    }

    private String serverPath(JsonNode root, Builder builder) {
        JsonNode server = root.path("servers").path(0);
        String url = server.path("url").asText("");
        if (url.isBlank()) return "";
        if (url.contains("{")) {
            // Fill server variables with their defaults: https://{host}/{basePath} → defaults.
            JsonNode variables = server.path("variables");
            for (Iterator<Map.Entry<String, JsonNode>> it = variables.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> variable = it.next();
                url = url.replace("{" + variable.getKey() + "}", variable.getValue().path("default").asText(""));
            }
            if (url.contains("{")) return "";
        }
        String path = url.replaceFirst("(?i)^[a-z][a-z0-9+.-]*://[^/]*", "");
        path = path.split("[?#]")[0];
        if (path.equals("/")) return "";
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    private void readOperation(JsonNode root, String rawPath, String method, JsonNode operation, List<JsonNode> shared,
                               Builder builder) {
        Draft draft = new Draft(method);
        draft.summary = text(operation.path("summary"), 200);
        draft.description = text(operation.path("description"), 2000);
        if (draft.summary == null && operation.hasNonNull("operationId")) draft.summary = text(operation.path("operationId"), 200);
        for (JsonNode tag : operation.path("tags")) if (tag.isTextual() && !tag.asText().isBlank()) draft.groups.add(tag.asText().trim());
        draft.deprecated = operation.path("deprecated").asBoolean(false);
        draft.path = builder.path(rawPath);

        Map<String, JsonNode> parameters = new LinkedHashMap<>();
        for (JsonNode raw : shared) { JsonNode p = resolve(root, raw); parameters.put(p.path("in").asText() + ":" + p.path("name").asText(), p); }
        for (JsonNode raw : list(operation.path("parameters"))) { JsonNode p = resolve(root, raw); parameters.put(p.path("in").asText() + ":" + p.path("name").asText(), p); }
        for (JsonNode parameter : parameters.values()) readParameter(root, parameter, draft);

        JsonNode body = resolve(root, operation.path("requestBody"));
        if (body.isObject() && body.path("content").isObject()) readBody(root, body, draft);

        draft.responseType = responseType(root, operation.path("responses"));
        draft.auth = security(root, operation.has("security") ? operation.path("security") : root.path("security"), draft);
        builder.add(draft);
    }

    private void readParameter(JsonNode root, JsonNode parameter, Draft draft) {
        String name = parameter.path("name").asText("").trim();
        String in = parameter.path("in").asText("").toLowerCase(Locale.ROOT);
        if (name.isEmpty()) return;
        JsonNode schema = resolve(root, parameter.has("schema") ? parameter.path("schema") : parameter);
        String description = text(parameter.path("description"), 500);
        if (description == null) description = text(schema.path("description"), 500);
        boolean required = parameter.path("required").asBoolean(false);
        switch (in) {
            case "path" -> draft.parameters.add(new ManualHttpApiService.Parameter(name, "PATH", scalar(schema, name, draft), true, description));
            case "query" -> {
                String type = schemaType(schema);
                if ("object".equals(type) && schema.path("properties").isObject()) {
                    // Spring @ParameterObject / form-explode objects: every field is its own query parameter.
                    Set<String> requiredFields = new HashSet<>();
                    for (JsonNode field : schema.path("required")) requiredFields.add(field.asText());
                    for (Iterator<Map.Entry<String, JsonNode>> it = schema.path("properties").fields(); it.hasNext(); ) {
                        Map.Entry<String, JsonNode> field = it.next();
                        JsonNode fieldSchema = resolve(root, field.getValue());
                        draft.parameters.add(new ManualHttpApiService.Parameter(field.getKey(), "QUERY",
                                scalar(fieldSchema, field.getKey(), draft), requiredFields.contains(field.getKey()),
                                text(fieldSchema.path("description"), 500)));
                    }
                } else {
                    draft.parameters.add(new ManualHttpApiService.Parameter(name, "QUERY", scalar(schema, name, draft), required, description));
                }
            }
            case "header", "cookie" -> {
                if (SECRET_NAME.matcher(name).find() || "authorization".equalsIgnoreCase(name)) {
                    if ("header".equals(in) && !"authorization".equalsIgnoreCase(name) && draft.headerKey == null) draft.headerKey = name;
                } else if (required) {
                    draft.warnings.add(("header".equals(in) ? "请求头 " : "Cookie ") + name + " 是必填的，导入后无法由智能体传入，请确认后端可以不传");
                }
            }
            default -> { }
        }
    }

    private void readBody(JsonNode root, JsonNode body, Draft draft) {
        JsonNode content = body.path("content");
        String media = null;
        for (Iterator<String> it = content.fieldNames(); it.hasNext(); ) {
            String candidate = it.next();
            String lower = candidate.toLowerCase(Locale.ROOT);
            if (lower.startsWith("application/json") || lower.endsWith("+json") || lower.equals("*/*")) { media = candidate; break; }
            if (lower.startsWith("text/plain") && media == null) media = candidate;
        }
        if (media == null) {
            String first = content.fieldNames().hasNext() ? content.fieldNames().next() : "";
            draft.unsupported = first.toLowerCase(Locale.ROOT).contains("multipart") || first.toLowerCase(Locale.ROOT).contains("octet-stream")
                    ? "文件上传接口暂不支持" : "请求体格式 " + first + " 暂不支持，只支持 JSON 和纯文本";
            return;
        }
        if ("GET".equals(draft.method)) { draft.warnings.add("GET 请求的请求体已忽略"); return; }
        boolean plain = media.toLowerCase(Locale.ROOT).startsWith("text/plain");
        JsonNode schema = plain ? JsonNodeFactory.instance.objectNode().put("type", "string")
                : sanitize(root, content.path(media).path("schema"), 0, new LinkedHashSet<>(), draft, new int[]{0});
        draft.body(schema, plain ? "text/plain" : "application/json", body.path("required").asBoolean(true));
    }

    private String responseType(JsonNode root, JsonNode responses) {
        if (!responses.isObject()) return "object";
        for (String status : List.of("200", "201", "2XX", "2xx", "default")) {
            JsonNode response = resolve(root, responses.path(status));
            if (!response.isObject()) continue;
            for (JsonNode media : response.path("content")) {
                JsonNode schema = resolve(root, media.path("schema"));
                return "array".equals(schemaType(schema)) ? "array" : "object";
            }
            return "object";
        }
        return "object";
    }

    private ManualHttpApiService.Authentication security(JsonNode root, JsonNode security, Draft draft) {
        if (security.isArray() && security.isEmpty()) return new ManualHttpApiService.Authentication("NONE", null);
        JsonNode schemes = root.path("components").path("securitySchemes");
        List<String> notes = new ArrayList<>();
        if (security.isArray()) {
            for (JsonNode requirement : security) {
                for (Iterator<String> it = requirement.fieldNames(); it.hasNext(); ) {
                    JsonNode scheme = resolve(root, schemes.path(it.next()));
                    String type = scheme.path("type").asText("").toLowerCase(Locale.ROOT);
                    if ("http".equals(type) && "bearer".equalsIgnoreCase(scheme.path("scheme").asText())) return bearer();
                    if ("oauth2".equals(type) || "openidconnect".equals(type)) return bearer();
                    if ("apikey".equals(type) && "header".equalsIgnoreCase(scheme.path("in").asText())) {
                        String header = scheme.path("name").asText("");
                        if ("authorization".equalsIgnoreCase(header)) return bearer();
                        if (!header.isBlank()) return new ManualHttpApiService.Authentication("API_KEY_HEADER", header);
                    }
                    if ("http".equals(type) && "basic".equalsIgnoreCase(scheme.path("scheme").asText())) {
                        notes.add("文档写的是 Basic 账号密码认证，请在「接口鉴权」里确认");
                    } else if ("apikey".equals(type)) {
                        notes.add("文档把密钥放在 " + scheme.path("in").asText() + " 里，平台只支持放在请求头，请在「接口鉴权」里确认");
                    }
                }
            }
        }
        if (draft.headerKey != null) return new ManualHttpApiService.Authentication("API_KEY_HEADER", draft.headerKey);
        notes.stream().distinct().forEach(draft.warnings::add);
        return new ManualHttpApiService.Authentication("UNKNOWN", null);
    }

    private static ManualHttpApiService.Authentication bearer() {
        return new ManualHttpApiService.Authentication("BEARER", null);
    }

    /* ------------------------------------------------------------------ Swagger 2.0 → OpenAPI 3 */

    JsonNode swagger2(JsonNode source) {
        ObjectNode doc = (ObjectNode) rewriteRefs(source.deepCopy());
        ObjectNode result = json.createObjectNode();
        result.put("openapi", "3.0.3");
        result.set("info", doc.path("info").isObject() ? doc.get("info") : json.createObjectNode());
        String basePath = doc.path("basePath").asText("");
        if (!basePath.isBlank() && !basePath.equals("/")) result.putArray("servers").addObject().put("url", basePath);
        List<String> consumes = strings(doc.path("consumes"), "application/json");
        List<String> produces = strings(doc.path("produces"), "application/json");
        JsonNode sharedParameters = doc.path("parameters");
        ObjectNode paths = result.putObject("paths");
        for (Iterator<Map.Entry<String, JsonNode>> it = doc.path("paths").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (!entry.getValue().isObject()) continue;
            ObjectNode item = paths.putObject(entry.getKey());
            List<JsonNode> pathLevel = list(entry.getValue().path("parameters"));
            for (Iterator<Map.Entry<String, JsonNode>> ops = entry.getValue().fields(); ops.hasNext(); ) {
                Map.Entry<String, JsonNode> op = ops.next();
                if (!Set.of("get", "post", "put", "patch", "delete", "head", "options").contains(op.getKey()) || !op.getValue().isObject()) continue;
                ObjectNode operation = op.getValue().deepCopy();
                List<String> opConsumes = op.getValue().has("consumes") ? strings(op.getValue().path("consumes"), "application/json") : consumes;
                List<String> opProduces = op.getValue().has("produces") ? strings(op.getValue().path("produces"), "application/json") : produces;
                operation.remove("consumes");
                operation.remove("produces");
                ArrayNode parameters = json.createArrayNode();
                List<JsonNode> all = new ArrayList<>(pathLevel);
                all.addAll(list(op.getValue().path("parameters")));
                boolean form = false;
                for (JsonNode raw : all) {
                    JsonNode parameter = raw;
                    String ref = raw.path("$ref").asText("");
                    if (ref.startsWith("#/parameters/")) parameter = sharedParameters.path(ref.substring("#/parameters/".length()));
                    String in = parameter.path("in").asText("");
                    if ("formData".equals(in)) { form = true; continue; }
                    if ("body".equals(in)) {
                        ObjectNode requestBody = operation.putObject("requestBody");
                        requestBody.put("required", parameter.path("required").asBoolean(false));
                        ObjectNode content = requestBody.putObject("content");
                        for (String media : opConsumes) content.putObject(media).set("schema", parameter.path("schema").isMissingNode()
                                ? json.createObjectNode() : parameter.get("schema"));
                        continue;
                    }
                    ObjectNode next = parameters.addObject();
                    next.put("name", parameter.path("name").asText());
                    next.put("in", in);
                    next.put("required", "path".equals(in) || parameter.path("required").asBoolean(false));
                    if (parameter.has("description")) next.set("description", parameter.get("description"));
                    ObjectNode schema = next.putObject("schema");
                    for (String key : List.of("type", "format", "items", "enum", "default", "minimum", "maximum", "minLength", "maxLength")) {
                        if (parameter.has(key)) schema.set(key, parameter.get(key));
                    }
                }
                if (form) {
                    ObjectNode requestBody = operation.putObject("requestBody");
                    requestBody.putObject("content").putObject(opConsumes.stream().anyMatch(media -> media.contains("multipart"))
                            ? "multipart/form-data" : "application/x-www-form-urlencoded");
                }
                operation.set("parameters", parameters);
                ObjectNode responses = json.createObjectNode();
                for (Iterator<Map.Entry<String, JsonNode>> rs = op.getValue().path("responses").fields(); rs.hasNext(); ) {
                    Map.Entry<String, JsonNode> response = rs.next();
                    ObjectNode next = responses.putObject(response.getKey());
                    next.put("description", response.getValue().path("description").asText(""));
                    if (response.getValue().has("schema")) {
                        ObjectNode content = next.putObject("content");
                        for (String media : opProduces) content.putObject(media).set("schema", response.getValue().get("schema"));
                    }
                }
                operation.set("responses", responses);
                item.set(op.getKey(), operation);
            }
        }
        ObjectNode components = result.putObject("components");
        components.set("schemas", doc.path("definitions").isObject() ? doc.get("definitions") : json.createObjectNode());
        ObjectNode schemes = components.putObject("securitySchemes");
        for (Iterator<Map.Entry<String, JsonNode>> it = doc.path("securityDefinitions").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode raw = entry.getValue();
            String type = raw.path("type").asText("");
            ObjectNode scheme = schemes.putObject(entry.getKey());
            switch (type) {
                case "apiKey" -> { scheme.put("type", "apiKey"); scheme.put("name", raw.path("name").asText()); scheme.put("in", raw.path("in").asText()); }
                case "basic" -> { scheme.put("type", "http"); scheme.put("scheme", "basic"); }
                case "oauth2" -> scheme.put("type", "oauth2");
                default -> scheme.put("type", type);
            }
        }
        if (doc.has("security")) result.set("security", doc.get("security"));
        return result;
    }

    private JsonNode rewriteRefs(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                if ("$ref".equals(entry.getKey()) && entry.getValue().isTextual()) {
                    entry.setValue(JsonNodeFactory.instance.textNode(entry.getValue().asText().replaceFirst("^#/definitions/", "#/components/schemas/")));
                } else {
                    rewriteRefs(entry.getValue());
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) rewriteRefs(item);
        }
        return node;
    }

    /* ------------------------------------------------------------------ Postman */

    private void readPostman(JsonNode root, Builder builder) {
        Map<String, String> variables = new LinkedHashMap<>();
        for (JsonNode variable : root.path("variable")) variables.put(variable.path("key").asText(), variable.path("value").asText(""));
        walkPostman(root.path("item"), List.of(), root.path("auth"), variables, builder);
    }

    private void walkPostman(JsonNode items, List<String> folders, JsonNode inheritedAuth, Map<String, String> variables, Builder builder) {
        for (JsonNode item : list(items)) {
            if (builder.full()) return;
            JsonNode auth = item.path("auth").isObject() ? item.path("auth") : inheritedAuth;
            if (item.path("item").isArray()) {
                List<String> next = new ArrayList<>(folders);
                if (!item.path("name").asText("").isBlank()) next.add(item.path("name").asText().trim());
                walkPostman(item.path("item"), next, auth, variables, builder);
                continue;
            }
            JsonNode request = item.path("request");
            if (request.isMissingNode()) continue;
            readPostmanRequest(item, request, folders, auth, variables, builder);
        }
    }

    private void readPostmanRequest(JsonNode item, JsonNode request, List<String> folders, JsonNode inheritedAuth,
                                    Map<String, String> variables, Builder builder) {
        String method = request.isTextual() ? "GET" : request.path("method").asText("GET").toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) { builder.skipped++; return; }
        Draft draft = new Draft(method);
        draft.summary = text(item.path("name"), 200);
        draft.description = description(request.path("description"));
        if (!folders.isEmpty()) draft.groups.add(folders.get(folders.size() - 1));

        JsonNode url = request.isTextual() ? request : request.path("url");
        String raw = url.isTextual() ? url.asText() : url.path("raw").asText("");
        String path;
        if (url.isObject() && url.path("path").isArray()) {
            StringBuilder joined = new StringBuilder();
            for (JsonNode segment : url.path("path")) joined.append('/').append(segment.isObject() ? segment.path("value").asText() : segment.asText());
            path = joined.toString();
        } else {
            path = rawPath(raw);
        }
        draft.host = postmanHost(url, raw, variables);
        draft.path = builder.path(path);

        Map<String, String> pathValues = new LinkedHashMap<>();
        for (JsonNode variable : url.path("variable")) pathValues.put(variable.path("key").asText(), variable.path("value").asText(""));
        Matcher matcher = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_.-]*)}").matcher(draft.path);
        while (matcher.find()) {
            String name = matcher.group(1);
            draft.parameters.add(new ManualHttpApiService.Parameter(name, "PATH", inferScalar(pathValues.getOrDefault(name, "")), true, null));
        }
        if (url.isObject() && url.path("query").isArray()) {
            for (JsonNode query : url.path("query")) {
                if (query.path("disabled").asBoolean(false)) continue;
                addQuery(draft, query.path("key").asText(""), query.path("value").asText(""), description(query.path("description")));
            }
        } else {
            queryFromRaw(raw, draft);
        }

        String contentType = "";
        for (JsonNode header : list(request.path("header"))) {
            if (header.path("disabled").asBoolean(false)) continue;
            String name = header.path("key").asText("");
            String value = header.path("value").asText("");
            if ("content-type".equalsIgnoreCase(name)) contentType = value.toLowerCase(Locale.ROOT);
            else authHeader(draft, name, value);
        }
        ManualHttpApiService.Authentication auth = postmanAuth(request.path("auth").isObject() ? request.path("auth") : inheritedAuth);
        if (auth != null && draft.authFromHeader == null) draft.auth = auth;
        else if (draft.authFromHeader != null) draft.auth = draft.authFromHeader;

        JsonNode body = request.path("body");
        if (body.isObject() && !body.path("disabled").asBoolean(false)) {
            String mode = body.path("mode").asText("");
            switch (mode) {
                case "raw" -> {
                    String text = body.path("raw").asText("");
                    String language = body.path("options").path("raw").path("language").asText("");
                    if (!text.isBlank()) sampleBody(draft, text, "json".equalsIgnoreCase(language) || contentType.contains("json"));
                }
                case "urlencoded" -> { if (!list(body.path("urlencoded")).isEmpty()) draft.unsupported = "表单编码（x-www-form-urlencoded）请求体暂不支持"; }
                case "formdata" -> { if (!list(body.path("formdata")).isEmpty()) draft.unsupported = "表单 / 文件上传接口暂不支持"; }
                case "file" -> draft.unsupported = "文件上传接口暂不支持";
                case "graphql" -> draft.unsupported = "GraphQL 接口暂不支持";
                default -> { }
            }
        }
        draft.sideEffect = null;
        builder.add(draft);
    }

    private String postmanHost(JsonNode url, String raw, Map<String, String> variables) {
        String host = null;
        if (url.isObject() && url.path("host").isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode part : url.path("host")) parts.add(part.asText());
            host = String.join(".", parts);
        } else if (raw.matches("(?i)^https?://.*")) {
            host = raw.replaceFirst("(?i)^https?://", "").split("[/?#]")[0];
        } else if (raw.startsWith("{{")) {
            host = raw.substring(0, raw.indexOf("}}") + 2 > 2 ? raw.indexOf("}}") + 2 : raw.length());
        }
        if (host == null) return null;
        Matcher variable = Pattern.compile("^\\{\\{([^}]+)}}$").matcher(host);
        if (variable.matches() && variables.containsKey(variable.group(1))) {
            String value = variables.get(variable.group(1));
            String resolved = value.replaceFirst("(?i)^https?://", "").split("[/?#]")[0];
            if (!resolved.isBlank()) return resolved;
        }
        return host.length() > 120 ? host.substring(0, 120) : host;
    }

    private ManualHttpApiService.Authentication postmanAuth(JsonNode auth) {
        if (!auth.isObject()) return null;
        String type = auth.path("type").asText("").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "noauth" -> new ManualHttpApiService.Authentication("NONE", null);
            case "bearer", "oauth2", "jwt" -> bearer();
            case "apikey" -> {
                String key = null;
                String in = "header";
                for (JsonNode entry : auth.path("apikey")) {
                    if ("key".equals(entry.path("key").asText())) key = entry.path("value").asText();
                    if ("in".equals(entry.path("key").asText())) in = entry.path("value").asText("header");
                }
                yield key != null && "header".equalsIgnoreCase(in) && !key.isBlank()
                        ? new ManualHttpApiService.Authentication("API_KEY_HEADER", key) : null;
            }
            default -> null;
        };
    }

    private static String description(JsonNode node) {
        if (node.isTextual()) return text(node, 2000);
        if (node.isObject()) return text(node.path("content"), 2000);
        return null;
    }

    /* ------------------------------------------------------------------ HAR */

    private void readHar(JsonNode root, Builder builder) {
        Map<String, Draft> byKey = new LinkedHashMap<>();
        for (JsonNode entry : root.path("log").path("entries")) {
            JsonNode request = entry.path("request");
            String method = request.path("method").asText("").toUpperCase(Locale.ROOT);
            if (!METHODS.contains(method)) continue;
            String url = request.path("url").asText("");
            URI uri;
            try { uri = URI.create(url); } catch (IllegalArgumentException invalid) { continue; }
            if (uri.getRawPath() == null || STATIC_ASSET.matcher(uri.getRawPath()).find()) continue;
            String resource = entry.path("_resourceType").asText("").toLowerCase(Locale.ROOT);
            String responseMime = entry.path("response").path("content").path("mimeType").asText("").toLowerCase(Locale.ROOT);
            String requestMime = request.path("postData").path("mimeType").asText("").toLowerCase(Locale.ROOT);
            boolean api = resource.equals("xhr") || resource.equals("fetch") || responseMime.contains("json") || requestMime.contains("json");
            if (!api) continue;
            String templated = templatePath(URLDecoder.decode(uri.getRawPath().replace("+", "%2B"), StandardCharsets.UTF_8));
            String path = builder.path(templated);
            String key = method + " " + path;
            Draft draft = byKey.get(key);
            if (draft == null) {
                if (byKey.size() >= MAX_CANDIDATES) { builder.truncated = true; break; }
                draft = new Draft(method);
                draft.path = path;
                draft.host = uri.getHost();
                Matcher matcher = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_.-]*)}").matcher(path);
                while (matcher.find()) draft.parameters.add(new ManualHttpApiService.Parameter(matcher.group(1), "PATH", "string", true, null));
                byKey.put(key, draft);
            }
            for (JsonNode query : request.path("queryString")) addQuery(draft, query.path("name").asText(""), query.path("value").asText(""), null);
            for (JsonNode header : request.path("headers")) {
                String name = header.path("name").asText("");
                if (!name.startsWith(":")) authHeader(draft, name, header.path("value").asText(""));
            }
            if (draft.authFromHeader != null) draft.auth = draft.authFromHeader;
            String postText = request.path("postData").path("text").asText("");
            if (!postText.isBlank() && draft.bodySchema == null && draft.unsupported == null) {
                if (requestMime.contains("x-www-form-urlencoded")) draft.unsupported = "表单编码（x-www-form-urlencoded）请求体暂不支持";
                else if (requestMime.contains("multipart")) draft.unsupported = "表单 / 文件上传接口暂不支持";
                else sampleBody(draft, postText, requestMime.contains("json"));
            }
            String responseText = entry.path("response").path("content").path("text").asText("").stripLeading();
            if (responseText.startsWith("[")) draft.responseType = "array";
        }
        for (Draft draft : byKey.values()) {
            draft.summary = null;
            builder.add(draft);
        }
    }

    /** /erp/stock/123 → /erp/stock/{id}; several ids become {id}, {id2}… */
    static String templatePath(String path) {
        String[] segments = path.split("/", -1);
        int count = 0;
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (index > 0) result.append('/');
            if (!segment.isEmpty() && ID_SEGMENT.matcher(segment).matches()) {
                count++;
                result.append(count == 1 ? "{id}" : "{id" + count + "}");
            } else {
                result.append(segment);
            }
        }
        return result.toString();
    }

    /* ------------------------------------------------------------------ shared helpers */

    private void addQuery(Draft draft, String name, String value, String description) {
        String key = name == null ? "" : name.trim();
        if (key.isEmpty() || draft.parameters.stream().anyMatch(p -> "QUERY".equals(p.location()) && p.name().equals(key))) return;
        if (key.endsWith("[]")) return;
        boolean secret = SECRET_NAME.matcher(key).find();
        if (secret && draft.secretNotes.add(key)) draft.warnings.add("查询参数 " + key + " 看起来是密钥，取值已丢弃");
        draft.parameters.add(new ManualHttpApiService.Parameter(key, "QUERY", secret ? "string" : inferScalar(value), false, description));
    }

    private void queryFromRaw(String raw, Draft draft) {
        int question = raw.indexOf('?');
        if (question < 0) return;
        String query = raw.substring(question + 1).split("#")[0];
        for (String pair : query.split("&")) {
            if (pair.isBlank()) continue;
            int eq = pair.indexOf('=');
            addQuery(draft, eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : pair.substring(eq + 1), null);
        }
    }

    private void authHeader(Draft draft, String name, String value) {
        String lower = name.toLowerCase(Locale.ROOT);
        if ("authorization".equals(lower)) {
            if (value.regionMatches(true, 0, "bearer", 0, 6) || value.startsWith("{{")) draft.authFromHeader = bearer();
            else if (draft.secretNotes.add("authorization")) draft.warnings.add("Authorization 请求头不是 Bearer 令牌，请在「接口鉴权」里确认");
        } else if (lower.matches(".*(api[-_]?key|access[-_]?key|token|secret).*") && (draft.authFromHeader == null
                || !"BEARER".equals(draft.authFromHeader.mode()))) {
            draft.authFromHeader = new ManualHttpApiService.Authentication("API_KEY_HEADER", name);
        } else if (lower.equals("tenant-id") && draft.secretNotes.add(lower)) {
            draft.warnings.add("请求带了 tenant-id 请求头，需要在「接口鉴权」的服务地址或后端里处理租户");
        }
    }

    private void sampleBody(Draft draft, String text, boolean jsonHint) {
        if ("GET".equals(draft.method)) return;
        String trimmed = text.strip();
        if (jsonHint || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode sample = json.readTree(stripPostmanVariables(trimmed));
                JsonNode schema = sanitize(json.createObjectNode(), infer(sample, 0), 0, new LinkedHashSet<>(), draft, new int[]{0});
                draft.body(schema, "application/json", true);
                return;
            } catch (Exception invalid) {
                if (jsonHint) { draft.warnings.add("请求体示例不是合法 JSON，已按对象处理"); draft.body(json.createObjectNode().put("type", "object"), "application/json", true); return; }
            }
        }
        draft.body(json.createObjectNode().put("type", "string"), "text/plain", true);
    }

    /** Postman bodies often hold unquoted {{variables}}; turn them into strings so the example parses. */
    private static String stripPostmanVariables(String text) {
        return text.replaceAll("(?<=[:\\[,]\\s{0,8})\\{\\{[^}]+}}", "\"\"");
    }

    JsonNode infer(JsonNode value, int depth) {
        ObjectNode schema = json.createObjectNode();
        if (depth > 8) return schema;
        if (value.isArray()) {
            schema.put("type", "array");
            JsonNode first = null;
            for (JsonNode item : value) if (!item.isNull()) { first = item; break; }
            schema.set("items", first == null ? json.createObjectNode().put("type", "string") : infer(first, depth + 1));
        } else if (value.isObject()) {
            schema.put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                properties.set(field.getKey(), field.getValue().isNull()
                        ? json.createObjectNode().put("type", "string").put("nullable", true) : infer(field.getValue(), depth + 1));
            }
        } else if (value.isIntegralNumber()) {
            schema.put("type", "integer");
        } else if (value.isNumber()) {
            schema.put("type", "number");
        } else if (value.isBoolean()) {
            schema.put("type", "boolean");
        } else {
            schema.put("type", "string");
        }
        return schema;
    }

    private static String inferScalar(String value) {
        if (value == null) return "string";
        if (value.matches("-?\\d{1,15}")) return "integer";
        if (value.matches("-?\\d+\\.\\d+")) return "number";
        if (value.equals("true") || value.equals("false")) return "boolean";
        return "string";
    }

    private static String rawPath(String raw) {
        String value = raw == null ? "" : raw.trim();
        value = value.replaceFirst("^\\{\\{[^}]+}}", "");
        value = value.replaceFirst("(?i)^https?://[^/?#]*", "");
        return value.split("[?#]")[0];
    }

    private String scalar(JsonNode schema, String name, Draft draft) {
        String type = schemaType(schema);
        if (SCALARS.contains(type)) return type;
        if ("array".equals(type)) {
            draft.warnings.add("参数 " + name + " 是数组，按字符串导入（多个值用英文逗号分隔）");
            return "string";
        }
        return "string";
    }

    private static String schemaType(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isTextual()) return type.asText();
        if (type.isArray()) for (JsonNode item : type) if (!"null".equals(item.asText())) return item.asText();
        if (schema.has("properties")) return "object";
        if (schema.has("items")) return "array";
        return "";
    }

    private static List<JsonNode> list(JsonNode node) {
        List<JsonNode> result = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(result::add);
        return result;
    }

    private static List<String> strings(JsonNode node, String fallback) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) if (item.isTextual()) result.add(item.asText());
        if (result.isEmpty()) result.add(fallback);
        return result;
    }

    private static String text(JsonNode node, int max) {
        if (node == null || !node.isTextual()) return null;
        String value = node.asText().trim();
        if (value.isEmpty()) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** Follows local {@code #/...} references only; remote references are left unresolved. */
    private JsonNode resolve(JsonNode root, JsonNode node) {
        JsonNode current = node;
        for (int hops = 0; hops < 16 && current != null && current.isObject() && current.path("$ref").isTextual(); hops++) {
            String ref = current.path("$ref").asText();
            if (!ref.startsWith("#/")) return JsonNodeFactory.instance.objectNode();
            current = root.at(pointer(ref));
        }
        return current == null || current.isMissingNode() ? JsonNodeFactory.instance.objectNode() : current;
    }

    private static String pointer(String ref) {
        String value = ref.substring(1);
        return value.contains("%") ? URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8) : value;
    }

    /**
     * Reduces a document schema to the subset HTTP API binding enforces: inlines local refs, merges allOf,
     * picks one branch of oneOf/anyOf, drops unknown keywords and readOnly fields, and caps depth/size.
     */
    JsonNode sanitize(JsonNode root, JsonNode raw, int depth, Set<String> refs, Draft draft, int[] nodes) {
        ObjectNode out = json.createObjectNode();
        if (raw == null || !raw.isObject() || ++nodes[0] > 3000) return out;
        JsonNode schema = raw;
        String ref = raw.path("$ref").asText(null);
        if (ref != null) {
            if (refs.contains(ref)) return out.put("type", "object");
            Set<String> nested = new LinkedHashSet<>(refs);
            nested.add(ref);
            return sanitize(root, resolve(root, raw), depth, nested, draft, nodes);
        }
        if (schema.path("allOf").isArray()) schema = mergeAllOf(root, schema, refs);
        for (String union : List.of("oneOf", "anyOf")) {
            if (!schema.path(union).isArray() || schema.path(union).isEmpty()) continue;
            JsonNode chosen = null;
            boolean nullable = false;
            for (JsonNode option : schema.path(union)) {
                JsonNode resolved = resolve(root, option);
                if ("null".equals(resolved.path("type").asText())) { nullable = true; continue; }
                if (chosen == null) chosen = option;
            }
            if (schema.path(union).size() > (nullable ? 2 : 1) && draft.notes.add("union")) draft.warnings.add("请求体有多选一结构，已按第一种导入");
            JsonNode picked = sanitize(root, chosen, depth, refs, draft, nodes);
            if (nullable && picked instanceof ObjectNode object && object.has("type")) object.put("nullable", true);
            return picked;
        }
        String type = schemaType(schema);
        boolean nullable = schema.path("nullable").asBoolean(false);
        if (schema.path("type").isArray()) for (JsonNode item : schema.path("type")) if ("null".equals(item.asText())) nullable = true;
        if (!Set.of("object", "array", "string", "integer", "number", "boolean").contains(type)) return out;
        if (depth >= HttpApiJsonBinding.MAX_DEPTH - 1 && ("object".equals(type) || "array".equals(type))) {
            if (draft.notes.add("depth")) draft.warnings.add("请求体层级太深，深层字段已简化");
            return out;
        }
        out.put("type", type);
        String description = text(schema.path("description"), 500);
        if (description != null) out.put("description", description);
        String title = text(schema.path("title"), 200);
        if (title != null) out.put("title", title);
        if (nullable) out.put("nullable", true);
        if (schema.path("writeOnly").isBoolean()) out.put("writeOnly", schema.path("writeOnly").asBoolean());
        if (schema.path("enum").isArray() && !schema.path("enum").isEmpty()) {
            ArrayNode values = json.createArrayNode();
            for (JsonNode value : schema.path("enum")) if (value.isValueNode() && !value.isNull() && values.size() < 200) values.add(value);
            if (!values.isEmpty()) out.set("enum", values);
        }
        if (schema.has("default") && schema.get("default").isValueNode() && !schema.get("default").isNull()) out.set("default", schema.get("default"));
        switch (type) {
            case "string" -> {
                copyNonNegative(schema, out, "minLength", "maxLength");
                String format = schema.path("format").asText("");
                if (STRING_FORMATS.contains(format)) out.put("format", format);
            }
            case "integer", "number" -> {
                for (String key : List.of("minimum", "maximum")) if (schema.path(key).isNumber()) out.set(key, schema.get(key));
                for (String key : List.of("exclusiveMinimum", "exclusiveMaximum")) if (schema.path(key).isNumber()) out.set(key, schema.get(key));
                if (schema.path("multipleOf").isNumber() && schema.path("multipleOf").decimalValue().signum() > 0) out.set("multipleOf", schema.get("multipleOf"));
            }
            case "array" -> {
                copyNonNegative(schema, out, "minItems", "maxItems");
                if (schema.path("uniqueItems").isBoolean()) out.put("uniqueItems", schema.path("uniqueItems").asBoolean());
                out.set("items", sanitize(root, schema.path("items"), depth + 1, refs, draft, nodes));
            }
            case "object" -> {
                ObjectNode properties = json.createObjectNode();
                for (Iterator<Map.Entry<String, JsonNode>> it = schema.path("properties").fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> field = it.next();
                    String name = field.getKey();
                    if (name.isBlank() || name.length() > 128) continue;
                    JsonNode resolved = resolve(root, field.getValue());
                    if (resolved.path("readOnly").asBoolean(false) || field.getValue().path("readOnly").asBoolean(false)) continue;
                    if (properties.size() >= HttpApiJsonBinding.MAX_FIELDS) {
                        if (draft.notes.add("fields")) draft.warnings.add("请求体字段超过 " + HttpApiJsonBinding.MAX_FIELDS + " 个，多出的字段已省略");
                        break;
                    }
                    properties.set(name, sanitize(root, field.getValue(), depth + 1, refs, draft, nodes));
                }
                if (!properties.isEmpty()) out.set("properties", properties);
                ArrayNode required = json.createArrayNode();
                for (JsonNode name : schema.path("required")) if (name.isTextual() && properties.has(name.asText())) required.add(name.asText());
                if (!required.isEmpty()) out.set("required", required);
                JsonNode additional = schema.path("additionalProperties");
                if (additional.isBoolean()) out.put("additionalProperties", additional.asBoolean());
                else if (additional.isObject()) out.set("additionalProperties", sanitize(root, additional, depth + 1, refs, draft, nodes));
            }
            default -> { }
        }
        return out;
    }

    private JsonNode mergeAllOf(JsonNode root, JsonNode schema, Set<String> refs) {
        ObjectNode merged = ((ObjectNode) schema).deepCopy();
        merged.remove("allOf");
        ObjectNode properties = merged.path("properties").isObject() ? (ObjectNode) merged.get("properties") : json.createObjectNode();
        ArrayNode required = merged.path("required").isArray() ? (ArrayNode) merged.get("required") : json.createArrayNode();
        for (JsonNode part : schema.path("allOf")) {
            JsonNode resolved = resolve(root, part);
            if (resolved.path("allOf").isArray()) resolved = mergeAllOf(root, resolved, refs);
            resolved.path("properties").fields().forEachRemaining(field -> { if (!properties.has(field.getKey())) properties.set(field.getKey(), field.getValue()); });
            for (JsonNode name : resolved.path("required")) required.add(name);
            if (!merged.has("description") && resolved.has("description")) merged.set("description", resolved.get("description"));
        }
        merged.put("type", "object");
        merged.set("properties", properties);
        merged.set("required", required);
        return merged;
    }

    private static void copyNonNegative(JsonNode from, ObjectNode to, String... keys) {
        for (String key : keys) if (from.path(key).canConvertToInt() && from.path(key).asInt() >= 0) to.put(key, from.path(key).asInt());
    }

    /* ------------------------------------------------------------------ building */

    static final class Draft {
        final String method;
        String path;
        String summary;
        String description;
        String host;
        boolean deprecated;
        String sideEffect;
        String responseType = "object";
        String unsupported;
        String headerKey;
        ManualHttpApiService.Authentication auth;
        ManualHttpApiService.Authentication authFromHeader;
        JsonNode bodySchema;
        String bodyContentType;
        boolean bodyRequired;
        final List<String> groups = new ArrayList<>();
        final List<ManualHttpApiService.Parameter> parameters = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final Set<String> notes = new HashSet<>();
        final Set<String> secretNotes = new HashSet<>();

        Draft(String method) { this.method = method; }

        void body(JsonNode schema, String contentType, boolean required) {
            String reason = HttpApiJsonBinding.unsupportedReason(schema);
            if (reason != null) {
                warnings.add("请求体结构过于复杂（" + reason + "），已简化为任意对象");
                schema = JsonNodeFactory.instance.objectNode().put("type", "object");
            }
            this.bodySchema = schema;
            this.bodyContentType = contentType;
            this.bodyRequired = required;
        }
    }

    final class Builder {
        private final String prefix;
        final List<Candidate> candidates = new ArrayList<>();
        final Set<String> keys = new HashSet<>();
        int skipped;
        boolean truncated;

        Builder(String prefix) {
            String value = prefix == null ? "" : prefix.trim();
            if (!value.isEmpty() && !value.startsWith("/")) value = "/" + value;
            while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            this.prefix = value;
        }

        boolean full() {
            if (candidates.size() >= MAX_CANDIDATES) { truncated = true; return true; }
            return false;
        }

        String path(String raw) {
            String value = raw == null ? "" : raw.trim();
            value = value.replaceFirst("(?i)^https?://[^/?#]*", "");
            value = value.split("[?#]")[0];
            value = value.replaceAll("\\{\\{\\s*([A-Za-z][A-Za-z0-9_.-]*)\\s*}}", "{$1}");
            value = value.replaceAll("/:([A-Za-z][A-Za-z0-9_]*)", "/{$1}");
            if (!value.startsWith("/")) value = "/" + value;
            value = value.replaceAll("/+", "/");
            if (!prefix.isEmpty() && (value.equals(prefix) || value.startsWith(prefix + "/"))) {
                value = value.substring(prefix.length());
                if (value.isEmpty()) value = "/";
            }
            if (value.length() > 1 && value.endsWith("/")) value = value.substring(0, value.length() - 1);
            return value;
        }

        void add(Draft draft) {
            if (full()) return;
            String key = draft.method + " " + draft.path;
            String unsupported = draft.unsupported;
            if (!keys.add(key)) unsupported = "文档里有重复的同一接口，只导入第一个";
            List<ManualHttpApiService.Parameter> parameters = dedupe(draft.parameters);
            String sideEffect = draft.sideEffect != null ? draft.sideEffect : sideEffect(draft);
            if ("READ_ONLY".equals(sideEffect) && !"GET".equals(draft.method)) draft.warnings.add("按名称推测是查询接口（只读），请核对");
            ManualHttpApiService.RequestBody body = draft.bodySchema == null ? null
                    : new ManualHttpApiService.RequestBody(draft.bodyContentType, draft.bodyRequired, draft.bodySchema);
            ManualHttpApiService.Authentication auth = draft.auth != null ? draft.auth
                    : draft.authFromHeader != null ? draft.authFromHeader : new ManualHttpApiService.Authentication("UNKNOWN", null);
            List<String> groups = draft.groups.isEmpty() ? List.of() : List.of(trim(draft.groups.get(0), 64));
            ManualHttpApiService.Request request = new ManualHttpApiService.Request(draft.method, draft.path, sideEffect, null,
                    parameters, body, draft.responseType, auth, groups, draft.summary, draft.description);
            candidates.add(new Candidate(key, request, draft.host, draft.deprecated, List.copyOf(draft.warnings), unsupported, false));
        }

        private String sideEffect(Draft draft) {
            return switch (draft.method) {
                case "GET" -> "READ_ONLY";
                case "DELETE" -> "IRREVERSIBLE";
                case "PUT" -> "IDEMPOTENT_WRITE";
                case "PATCH" -> "WRITE";
                default -> {
                    // Only POST: many enterprise systems query with POST /xxx/page or /xxx/list.
                    String last = draft.path.substring(draft.path.lastIndexOf('/') + 1);
                    String hint = "/" + last + " " + (draft.summary == null ? "" : draft.summary);
                    yield READ_HINT.matcher(hint).find() && !hint.matches("(?s).*(新增|创建|修改|更新|删除|保存|提交|审核|导入|create|update|delete|save|submit).*")
                            ? "READ_ONLY" : "WRITE";
                }
            };
        }

        private List<ManualHttpApiService.Parameter> dedupe(List<ManualHttpApiService.Parameter> raw) {
            Map<String, ManualHttpApiService.Parameter> result = new LinkedHashMap<>();
            for (ManualHttpApiService.Parameter parameter : raw) result.putIfAbsent(parameter.location() + ":" + parameter.name(), parameter);
            List<ManualHttpApiService.Parameter> list = new ArrayList<>(result.values());
            return list.size() > 50 ? list.subList(0, 50) : list;
        }

        private String trim(String value, int max) {
            return value.length() > max ? value.substring(0, max) : value;
        }
    }

    /* ------------------------------------------------------------------ API */

    public record PreviewRequest(String content, String fileName, String url, String authorization, String stripPrefix) { }

    /**
     * One operation found in the document. {@code request} is ready for the batch endpoint (origin is set by
     * the caller); {@code unsupported} explains why it cannot be saved; {@code existing} marks a same
     * method + route already in this project (saving updates its manual source).
     */
    public record Candidate(String key, ManualHttpApiService.Request request, String host, boolean deprecated,
                            List<String> warnings, String unsupported, boolean existing) { }

    public record Preview(String format, String title, String version, String fetchedFrom, String stripPrefix,
                          int skipped, List<String> warnings, List<Candidate> candidates) { }
}

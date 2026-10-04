package com.enterprise.ai.text.tooling.scanner.openapi;

import com.enterprise.ai.text.tooling.scanner.ScanOptions;
import com.enterprise.ai.text.tooling.scanner.manifest.HttpApiOperation;
import com.enterprise.ai.text.tooling.scanner.manifest.ProjectMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secret-free, deliberately conservative OpenAPI 3.x operation inventory.
 *
 * <p>The legacy OpenAPI scanner still creates {@code ToolDefinition} rows for compatibility.
 * This extractor is separate so a Tool name, operationId, description, server URL, example, or
 * default value can never become an HTTP API identity or source contract fact. Any operation the
 * small static model cannot prove is omitted and makes the inventory partial; Capability can then
 * observe reported facts but cannot treat the response as a source-removal list.</p>
 */
final class OpenApiHttpApiInventoryExtractor {

    private static final int MAX_REF_DEPTH = 32;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> HTTP_METHODS = List.of(
            "delete", "get", "head", "options", "patch", "post", "put", "trace");
    private static final Set<String> PATH_ITEM_FIELDS = Set.of(
            "$ref", "description", "parameters", "servers", "summary");
    private static final Set<String> UNSUPPORTED_SCHEMA_KEYWORDS = Set.of(
            "allof", "anyof", "oneof", "not", "if", "then", "else", "contains", "prefixitems",
            "patternproperties", "propertynames", "dependentrequired", "dependentschemas",
            "unevaluatedproperties", "unevaluateditems");
    private static final Set<String> SAFE_SCHEMA_FIELDS = Set.of(
            "$ref", "type", "format", "pattern", "minimum", "maximum", "exclusiveMinimum",
            "exclusiveMaximum", "multipleOf", "minLength", "maxLength", "minItems", "maxItems",
            "minProperties", "maxProperties", "nullable", "readOnly", "writeOnly", "uniqueItems",
            "enum", "const", "properties", "required", "items", "additionalProperties",
            "title", "description", "deprecated", "default", "example", "examples");
    private static final Set<String> OPERATION_FIELDS = Set.of(
            "tags", "summary", "description", "externalDocs", "operationId", "parameters", "requestBody",
            "responses", "deprecated", "security", "servers");
    private static final Set<String> PARAMETER_FIELDS = Set.of(
            "name", "in", "description", "required", "deprecated", "allowEmptyValue", "style", "explode",
            "allowReserved", "schema", "example", "examples", "content");
    private static final Set<String> REQUEST_BODY_FIELDS = Set.of("description", "content", "required");
    private static final Set<String> RESPONSE_FIELDS = Set.of("description", "headers", "content", "links");
    private static final Set<String> MEDIA_TYPE_FIELDS = Set.of("schema", "example", "examples", "encoding");
    private static final Set<String> SERVER_FIELDS = Set.of("url", "description", "variables");
    private static final Set<String> ALLOWED_SCHEMA_TYPES = Set.of(
            "array", "boolean", "integer", "number", "object", "string");
    private static final Set<String> SENSITIVE_NAME_PARTS = Set.of(
            "api-key", "apikey", "authorization", "cookie", "credential", "password", "secret", "token");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^{}]+)}");
    private static final Pattern SENSITIVE_LITERAL = Pattern.compile(
            "(?i)(?:bearer\\s+|api[_-]?key|password|token|secret|credential)");

    Result extract(JsonNode root,
                   String relativeSpecPath,
                   ProjectMetadata project,
                   ScanOptions options) {
        if (!isOpenApi3(root) || root == null || !root.path("paths").isObject() || !validInfo(root.get("info"))) {
            return Result.partial();
        }
        // A document-level malformed server block is enough to make an otherwise empty document
        // unsafe as a removal inventory. Per-operation validation below additionally checks the
        // effective (operation > path > root) base path against the saved project scope.
        boolean complete = serverSyntaxSupported(root.get("servers"));
        List<HttpApiOperation> operations = new ArrayList<>();
        List<Map.Entry<String, JsonNode>> paths = sortedFields(root.path("paths"));
        for (Map.Entry<String, JsonNode> pathEntry : paths) {
            String endpointPath = pathEntry.getKey();
            if (!validEndpointPath(endpointPath)) {
                complete = false;
                continue;
            }
            Outcome<JsonNode> resolvedPathItem = resolveReference(pathEntry.getValue(), root,
                    new LinkedHashSet<>(), 0);
            if (!resolvedPathItem.supported() || !resolvedPathItem.value().isObject()) {
                complete = false;
                continue;
            }
            JsonNode pathItem = resolvedPathItem.value();
            for (Map.Entry<String, JsonNode> field : sortedFields(pathItem)) {
                String method = field.getKey().toLowerCase(Locale.ROOT);
                if (HTTP_METHODS.contains(method)) {
                    if (!isHttpMethodAllowed(method, options)) {
                        complete = false;
                        continue;
                    }
                    if (!field.getValue().isObject()) {
                        complete = false;
                        continue;
                    }
                    JsonNode operation = field.getValue();
                    if (Boolean.TRUE.equals(options == null ? null : options.getSkipDeprecated())
                            && operation.path("deprecated").asBoolean(false)) {
                        complete = false;
                        continue;
                    }
                    Outcome<HttpApiOperation> parsed = parseOperation(root, relativeSpecPath, project,
                            endpointPath, method, pathItem, operation);
                    if (!parsed.supported()) {
                        complete = false;
                        continue;
                    }
                    operations.add(parsed.value());
                    continue;
                }
                if (!PATH_ITEM_FIELDS.contains(method) && !method.startsWith("x-")) {
                    // OpenAPI path items have a fixed key set. An unknown non-extension key is
                    // often a method an older parser did not understand; never report full truth.
                    complete = false;
                }
            }
        }
        operations.sort(Comparator.comparing(HttpApiOperation::sourceKey));
        Set<String> sourceKeys = new HashSet<>();
        for (HttpApiOperation operation : operations) {
            if (!sourceKeys.add(operation.sourceKey())) {
                return Result.partial();
            }
        }
        return new Result(List.copyOf(operations), complete);
    }

    private Outcome<HttpApiOperation> parseOperation(JsonNode root,
                                                      String relativeSpecPath,
                                                      ProjectMetadata project,
                                                      String endpointPath,
                                                      String method,
                                                      JsonNode pathItem,
                                                      JsonNode operation) {
        if (!hasOnlyKnownFields(operation, OPERATION_FIELDS)
                || operation.has("callbacks")
                || !serversCompatible(root, pathItem, operation, project)) {
            return Outcome.unsupported();
        }
        Outcome<List<HttpApiOperation.Parameter>> parameters = parseParameters(root, endpointPath, pathItem, operation);
        Outcome<ParsedRequestBody> requestBody = parseRequestBody(root, operation);
        Outcome<List<HttpApiOperation.Response>> responses = parseResponses(root, operation);
        Outcome<Authentication> authentication = parseAuthentication(root, operation);
        if (!parameters.supported() || !requestBody.supported() || !responses.supported()
                || !authentication.supported()) {
            return Outcome.unsupported();
        }
        List<String> consumes = requestBody.value() == null ? List.of() : requestBody.value().contentTypes();
        List<String> produces = responses.value().stream()
                .flatMap(response -> response.contentTypes().stream()).distinct().sorted().toList();
        String pointer = "/paths/" + encodePointer(endpointPath) + "/" + method;
        String sourceLocation = relativeSpecPath + "#" + pointer;
        HttpApiOperation provisional = new HttpApiOperation(
                "openapi:" + relativeSpecPath + ":" + sha256(pointer),
                sourceLocation,
                null,
                method.toUpperCase(Locale.ROOT),
                normalizeContextPath(project == null ? null : project.contextPath()),
                endpointPath,
                consumes,
                produces,
                List.of(),
                parameters.value(),
                requestBody.value() == null ? null : requestBody.value().asManifest(),
                responses.value(),
                authentication.value().state(),
                authentication.value().schemes(),
                authentication.value().requiredHeaderNames(),
                readOnly(method) ? "READ_ONLY" : "WRITE");
        return Outcome.supported(withRevision(provisional));
    }

    private Outcome<List<HttpApiOperation.Parameter>> parseParameters(JsonNode root,
                                                                       String endpointPath,
                                                                       JsonNode pathItem,
                                                                       JsonNode operation) {
        Outcome<Map<String, HttpApiOperation.Parameter>> pathParameters = parseParameterLayer(
                root, pathItem.get("parameters"));
        Outcome<Map<String, HttpApiOperation.Parameter>> operationParameters = parseParameterLayer(
                root, operation.get("parameters"));
        if (!pathParameters.supported() || !operationParameters.supported()) {
            return Outcome.unsupported();
        }
        Map<String, HttpApiOperation.Parameter> effective = new HashMap<>(pathParameters.value());
        effective.putAll(operationParameters.value());
        Set<String> routeVariables = pathVariables(endpointPath);
        if (routeVariables == null) {
            return Outcome.unsupported();
        }
        for (HttpApiOperation.Parameter parameter : effective.values()) {
            if ("PATH".equals(parameter.location())
                    && (!Boolean.TRUE.equals(parameter.required()) || !routeVariables.contains(parameter.name()))) {
                return Outcome.unsupported();
            }
        }
        for (String routeVariable : routeVariables) {
            if (!effective.containsKey("PATH:" + routeVariable)) {
                return Outcome.unsupported();
            }
        }
        List<HttpApiOperation.Parameter> values = new ArrayList<>(effective.values());
        values.sort(Comparator.comparing(HttpApiOperation.Parameter::location)
                .thenComparing(HttpApiOperation.Parameter::name));
        return Outcome.supported(List.copyOf(values));
    }

    private Outcome<Map<String, HttpApiOperation.Parameter>> parseParameterLayer(JsonNode root,
                                                                                    JsonNode rawParameters) {
        if (rawParameters == null || rawParameters.isMissingNode() || rawParameters.isNull()) {
            return Outcome.supported(Map.of());
        }
        if (!rawParameters.isArray()) {
            return Outcome.unsupported();
        }
        Map<String, HttpApiOperation.Parameter> values = new HashMap<>();
        for (JsonNode raw : rawParameters) {
            Outcome<HttpApiOperation.Parameter> parsed = parseParameter(root, raw);
            if (!parsed.supported()) {
                return Outcome.unsupported();
            }
            HttpApiOperation.Parameter parameter = parsed.value();
            String key = parameter.location() + ":" + ("HEADER".equals(parameter.location())
                    ? parameter.name().toLowerCase(Locale.ROOT) : parameter.name());
            if (values.putIfAbsent(key, parameter) != null) {
                return Outcome.unsupported();
            }
        }
        return Outcome.supported(Map.copyOf(values));
    }

    private Outcome<HttpApiOperation.Parameter> parseParameter(JsonNode root, JsonNode raw) {
        Outcome<JsonNode> resolved = resolveReference(raw, root, new LinkedHashSet<>(), 0);
        if (!resolved.supported() || !resolved.value().isObject()) {
            return Outcome.unsupported();
        }
        JsonNode parameter = resolved.value();
        String name = textual(parameter.get("name"));
        String location = parameterLocation(textual(parameter.get("in")));
        if (!validIdentifier(name) || location == null || (!"PATH".equals(location) && !validParameterName(name))) {
            return Outcome.unsupported();
        }
        if (!hasOnlyKnownFields(parameter, PARAMETER_FIELDS)
                || !parameterSerializationSupported(parameter, location)) {
            return Outcome.unsupported();
        }
        Outcome<Boolean> required = booleanValue(parameter.get("required"), false);
        if (!required.supported() || (parameter.has("schema") && parameter.has("content"))) {
            return Outcome.unsupported();
        }
        Outcome<MediaSchema> schema;
        if (parameter.has("content")) {
            schema = parseMediaSchema(root, parameter.get("content"), sensitiveName(name));
        } else if (parameter.has("schema")) {
            Outcome<JsonNode> parsed = schema(root, parameter.get("schema"), sensitiveName(name),
                    new LinkedHashSet<>(), 0);
            schema = parsed.supported() ? Outcome.supported(new MediaSchema(parsed.value(), List.of()))
                    : Outcome.unsupported();
        } else {
            return Outcome.unsupported();
        }
        if (!schema.supported()) {
            return Outcome.unsupported();
        }
        return Outcome.supported(new HttpApiOperation.Parameter(name, location, required.value(),
                schema.value().schema(), schema.value().contentTypes()));
    }

    private Outcome<ParsedRequestBody> parseRequestBody(JsonNode root, JsonNode operation) {
        JsonNode raw = operation.get("requestBody");
        if (raw == null || raw.isNull()) {
            return Outcome.supported(null);
        }
        Outcome<JsonNode> resolved = resolveReference(raw, root, new LinkedHashSet<>(), 0);
        if (!resolved.supported() || !resolved.value().isObject()) {
            return Outcome.unsupported();
        }
        JsonNode body = resolved.value();
        if (!hasOnlyKnownFields(body, REQUEST_BODY_FIELDS)) {
            return Outcome.unsupported();
        }
        Outcome<Boolean> required = booleanValue(body.get("required"), false);
        Outcome<MediaSchema> media = parseMediaSchema(root, body.get("content"), false);
        if (!required.supported() || !media.supported()) {
            return Outcome.unsupported();
        }
        return Outcome.supported(new ParsedRequestBody(required.value(), media.value().schema(), media.value().contentTypes()));
    }

    private Outcome<List<HttpApiOperation.Response>> parseResponses(JsonNode root, JsonNode operation) {
        JsonNode rawResponses = operation.get("responses");
        if (rawResponses == null || !rawResponses.isObject() || rawResponses.isEmpty()) {
            return Outcome.unsupported();
        }
        List<HttpApiOperation.Response> responses = new ArrayList<>();
        Set<String> statuses = new HashSet<>();
        for (Map.Entry<String, JsonNode> entry : sortedFields(rawResponses)) {
            String status = responseStatus(entry.getKey());
            if (status == null || !statuses.add(status)) {
                return Outcome.unsupported();
            }
            Outcome<JsonNode> resolved = resolveReference(entry.getValue(), root, new LinkedHashSet<>(), 0);
            if (!resolved.supported() || !resolved.value().isObject()) {
                return Outcome.unsupported();
            }
            JsonNode response = resolved.value();
            if (!hasOnlyKnownFields(response, RESPONSE_FIELDS)
                    || response.has("headers") || response.has("links")) {
                // Response header/link contracts have no lossless representation in the current
                // HTTP source wire, so they cannot be declared a complete comparable response.
                return Outcome.unsupported();
            }
            Outcome<MediaSchema> media = response.has("content")
                    ? parseMediaSchema(root, response.get("content"), false)
                    : Outcome.supported(new MediaSchema(null, List.of()));
            if (!media.supported()) {
                return Outcome.unsupported();
            }
            responses.add(new HttpApiOperation.Response(status, media.value().schema(), media.value().contentTypes()));
        }
        responses.sort(Comparator.comparing(HttpApiOperation.Response::status));
        return Outcome.supported(List.copyOf(responses));
    }

    private Outcome<Authentication> parseAuthentication(JsonNode root, JsonNode operation) {
        JsonNode security = operation.has("security") ? operation.get("security") : root.get("security");
        if (security == null || security.isNull() || security.isMissingNode()) {
            return Outcome.supported(new Authentication("UNKNOWN", List.of(), List.of()));
        }
        if (!security.isArray()) {
            return Outcome.unsupported();
        }
        if (security.isEmpty()) {
            return Outcome.supported(new Authentication("NONE", List.of(), List.of()));
        }
        // An array is OR. A requirement object is AND, but the current wire stores scheme names
        // and required headers in separate lists, losing each scheme-to-header association when
        // more than one scheme appears. Only a single scheme can be compared without ambiguity.
        if (security.size() != 1 || !security.get(0).isObject() || security.get(0).size() != 1) {
            return Outcome.unsupported();
        }
        List<String> schemes = new ArrayList<>();
        List<String> headers = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : sortedFields(security.get(0))) {
            String schemeName = entry.getKey();
            if (!validIdentifier(schemeName) || !entry.getValue().isArray() || !entry.getValue().isEmpty()) {
                // Scope-bearing OAuth/OpenID requirements require a richer model than the current contract.
                return Outcome.unsupported();
            }
            JsonNode rawScheme = root.path("components").path("securitySchemes").get(schemeName);
            Outcome<JsonNode> resolved = resolveReference(rawScheme, root, new LinkedHashSet<>(), 0);
            if (!resolved.supported() || !resolved.value().isObject()) {
                return Outcome.unsupported();
            }
            JsonNode scheme = resolved.value();
            String type = textual(scheme.get("type"));
            if (type == null) {
                return Outcome.unsupported();
            }
            switch (type.toLowerCase(Locale.ROOT)) {
                case "http" -> {
                    if (!hasOnlyKnownFields(scheme, Set.of("type", "scheme", "bearerFormat", "description"))) {
                        return Outcome.unsupported();
                    }
                    String mechanism = textual(scheme.get("scheme"));
                    if (!validIdentifier(mechanism)) {
                        return Outcome.unsupported();
                    }
                    schemes.add("HTTP:" + mechanism.toLowerCase(Locale.ROOT) + ":" + schemeName);
                    headers.add("Authorization");
                }
                case "apikey" -> {
                    if (!hasOnlyKnownFields(scheme, Set.of("type", "name", "in", "description"))) {
                        return Outcome.unsupported();
                    }
                    String in = textual(scheme.get("in"));
                    String headerName = textual(scheme.get("name"));
                    if (!"header".equalsIgnoreCase(in) || !validHeaderName(headerName)) {
                        return Outcome.unsupported();
                    }
                    schemes.add("API_KEY:" + schemeName);
                    headers.add(headerName);
                }
                case "oauth2" -> {
                    if (!hasOnlyKnownFields(scheme, Set.of("type", "flows", "description"))
                            || !scheme.path("flows").isObject() || scheme.path("flows").isEmpty()) {
                        return Outcome.unsupported();
                    }
                    schemes.add("OAUTH2:" + schemeName);
                }
                case "openidconnect" -> {
                    if (!hasOnlyKnownFields(scheme, Set.of("type", "openIdConnectUrl", "description"))
                            || textual(scheme.get("openIdConnectUrl")) == null) {
                        return Outcome.unsupported();
                    }
                    schemes.add("OPENID_CONNECT:" + schemeName);
                }
                default -> {
                    return Outcome.unsupported();
                }
            }
        }
        return Outcome.supported(new Authentication("REQUIRED", distinctSorted(schemes), distinctSorted(headers)));
    }

    /**
     * The persisted scan project owns the context path. A server block may be used only when its
     * static path is compatible with that saved scope; its host, URL text, variables, and
     * descriptions are never copied into an API fact or hash.
     */
    private boolean serversCompatible(JsonNode root,
                                      JsonNode pathItem,
                                      JsonNode operation,
                                      ProjectMetadata project) {
        JsonNode servers = operation.has("servers") ? operation.get("servers")
                : (pathItem.has("servers") ? pathItem.get("servers") : root.get("servers"));
        if (servers == null || servers.isMissingNode() || servers.isNull()) {
            return true;
        }
        if (!serverSyntaxSupported(servers)) {
            return false;
        }
        String expectedContextPath = normalizeContextPath(project == null ? null : project.contextPath());
        for (JsonNode server : servers) {
            String serverPath = staticServerPath(textual(server.get("url")));
            if (serverPath == null || (!serverPath.isEmpty() && !serverPath.equals(expectedContextPath))) {
                return false;
            }
        }
        return true;
    }

    private boolean serverSyntaxSupported(JsonNode servers) {
        if (servers == null || servers.isMissingNode() || servers.isNull()) {
            return true;
        }
        if (!servers.isArray() || servers.isEmpty()) {
            return false;
        }
        for (JsonNode server : servers) {
            if (!server.isObject() || !hasOnlyKnownFields(server, SERVER_FIELDS)
                    || textual(server.get("url")) == null) {
                return false;
            }
            JsonNode variables = server.get("variables");
            if (variables != null && !variables.isObject()) {
                return false;
            }
            if (staticServerPath(textual(server.get("url"))) == null) {
                return false;
            }
        }
        return true;
    }

    private String staticServerPath(String rawUrl) {
        if (rawUrl == null || rawUrl.length() > 2048 || rawUrl.contains("{") || rawUrl.contains("}")
                || rawUrl.contains("\r") || rawUrl.contains("\n")) {
            return null;
        }
        try {
            URI uri = URI.create(rawUrl);
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getScheme() != null && !"http".equalsIgnoreCase(uri.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme()))) {
                return null;
            }
            String path = uri.getPath();
            if (path == null || path.isBlank() || "/".equals(path)) {
                return "";
            }
            if (!path.startsWith("/")) {
                return null;
            }
            return normalizeContextPath(path);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean parameterSerializationSupported(JsonNode parameter, String location) {
        String defaultStyle = switch (location) {
            case "PATH", "HEADER" -> "simple";
            case "QUERY", "COOKIE" -> "form";
            default -> null;
        };
        String style = parameter.has("style") ? textual(parameter.get("style")) : defaultStyle;
        if (!defaultStyle.equals(style)) {
            return false;
        }
        boolean defaultExplode = "form".equals(defaultStyle);
        JsonNode explode = parameter.get("explode");
        if (explode != null && (!explode.isBoolean() || explode.booleanValue() != defaultExplode)) {
            return false;
        }
        JsonNode allowReserved = parameter.get("allowReserved");
        if (allowReserved != null && (!allowReserved.isBoolean() || allowReserved.booleanValue())) {
            return false;
        }
        JsonNode allowEmptyValue = parameter.get("allowEmptyValue");
        return allowEmptyValue == null || (allowEmptyValue.isBoolean() && !allowEmptyValue.booleanValue());
    }

    private Outcome<MediaSchema> parseMediaSchema(JsonNode root, JsonNode rawContent, boolean sensitiveContext) {
        if (rawContent == null || !rawContent.isObject() || rawContent.isEmpty()) {
            return Outcome.unsupported();
        }
        JsonNode agreedSchema = null;
        List<String> contentTypes = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : sortedFields(rawContent)) {
            String mediaType = entry.getKey();
            if (!validMediaType(mediaType) || !hasOnlyKnownFields(entry.getValue(), MEDIA_TYPE_FIELDS)
                    || entry.getValue().has("encoding")) {
                // Multipart/form encoding is a request serialization contract, not a cosmetic
                // annotation. The current source wire cannot represent it losslessly.
                return Outcome.unsupported();
            }
            JsonNode rawSchema = entry.getValue().get("schema");
            Outcome<JsonNode> parsed = rawSchema == null
                    ? Outcome.supported(null)
                    : schema(root, rawSchema, sensitiveContext, new LinkedHashSet<>(), 0);
            if (!parsed.supported()) {
                return Outcome.unsupported();
            }
            if (agreedSchema != null && parsed.value() != null && !agreedSchema.equals(parsed.value())) {
                return Outcome.unsupported();
            }
            if ((agreedSchema == null) != (parsed.value() == null) && !contentTypes.isEmpty()) {
                return Outcome.unsupported();
            }
            agreedSchema = parsed.value();
            contentTypes.add(mediaType.trim().toLowerCase(Locale.ROOT));
        }
        return Outcome.supported(new MediaSchema(agreedSchema, distinctSorted(contentTypes)));
    }

    private Outcome<JsonNode> schema(JsonNode root,
                                     JsonNode raw,
                                     boolean sensitiveContext,
                                     Set<String> refStack,
                                     int depth) {
        if (depth > MAX_REF_DEPTH || raw == null || !raw.isObject()) {
            return Outcome.unsupported();
        }
        if (raw.has("$ref")) {
            Outcome<JsonNode> resolved = resolveReference(raw, root, refStack, depth);
            if (!resolved.supported() || resolved.value() == raw) {
                return Outcome.unsupported();
            }
            return schema(root, resolved.value(), sensitiveContext, refStack, depth + 1);
        }
        for (Map.Entry<String, JsonNode> field : sortedFields(raw)) {
            String normalizedKey = field.getKey().toLowerCase(Locale.ROOT);
            if (UNSUPPORTED_SCHEMA_KEYWORDS.contains(normalizedKey)
                    || !SAFE_SCHEMA_FIELDS.contains(field.getKey())) {
                return Outcome.unsupported();
            }
        }
        String declaredType = textual(raw.get("type"));
        if (raw.has("type") && (!ALLOWED_SCHEMA_TYPES.contains(declaredType == null ? "" : declaredType.toLowerCase(Locale.ROOT)))) {
            return Outcome.unsupported();
        }
        boolean hasProperties = raw.path("properties").isObject();
        boolean hasItems = raw.has("items");
        String type = declaredType == null ? (hasProperties ? "object" : hasItems ? "array" : null)
                : declaredType.toLowerCase(Locale.ROOT);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        if (type != null) {
            result.put("type", type);
        }
        if (!copySafeText(raw, result, "format") || !copySafeText(raw, result, "pattern")
                || !copySafeNumber(raw, result, "minimum", false)
                || !copySafeNumber(raw, result, "maximum", false)
                || !copySafeNumber(raw, result, "exclusiveMinimum", true)
                || !copySafeNumber(raw, result, "exclusiveMaximum", true)
                || !copySafeNumber(raw, result, "multipleOf", false)
                || !copySafeNumber(raw, result, "minLength", false)
                || !copySafeNumber(raw, result, "maxLength", false)
                || !copySafeNumber(raw, result, "minItems", false)
                || !copySafeNumber(raw, result, "maxItems", false)
                || !copySafeNumber(raw, result, "minProperties", false)
                || !copySafeNumber(raw, result, "maxProperties", false)
                || !copySafeBoolean(raw, result, "nullable")
                || !copySafeBoolean(raw, result, "readOnly")
                || !copySafeBoolean(raw, result, "writeOnly")
                || !copySafeBoolean(raw, result, "uniqueItems")) {
            return Outcome.unsupported();
        }
        if (raw.has("enum")) {
            if (sensitiveContext || !safeEnum(raw.get("enum"))) {
                return Outcome.unsupported();
            }
            result.set("enum", raw.get("enum").deepCopy());
        }
        if (raw.has("const")) {
            if (sensitiveContext || !safeLiteral(raw.get("const"))) {
                return Outcome.unsupported();
            }
            result.set("const", raw.get("const").deepCopy());
        }
        if (raw.has("properties")) {
            if (!"object".equals(type) || !raw.get("properties").isObject()) {
                return Outcome.unsupported();
            }
            ObjectNode properties = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> entry : sortedFields(raw.get("properties"))) {
                if (!validIdentifier(entry.getKey())) {
                    return Outcome.unsupported();
                }
                Outcome<JsonNode> child = schema(root, entry.getValue(),
                        sensitiveContext || sensitiveName(entry.getKey()), new LinkedHashSet<>(refStack), depth + 1);
                if (!child.supported()) {
                    return Outcome.unsupported();
                }
                properties.set(entry.getKey(), child.value());
            }
            result.set("properties", properties);
            Outcome<ArrayNode> required = requiredProperties(raw.get("required"), properties);
            if (!required.supported()) {
                return Outcome.unsupported();
            }
            if (!required.value().isEmpty()) {
                result.set("required", required.value());
            }
        } else if (raw.has("required")) {
            return Outcome.unsupported();
        }
        if (raw.has("items")) {
            if (!"array".equals(type)) {
                return Outcome.unsupported();
            }
            Outcome<JsonNode> items = schema(root, raw.get("items"), sensitiveContext,
                    new LinkedHashSet<>(refStack), depth + 1);
            if (!items.supported()) {
                return Outcome.unsupported();
            }
            result.set("items", items.value());
        }
        if (raw.has("additionalProperties")) {
            JsonNode additional = raw.get("additionalProperties");
            if (additional.isBoolean()) {
                result.set("additionalProperties", additional.deepCopy());
            } else {
                Outcome<JsonNode> parsed = schema(root, additional, sensitiveContext,
                        new LinkedHashSet<>(refStack), depth + 1);
                if (!parsed.supported()) {
                    return Outcome.unsupported();
                }
                result.set("additionalProperties", parsed.value());
            }
        }
        return Outcome.supported(result);
    }

    private Outcome<ArrayNode> requiredProperties(JsonNode raw, ObjectNode properties) {
        if (raw == null || raw.isMissingNode() || raw.isNull()) {
            return Outcome.supported(JsonNodeFactory.instance.arrayNode());
        }
        if (!raw.isArray()) {
            return Outcome.unsupported();
        }
        Set<String> names = new TreeSet<>();
        for (JsonNode item : raw) {
            if (!item.isTextual() || !properties.has(item.asText()) || !names.add(item.asText())) {
                return Outcome.unsupported();
            }
        }
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        names.stream().sorted().forEach(result::add);
        return Outcome.supported(result);
    }

    private Outcome<JsonNode> resolveReference(JsonNode raw,
                                                JsonNode root,
                                                Set<String> refStack,
                                                int depth) {
        if (depth > MAX_REF_DEPTH || raw == null || raw.isMissingNode() || raw.isNull()) {
            return Outcome.unsupported();
        }
        if (!raw.has("$ref")) {
            return Outcome.supported(raw);
        }
        if (!raw.get("$ref").isTextual() || raw.size() != 1) {
            return Outcome.unsupported();
        }
        String ref = raw.get("$ref").asText();
        if (!ref.startsWith("#/") || !refStack.add(ref)) {
            return Outcome.unsupported();
        }
        JsonNode target;
        try {
            target = root.at(ref.substring(1));
        } catch (IllegalArgumentException ignored) {
            return Outcome.unsupported();
        }
        if (target == null || target.isMissingNode() || target.isNull()) {
            return Outcome.unsupported();
        }
        return resolveReference(target, root, refStack, depth + 1);
    }

    private HttpApiOperation withRevision(HttpApiOperation operation) {
        ObjectNode evidence = JsonNodeFactory.instance.objectNode();
        evidence.put("method", operation.httpMethod());
        // Context path is trusted only from Capability's persisted scan project. It is not a
        // document fact and must not let a scanner response change source freshness evidence.
        evidence.put("endpointPath", operation.endpointPath());
        evidence.set("consumes", strings(operation.consumes()));
        evidence.set("produces", strings(operation.produces()));
        evidence.set("mappingConditions", JSON.valueToTree(operation.mappingConditions()));
        evidence.set("parameters", JSON.valueToTree(operation.parameters()));
        evidence.set("requestBody", JSON.valueToTree(operation.requestBody()));
        evidence.set("responses", JSON.valueToTree(operation.responses()));
        evidence.put("authenticationState", operation.authenticationState());
        evidence.set("authenticationSchemes", strings(operation.authenticationSchemes()));
        evidence.set("requiredHeaderNames", strings(operation.requiredHeaderNames()));
        evidence.put("sideEffect", operation.sideEffect());
        return new HttpApiOperation(operation.sourceKey(), operation.sourceLocation(),
                "scan:" + sha256(canonicalJson(evidence)), operation.httpMethod(), operation.contextPath(),
                operation.endpointPath(), operation.consumes(), operation.produces(), operation.mappingConditions(),
                operation.parameters(), operation.requestBody(), operation.responses(), operation.authenticationState(),
                operation.authenticationSchemes(), operation.requiredHeaderNames(), operation.sideEffect());
    }

    private ArrayNode strings(List<String> values) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        distinctSorted(values).forEach(result::add);
        return result;
    }

    private String canonicalJson(JsonNode raw) {
        try {
            return JSON.writeValueAsString(canonicalTree(raw));
        } catch (Exception failure) {
            throw new IllegalStateException("OpenAPI source facts cannot be canonicalized", failure);
        }
    }

    private JsonNode canonicalTree(JsonNode raw) {
        if (raw == null || raw.isNull() || raw.isValueNode()) {
            return raw == null ? JsonNodeFactory.instance.nullNode() : raw.deepCopy();
        }
        if (raw.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            raw.forEach(value -> result.add(canonicalTree(value)));
            return result;
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, JsonNode> entry : sortedFields(raw)) {
            result.set(entry.getKey(), canonicalTree(entry.getValue()));
        }
        return result;
    }

    private boolean isOpenApi3(JsonNode root) {
        String version = root == null ? null : textual(root.get("openapi"));
        return version != null && version.matches("3\\.\\d+(?:\\.\\d+)?(?:[-+][A-Za-z0-9.-]+)?");
    }

    private boolean validInfo(JsonNode info) {
        return info != null && info.isObject() && textual(info.get("title")) != null
                && textual(info.get("version")) != null;
    }

    private boolean isHttpMethodAllowed(String method, ScanOptions options) {
        if (options == null || options.getHttpMethodWhitelist() == null || options.getHttpMethodWhitelist().isEmpty()) {
            return true;
        }
        return options.getHttpMethodWhitelist().stream()
                .anyMatch(value -> method.equalsIgnoreCase(value == null ? "" : value.trim()));
    }

    private String parameterLocation(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "path" -> "PATH";
            case "query" -> "QUERY";
            case "header" -> "HEADER";
            case "cookie" -> "COOKIE";
            default -> null;
        };
    }

    private String responseStatus(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if ("DEFAULT".equals(value) || value.matches("[1-5](?:[0-9]{2}|XX)")) {
            return value;
        }
        return null;
    }

    private Set<String> pathVariables(String endpointPath) {
        Matcher matcher = PATH_VARIABLE.matcher(endpointPath);
        Set<String> values = new LinkedHashSet<>();
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() < lastEnd || !validIdentifier(matcher.group(1)) || !values.add(matcher.group(1))) {
                return null;
            }
            lastEnd = matcher.end();
        }
        String residual = PATH_VARIABLE.matcher(endpointPath).replaceAll("");
        return residual.contains("{") || residual.contains("}") ? null : values;
    }

    private boolean validEndpointPath(String value) {
        return value != null && value.startsWith("/") && value.length() <= 1024 && !value.contains("?")
                && !value.contains("#") && !value.contains("\r") && !value.contains("\n");
    }

    private boolean validIdentifier(String value) {
        return value != null && !value.isBlank() && value.length() <= 256 && !value.contains("=")
                && !value.contains(":") && !value.contains("\r") && !value.contains("\n");
    }

    private boolean validParameterName(String value) {
        return validIdentifier(value);
    }

    private boolean validHeaderName(String value) {
        return validIdentifier(value) && value.toLowerCase(Locale.ROOT)
                .matches("[!#$%&'*+.^_|~0-9a-z-]+");
    }

    private boolean validMediaType(String value) {
        return value != null && !value.isBlank() && value.length() <= 256 && !value.contains("\r") && !value.contains("\n");
    }

    private boolean readOnly(String method) {
        return Set.of("get", "head", "options", "trace").contains(method.toLowerCase(Locale.ROOT));
    }

    private boolean sensitiveName(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return SENSITIVE_NAME_PARTS.stream().anyMatch(normalized::contains);
    }

    private boolean safeEnum(JsonNode values) {
        if (!values.isArray() || values.isEmpty()) {
            return false;
        }
        for (JsonNode value : values) {
            if (!safeLiteral(value)) {
                return false;
            }
        }
        return true;
    }

    private boolean safeLiteral(JsonNode value) {
        if (value == null || !(value.isTextual() || value.isNumber() || value.isBoolean() || value.isNull())) {
            return false;
        }
        return !value.isTextual() || (value.asText().length() <= 1024
                && !SENSITIVE_LITERAL.matcher(value.asText()).find()
                && !value.asText().contains("\r") && !value.asText().contains("\n"));
    }

    private Outcome<Boolean> booleanValue(JsonNode value, boolean fallback) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return Outcome.supported(fallback);
        }
        return value.isBoolean() ? Outcome.supported(value.booleanValue()) : Outcome.unsupported();
    }

    private boolean copySafeText(JsonNode source, ObjectNode target, String key) {
        JsonNode value = source.get(key);
        if (value == null) {
            return true;
        }
        if (!value.isTextual() || value.asText().length() > 1024 || value.asText().contains("\r")
                || value.asText().contains("\n") || (SENSITIVE_LITERAL.matcher(value.asText()).find()
                && !("format".equals(key) && "password".equals(value.asText())))) {
            return false;
        }
        target.put(key, value.asText());
        return true;
    }

    private boolean copySafeNumber(JsonNode source, ObjectNode target, String key, boolean allowBoolean) {
        JsonNode value = source.get(key);
        if (value == null) {
            return true;
        }
        if (!value.isNumber() && !(allowBoolean && value.isBoolean())) {
            return false;
        }
        target.set(key, value.deepCopy());
        return true;
    }

    private boolean copySafeBoolean(JsonNode source, ObjectNode target, String key) {
        JsonNode value = source.get(key);
        if (value == null) {
            return true;
        }
        if (!value.isBoolean()) {
            return false;
        }
        target.set(key, value.deepCopy());
        return true;
    }

    private boolean hasOnlyKnownFields(JsonNode raw, Set<String> allowed) {
        if (raw == null || !raw.isObject()) {
            return false;
        }
        for (Map.Entry<String, JsonNode> field : sortedFields(raw)) {
            if (!allowed.contains(field.getKey()) && !field.getKey().startsWith("x-")) {
                return false;
            }
        }
        return true;
    }

    private String normalizeContextPath(String raw) {
        if (raw == null || raw.isBlank() || "/".equals(raw.trim())) {
            return "";
        }
        String value = raw.trim().replaceAll("/+", "/");
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        return value.length() > 1 && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String encodePointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private String textual(JsonNode value) {
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private List<Map.Entry<String, JsonNode>> sortedFields(JsonNode object) {
        if (object == null || !object.isObject()) {
            return List.of();
        }
        List<Map.Entry<String, JsonNode>> values = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> iterator = object.fields();
        iterator.forEachRemaining(values::add);
        values.sort(Map.Entry.comparingByKey());
        return values;
    }

    private List<String> distinctSorted(List<String> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().sorted().toList();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    record Result(List<HttpApiOperation> operations, boolean complete) {
        Result {
            operations = operations == null ? List.of() : List.copyOf(operations);
        }

        static Result partial() {
            return new Result(List.of(), false);
        }
    }

    private record MediaSchema(JsonNode schema, List<String> contentTypes) {
        MediaSchema {
            contentTypes = contentTypes == null ? List.of() : List.copyOf(contentTypes);
        }
    }

    private record ParsedRequestBody(boolean required, JsonNode schema, List<String> contentTypes) {
        ParsedRequestBody {
            contentTypes = contentTypes == null ? List.of() : List.copyOf(contentTypes);
        }

        HttpApiOperation.RequestBody asManifest() {
            return new HttpApiOperation.RequestBody("BODY", required, schema, contentTypes);
        }
    }

    private record Authentication(String state, List<String> schemes, List<String> requiredHeaderNames) {
        Authentication {
            schemes = schemes == null ? List.of() : List.copyOf(schemes);
            requiredHeaderNames = requiredHeaderNames == null ? List.of() : List.copyOf(requiredHeaderNames);
        }
    }

    private record Outcome<T>(T value, boolean supported) {
        static <T> Outcome<T> supported(T value) {
            return new Outcome<>(value, true);
        }

        static <T> Outcome<T> unsupported() {
            return new Outcome<>(null, false);
        }
    }
}

package com.enterprise.ai.text.tooling.scanner.openapi;

import com.enterprise.ai.text.tooling.scanner.manifest.ParameterLocation;
import com.enterprise.ai.text.tooling.scanner.manifest.ProjectMetadata;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolDefinition;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolManifest;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolParameterDefinition;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolSource;
import com.enterprise.ai.text.tooling.scanner.ScanOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 基于 OpenAPI/Swagger 文档生成运行时可消费的扫描结果。
 */
public class OpenApiToolManifestScanner {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final List<String> HTTP_METHODS = List.of("get", "post", "put", "delete", "patch");

    public ToolManifest scan(Path specPath, ProjectMetadata projectMetadata) {
        return scan(specPath, projectMetadata, null, null);
    }

    public ToolManifest scan(Path specPath, ProjectMetadata projectMetadata, ScanOptions options, Long incrementalSinceEpochMs) {
        if (incrementalSinceEpochMs != null && incrementalSinceEpochMs > 0
                && options != null
                && options.getIncrementalMode() != null
                && (ScanOptions.MODE_MTIME.equalsIgnoreCase(options.getIncrementalMode())
                || "GIT_DIFF".equalsIgnoreCase(options.getIncrementalMode()))) {
            try {
                if (Files.getLastModifiedTime(specPath).toMillis() <= incrementalSinceEpochMs) {
                    return new ToolManifest(projectMetadata, List.of());
                }
            } catch (Exception ignored) {
            }
        }
        JsonNode root = readSpec(specPath);
        JsonNode paths = root.path("paths");
        if (!paths.isObject()) {
            throw new IllegalArgumentException("OpenAPI spec does not contain paths: " + specPath);
        }

        ProjectMetadata effectiveProject = normalizeProject(projectMetadata, root);
        List<ToolDefinition> tools = new ArrayList<>();
        boolean skipDep = options != null && Boolean.TRUE.equals(options.getSkipDeprecated());

        Iterator<Map.Entry<String, JsonNode>> pathFields = paths.fields();
        while (pathFields.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathFields.next();
            String apiPath = pathEntry.getKey();
            JsonNode pathItem = pathEntry.getValue();

            for (String method : HTTP_METHODS) {
                if (!isHttpMethodAllowedByOptions(method, options)) {
                    continue;
                }
                JsonNode operation = pathItem.get(method);
                if (operation != null && operation.isObject()) {
                    if (skipDep && operation.path("deprecated").asBoolean(false)) {
                        continue;
                    }
                    tools.add(toToolDefinition(specPath, effectiveProject, apiPath, method, root, pathItem, operation));
                }
            }
        }

        ToolManifest manifest = new ToolManifest(effectiveProject, tools);
        manifest.validate();
        return manifest;
    }

    private static boolean isHttpMethodAllowedByOptions(String method, ScanOptions options) {
        if (options == null || options.getHttpMethodWhitelist() == null
                || options.getHttpMethodWhitelist().isEmpty()) {
            return true;
        }
        for (String w : options.getHttpMethodWhitelist()) {
            if (method != null && method.equalsIgnoreCase(w == null ? "" : w.trim())) {
                return true;
            }
        }
        return false;
    }

    private ToolDefinition toToolDefinition(
            Path specPath,
            ProjectMetadata project,
            String apiPath,
            String method,
            JsonNode docRoot,
            JsonNode pathItem,
            JsonNode operation
    ) {
        List<ToolParameterDefinition> parameters = parseParameters(pathItem, operation);
        String requestBodyType = extractRequestBodyType(operation);
        if (requestBodyType != null) {
            JsonNode bodySchema = findJsonSchema(operation.path("requestBody").path("content"));
            List<ToolParameterDefinition> bodyChildren = openApiSchemaToToolParameters(
                    bodySchema, docRoot, ParameterLocation.BODY, 0);
            parameters.add(new ToolParameterDefinition(
                    "body_json",
                    "json",
                    "JSON 请求体，对应 " + requestBodyType,
                    operation.path("requestBody").path("required").asBoolean(false),
                    ParameterLocation.BODY,
                    bodyChildren
            ));
        }

        JsonNode responseSchema = findFirst2xxResponseSchema(operation.path("responses"));
        String responseType = extractResponseType(operation);
        if (responseSchema != null && !responseSchema.isMissingNode()) {
            List<ToolParameterDefinition> responseChildren = openApiSchemaToToolParameters(
                    responseSchema, docRoot, ParameterLocation.RESPONSE, 0);
            if (!responseChildren.isEmpty()) {
                String rt = firstNonBlank(schemaTypeLabel(responseSchema), responseType, "object");
                parameters.add(new ToolParameterDefinition(
                        "返回值",
                        rt,
                        "HTTP 2xx 响应体",
                        false,
                        ParameterLocation.RESPONSE,
                        responseChildren
                ));
            }
        }

        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        return new ToolDefinition(
                resolveToolName(apiPath, operation, normalizedMethod),
                resolveDescription(operation),
                normalizedMethod,
                apiPath,
                normalizedMethod + " " + joinPath(project.contextPath(), apiPath),
                parameters,
                requestBodyType,
                responseType,
                new ToolSource("openapi", specPath.getFileName() + "#/paths/" + encodeJsonPointer(apiPath) + "/" + method)
        );
    }

    private List<ToolParameterDefinition> parseParameters(JsonNode pathItem, JsonNode operation) {
        Map<String, ToolParameterDefinition> parameters = new LinkedHashMap<>();
        collectParameters(parameters, pathItem.path("parameters"));
        collectParameters(parameters, operation.path("parameters"));
        return new ArrayList<>(parameters.values());
    }

    private void collectParameters(Map<String, ToolParameterDefinition> parameters, JsonNode parameterArray) {
        if (!parameterArray.isArray()) {
            return;
        }

        for (JsonNode parameter : parameterArray) {
            ParameterLocation location = toLocation(parameter.path("in").asText());
            if (location == null) {
                continue;
            }

            ToolParameterDefinition definition = new ToolParameterDefinition(
                    parameter.path("name").asText(),
                    extractSchemaType(parameter.path("schema")),
                    firstNonBlank(parameter.path("description").asText(null), parameter.path("name").asText()),
                    parameter.path("required").asBoolean(false),
                    location
            );
            parameters.put(definition.location() + ":" + definition.name(), definition);
        }
    }

    private String extractRequestBodyType(JsonNode operation) {
        JsonNode requestBodySchema = findJsonSchema(operation.path("requestBody").path("content"));
        return schemaTypeLabel(requestBodySchema);
    }

    private String extractResponseType(JsonNode operation) {
        JsonNode responses = operation.path("responses");
        if (!responses.isObject()) {
            return null;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = responses.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getKey().startsWith("2")) {
                continue;
            }

            JsonNode schema = findJsonSchema(entry.getValue().path("content"));
            String responseType = schemaTypeLabel(schema);
            if (responseType != null) {
                return responseType;
            }

            String description = entry.getValue().path("description").asText(null);
            if (description != null && !description.isBlank()) {
                return description;
            }
        }
        return null;
    }

    private JsonNode findJsonSchema(JsonNode contentNode) {
        if (!contentNode.isObject()) {
            return null;
        }

        JsonNode jsonContent = contentNode.get("application/json");
        if (jsonContent != null) {
            return jsonContent.path("schema");
        }

        Iterator<JsonNode> contents = contentNode.elements();
        while (contents.hasNext()) {
            JsonNode content = contents.next();
            JsonNode schema = content.path("schema");
            if (!schema.isMissingNode()) {
                return schema;
            }
        }
        return null;
    }

    /**
     * 解析 $ref（含链式），在 docRoot 上按 JSON Pointer 定位。
     */
    private JsonNode resolveSchemaRefs(JsonNode schema, JsonNode docRoot, Set<String> refStack) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return null;
        }
        JsonNode refNode = schema.get("$ref");
        if (refNode == null || !refNode.isTextual()) {
            return schema;
        }
        String ref = refNode.asText();
        if (!refStack.add(ref)) {
            return null;
        }
        String pointer = ref.startsWith("#") ? ref.substring(1) : ref;
        JsonNode target = docRoot.at(pointer);
        if (target == null || target.isMissingNode() || target.isNull()) {
            return schema;
        }
        return resolveSchemaRefs(target, docRoot, refStack);
    }

    private JsonNode findFirst2xxResponseSchema(JsonNode responses) {
        if (responses == null || !responses.isObject()) {
            return null;
        }
        Iterator<Map.Entry<String, JsonNode>> it = responses.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getKey().startsWith("2")) {
                continue;
            }
            JsonNode schema = findJsonSchema(e.getValue().path("content"));
            if (schema != null && !schema.isMissingNode() && !schema.isNull()) {
                return schema;
            }
        }
        return null;
    }

    private List<ToolParameterDefinition> openApiSchemaToToolParameters(
            JsonNode schema,
            JsonNode docRoot,
            ParameterLocation location,
            int depth) {
        if (depth > 24 || schema == null || schema.isMissingNode() || schema.isNull()) {
            return List.of();
        }
        JsonNode base = resolveSchemaRefs(schema, docRoot, new HashSet<>());
        if (base == null || base.isMissingNode() || base.isNull()) {
            return List.of();
        }
        String type = base.path("type").asText("");
        if ("array".equals(type)) {
            JsonNode items = base.get("items");
            if (items == null || items.isMissingNode()) {
                return List.of();
            }
            List<ToolParameterDefinition> nested = openApiSchemaToToolParameters(items, docRoot, location, depth + 1);
            String itemType = firstNonBlank(schemaTypeLabel(items), extractSchemaType(items), "item");
            return List.of(new ToolParameterDefinition("items", itemType, "", false, location, nested));
        }
        JsonNode props = base.get("properties");
        if (props != null && props.isObject()) {
            JsonNode req = base.get("required");
            List<ToolParameterDefinition> out = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> pit = props.fields();
            while (pit.hasNext()) {
                Map.Entry<String, JsonNode> e = pit.next();
                String pname = e.getKey();
                JsonNode pschema = e.getValue();
                String ptype = firstNonBlank(schemaTypeLabel(pschema), extractSchemaType(pschema), "object");
                boolean reqd = req != null && req.isArray() && containsRequiredName(req, pname);
                String desc = firstNonBlank(pschema.path("description").asText(null), "");
                List<ToolParameterDefinition> grandchildren = openApiSchemaToToolParameters(pschema, docRoot, location, depth + 1);
                out.add(new ToolParameterDefinition(pname, ptype, desc, reqd, location, grandchildren));
            }
            return out;
        }
        return List.of();
    }

    private static boolean containsRequiredName(JsonNode reqArray, String name) {
        if (reqArray == null || !reqArray.isArray()) {
            return false;
        }
        for (JsonNode n : reqArray) {
            if (n.isTextual() && name.equals(n.asText())) {
                return true;
            }
        }
        return false;
    }

    private String resolveToolName(String apiPath, JsonNode operation, String method) {
        String operationId = operation.path("operationId").asText(null);
        if (operationId != null && !operationId.isBlank()) {
            return normalizeName(operationId);
        }
        return normalizeName(method + "_" + apiPath.replace('/', '_'));
    }

    private String resolveDescription(JsonNode operation) {
        return firstNonBlank(
                operation.path("description").asText(null),
                operation.path("summary").asText(null),
                "Scanned OpenAPI endpoint"
        );
    }

    private ProjectMetadata normalizeProject(ProjectMetadata projectMetadata, JsonNode root) {
        if (projectMetadata != null) {
            return projectMetadata;
        }

        JsonNode server = root.path("servers").isArray() && root.path("servers").size() > 0
                ? root.path("servers").get(0)
                : null;
        if (server == null) {
            throw new IllegalArgumentException("project metadata is required when OpenAPI servers are absent");
        }

        String url = server.path("url").asText();
        int slashIndex = url.indexOf('/', url.indexOf("//") + 2);
        String baseUrl = slashIndex > 0 ? url.substring(0, slashIndex) : url;
        String contextPath = slashIndex > 0 ? url.substring(slashIndex) : "";
        String projectName = normalizeName(root.path("info").path("title").asText("scanned-project"));
        return new ProjectMetadata(projectName, baseUrl, contextPath);
    }

    private JsonNode readSpec(Path specPath) {
        try {
            String filename = specPath.getFileName().toString().toLowerCase(Locale.ROOT);
            if (filename.endsWith(".json")) {
                return JSON_MAPPER.readTree(specPath.toFile());
            }
            return YAML_MAPPER.readTree(specPath.toFile());
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read OpenAPI spec: " + specPath, ex);
        }
    }

    private String extractSchemaType(JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return "string";
        }
        if (schema.hasNonNull("type")) {
            return schema.get("type").asText();
        }
        String refLabel = refLabel(schema.path("$ref").asText(null));
        return refLabel != null ? refLabel : "string";
    }

    private String schemaTypeLabel(JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return null;
        }
        if (schema.hasNonNull("$ref")) {
            return refLabel(schema.get("$ref").asText());
        }
        if (schema.hasNonNull("type")) {
            return schema.get("type").asText();
        }
        return null;
    }

    private ParameterLocation toLocation(String rawLocation) {
        if (rawLocation == null || rawLocation.isBlank()) {
            return null;
        }
        return switch (rawLocation.toLowerCase(Locale.ROOT)) {
            case "path" -> ParameterLocation.PATH;
            case "query" -> ParameterLocation.QUERY;
            case "body" -> ParameterLocation.BODY;
            default -> null;
        };
    }

    private String normalizeName(String rawName) {
        return rawName
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "")
                .toLowerCase(Locale.ROOT);
    }

    private String joinPath(String contextPath, String apiPath) {
        String left = Objects.requireNonNullElse(contextPath, "");
        if (left.endsWith("/")) {
            left = left.substring(0, left.length() - 1);
        }
        String right = apiPath.startsWith("/") ? apiPath : "/" + apiPath;
        return left + right;
    }

    private String encodeJsonPointer(String apiPath) {
        return apiPath.replace("~", "~0").replace("/", "~1");
    }

    private String refLabel(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        int slashIndex = ref.lastIndexOf('/');
        return slashIndex >= 0 ? ref.substring(slashIndex + 1) : ref;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

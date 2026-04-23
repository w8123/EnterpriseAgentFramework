package com.enterprise.ai.text.tooling.scanner.openapi;

import com.enterprise.ai.text.tooling.scanner.manifest.ParameterLocation;
import com.enterprise.ai.text.tooling.scanner.manifest.ProjectMetadata;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolDefinition;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolManifest;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolParameterDefinition;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 OpenAPI/Swagger 文档生成运行时可消费的扫描结果。
 */
public class OpenApiToolManifestScanner {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final List<String> HTTP_METHODS = List.of("get", "post", "put", "delete", "patch");

    public ToolManifest scan(Path specPath, ProjectMetadata projectMetadata) {
        JsonNode root = readSpec(specPath);
        JsonNode paths = root.path("paths");
        if (!paths.isObject()) {
            throw new IllegalArgumentException("OpenAPI spec does not contain paths: " + specPath);
        }

        ProjectMetadata effectiveProject = normalizeProject(projectMetadata, root);
        List<ToolDefinition> tools = new ArrayList<>();

        Iterator<Map.Entry<String, JsonNode>> pathFields = paths.fields();
        while (pathFields.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathFields.next();
            String apiPath = pathEntry.getKey();
            JsonNode pathItem = pathEntry.getValue();

            for (String method : HTTP_METHODS) {
                JsonNode operation = pathItem.get(method);
                if (operation != null && operation.isObject()) {
                    tools.add(toToolDefinition(specPath, effectiveProject, apiPath, method, pathItem, operation));
                }
            }
        }

        ToolManifest manifest = new ToolManifest(effectiveProject, tools);
        manifest.validate();
        return manifest;
    }

    private ToolDefinition toToolDefinition(
            Path specPath,
            ProjectMetadata project,
            String apiPath,
            String method,
            JsonNode pathItem,
            JsonNode operation
    ) {
        List<ToolParameterDefinition> parameters = parseParameters(pathItem, operation);
        String requestBodyType = extractRequestBodyType(operation);
        if (requestBodyType != null) {
            parameters.add(new ToolParameterDefinition(
                    "body_json",
                    "json",
                    "JSON 请求体，对应 " + requestBodyType,
                    operation.path("requestBody").path("required").asBoolean(false),
                    ParameterLocation.BODY
            ));
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
                extractResponseType(operation),
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

package com.enterprise.ai.control.mcp.infrastructure.runtime;

import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.mcp.application.port.McpContractResolutionException;
import com.enterprise.ai.control.mcp.application.port.McpItemContractResolver;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import feign.FeignException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * CAPABILITY contract resolution: fetches the tool definition through the
 * Capability service internal API (never Control-side table reads) and maps
 * {@code parameters_json} into an MCP JSON-Schema input, {@code ai_description}
 * (falling back to title) into the tool description, and {@code side_effect}
 * into the MCP risk level.
 */
@Component
public class CapabilityItemContractResolver implements McpItemContractResolver {

    private final CapabilityProxyClient capabilityProxyClient;
    private final ObjectMapper objectMapper;

    public CapabilityItemContractResolver(CapabilityProxyClient capabilityProxyClient,
                                          ObjectMapper objectMapper) {
        this.capabilityProxyClient = capabilityProxyClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpPublicationItemKind supportedKind() {
        return McpPublicationItemKind.CAPABILITY;
    }

    @Override
    public McpToolProjection resolve(String sourceRef) {
        String qualifiedName = sourceRef == null ? "" : sourceRef.trim();
        if (qualifiedName.isEmpty()) {
            throw failure("MCP_REQUIRED_FIELD", qualifiedName, "capability sourceRef is required");
        }
        Map<String, Object> definition = lookup(qualifiedName);
        if (!Boolean.TRUE.equals(definition.get("enabled"))) {
            throw failure("MCP_CAPABILITY_TOOL_DISABLED", qualifiedName,
                    "capability tool is disabled: " + qualifiedName);
        }
        String contractHash = text(definition.get("contractHash"));
        if (contractHash == null || !contractHash.matches("[0-9a-f]{64}")
                || !"READY".equals(definition.get("sourceAvailability"))) {
            throw failure("MCP_CAPABILITY_SOURCE_NOT_READY", qualifiedName, "能力来源或契约尚未就绪: " + qualifiedName);
        }
        String inputSchemaJson = inputSchemaJson(text(definition.get("parametersJson")), qualifiedName);
        String description = firstText(
                text(definition.get("aiDescription")),
                text(definition.get("title")),
                text(definition.get("description")));
        String toolName = McpDefaultToolNames.sanitize(firstText(
                text(definition.get("name")),
                qualifiedName));
        return new McpToolProjection(toolName, description, inputSchemaJson,
                McpPublicationItemKind.CAPABILITY, firstText(text(definition.get("qualifiedName")), qualifiedName), null,
                riskLevel(text(definition.get("sideEffect"))), contractHash);
    }

    private Map<String, Object> lookup(String qualifiedName) {
        try {
            ResponseEntity<Map<String, Object>> response =
                    capabilityProxyClient.getToolDefinition(qualifiedName);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
            throw failure("MCP_CAPABILITY_TOOL_NOT_FOUND", qualifiedName,
                    "capability tool is not found: " + qualifiedName);
        } catch (FeignException exception) {
            if (exception instanceof FeignException.NotFound) {
                throw failure("MCP_CAPABILITY_TOOL_NOT_FOUND", qualifiedName,
                        "capability tool is not found: " + qualifiedName);
            }
            throw failure("MCP_CAPABILITY_LOOKUP_FAILED", qualifiedName,
                    "capability service lookup failed for " + qualifiedName
                            + ": " + exception.getMessage());
        }
    }

    /**
     * Maps the stored {@code parameters_json} into the frozen MCP input schema.
     * A stored JSON-Schema object is used verbatim; a stored parameter array is
     * converted into {@code {"type":"object","properties":{...},"required":[...]}}
     * preserving nested children.
     */
    private String inputSchemaJson(String parametersJson, String qualifiedName) {
        if (parametersJson == null || parametersJson.isBlank()) {
            throw failure("MCP_CAPABILITY_SCHEMA_UNRESOLVABLE", qualifiedName,
                    "capability tool has no parameters json: " + qualifiedName);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(parametersJson);
        } catch (Exception exception) {
            throw failure("MCP_CAPABILITY_SCHEMA_UNRESOLVABLE", qualifiedName,
                    "capability tool parameters json is invalid: " + qualifiedName);
        }
        if (root.isObject()
                && (root.has("properties") || "object".equals(text(root.get("type"))))) {
            return root.toString();
        }
        if (root.isArray()) {
            return objectSchema((ArrayNode) root, qualifiedName).toString();
        }
        throw failure("MCP_CAPABILITY_SCHEMA_UNRESOLVABLE", qualifiedName,
                "capability tool parameters json must be a JSON Schema object or a parameter array: "
                        + qualifiedName);
    }

    private ObjectNode objectSchema(ArrayNode parameters, String qualifiedName) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (JsonNode parameter : parameters) {
            String name = jsonText(parameter.get("name"));
            if (name == null || name.isBlank()) {
                throw failure("MCP_CAPABILITY_SCHEMA_UNRESOLVABLE", qualifiedName,
                        "capability tool parameter without a name: " + qualifiedName);
            }
            ObjectNode property = properties.putObject(name);
            JsonNode children = parameter.get("children");
            if (children != null && children.isArray() && !children.isEmpty()) {
                ObjectNode nested = objectSchema((ArrayNode) children, qualifiedName);
                property.put("type", "object");
                property.set("properties", nested.get("properties"));
                if (nested.has("required") && !nested.get("required").isEmpty()) {
                    property.set("required", nested.get("required"));
                }
            } else {
                String type = jsonText(parameter.get("type"));
                property.put("type", type == null || type.isBlank() ? "string" : type);
            }
            String description = jsonText(parameter.get("description"));
            if (description != null) {
                property.put("description", description);
            }
            if (parameter.path("required").asBoolean(false)) {
                required.add(name);
            }
        }
        if (required.isEmpty()) {
            schema.remove("required");
        }
        return schema;
    }

    /** Reads a textual JSON value without the quoted toString() representation. */
    private static String jsonText(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String text = node.asText().trim();
        return text.isEmpty() ? null : text;
    }

    /** NONE/READ_ONLY map to READ, IDEMPOTENT_WRITE/WRITE to WRITE, IRREVERSIBLE stays; unknown defaults to WRITE. */
    private String riskLevel(String sideEffect) {
        String value = sideEffect == null ? "" : sideEffect.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "NONE", "READ_ONLY", "READ" -> "READ";
            case "IRREVERSIBLE" -> "IRREVERSIBLE";
            default -> "WRITE";
        };
    }

    private McpContractResolutionException failure(String code, String sourceRef, String message) {
        return new McpContractResolutionException(code, McpPublicationItemKind.CAPABILITY,
                sourceRef, message);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}

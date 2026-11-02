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
import java.util.LinkedHashMap;
import java.util.Map;

import static com.enterprise.ai.common.util.Texts.firstText;

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
        String risk = riskLevel(text(definition.get("sideEffect")), qualifiedName);
        return new McpToolProjection(toolName, description, inputSchemaJson,
                McpPublicationItemKind.CAPABILITY, firstText(text(definition.get("qualifiedName")), qualifiedName), null,
                risk, contractHash, risk, 1);
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
        Map<String, JsonNode> declared = new LinkedHashMap<>();
        for (JsonNode parameter : parameters) {
            String name = jsonText(parameter.get("name"));
            if (name == null || name.isBlank()) {
                throw failure("MCP_CAPABILITY_SCHEMA_UNRESOLVABLE", qualifiedName,
                        "capability tool parameter without a name: " + qualifiedName);
            }
            declared.put(name, parameter);
        }
        // The SDK describes a DTO with one object parameter plus one-level dotted field
        // declarations. These are fields of that object, not additional root arguments.
        Map<String, ArrayNode> dottedChildren = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : declared.entrySet()) {
            String name = entry.getKey();
            int dot = name.indexOf('.');
            if (dot <= 0 || dot != name.lastIndexOf('.') || dot == name.length() - 1) continue;
            String parent = name.substring(0, dot);
            JsonNode parentParameter = declared.get(parent);
            if (parentParameter == null || !"object".equals(jsonText(parentParameter.get("type")))) continue;
            ObjectNode child = ((ObjectNode) entry.getValue()).deepCopy();
            child.put("name", name.substring(dot + 1));
            dottedChildren.computeIfAbsent(parent, ignored -> objectMapper.createArrayNode()).add(child);
        }
        for (JsonNode parameter : parameters) {
            String name = jsonText(parameter.get("name"));
            if (name == null || name.isBlank()) {
                throw failure("MCP_CAPABILITY_SCHEMA_UNRESOLVABLE", qualifiedName,
                        "capability tool parameter without a name: " + qualifiedName);
            }
            int dot = name.indexOf('.');
            if (dot > 0 && dot == name.lastIndexOf('.') && dot < name.length() - 1
                    && dottedChildren.containsKey(name.substring(0, dot))) continue;
            ObjectNode property = properties.putObject(name);
            JsonNode children = parameter.get("children");
            ArrayNode combined = objectMapper.createArrayNode();
            if (children != null && children.isArray()) combined.addAll((ArrayNode) children);
            if (dottedChildren.containsKey(name)) combined.addAll(dottedChildren.get(name));
            if (!combined.isEmpty()) {
                ObjectNode nested = objectSchema(combined, qualifiedName);
                if ("array".equals(jsonText(parameter.get("type")))) {
                    property.put("type", "array");
                    property.set("items", nested);
                } else {
                    property.put("type", "object");
                    property.set("properties", nested.get("properties"));
                    if (nested.has("required") && !nested.get("required").isEmpty()) {
                        property.set("required", nested.get("required"));
                    }
                }
            } else {
                String type = jsonText(parameter.get("type"));
                property.put("type", type == null || type.isBlank() ? "string" : type);
                if ("array".equals(type)) {
                    String itemsType = firstText(jsonText(parameter.get("itemsType")),
                            jsonText(parameter.path("metadata").get("itemsType")));
                    ObjectNode items = property.putObject("items");
                    if (itemsType != null) items.put("type", itemsType);
                }
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

    /** An absent or unknown owner declaration cannot establish an irreversible-risk floor. */
    private String riskLevel(String sideEffect, String sourceRef) {
        String value = sideEffect == null ? "" : sideEffect.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "NONE", "READ_ONLY", "READ" -> "READ";
            case "IDEMPOTENT_WRITE", "WRITE" -> "WRITE";
            case "IRREVERSIBLE" -> "IRREVERSIBLE";
            default -> throw failure("MCP_CAPABILITY_RISK_UNRESOLVABLE", sourceRef,
                    "能力来源未提供可验证的副作用级别，请先核对能力声明");
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

}

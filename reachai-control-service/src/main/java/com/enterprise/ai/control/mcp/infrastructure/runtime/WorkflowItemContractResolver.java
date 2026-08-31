package com.enterprise.ai.control.mcp.infrastructure.runtime;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.mcp.application.port.McpContractResolutionException;
import com.enterprise.ai.control.mcp.application.port.McpItemContractResolver;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * WORKFLOW contract resolution: reads the Workflow definition and its version
 * history through the Runtime public API (never Control-side table reads),
 * requires an ACTIVE published version, pins that version id into the
 * projection, and mirrors the Workflow-as-Tool schema precedence (version
 * snapshot first, working copy last).
 */
@Component
public class WorkflowItemContractResolver implements McpItemContractResolver {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final RuntimeProxyClient runtimeProxyClient;
    private final ObjectMapper objectMapper;

    public WorkflowItemContractResolver(RuntimeProxyClient runtimeProxyClient,
                                        ObjectMapper objectMapper) {
        this.runtimeProxyClient = runtimeProxyClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpPublicationItemKind supportedKind() {
        return McpPublicationItemKind.WORKFLOW;
    }

    @Override
    public McpToolProjection resolve(String sourceRef) {
        String workflowId = sourceRef == null ? "" : sourceRef.trim();
        if (workflowId.isEmpty()) {
            throw failure("MCP_REQUIRED_FIELD", workflowId, "workflow sourceRef is required");
        }
        Map<String, Object> workflow = lookupWorkflow(workflowId);
        Map<String, Object> activeVersion = activeVersion(workflowId);
        String inputSchemaJson = inputSchemaJson(workflow, activeVersion, workflowId);
        String description = firstText(text(workflow.get("description")), text(workflow.get("name")));
        // The default tool name stays machine-safe: keySlug first, workflowId as the
        // final fallback; the human-readable workflow name only feeds the description.
        String toolName = McpDefaultToolNames.sanitize(firstText(
                text(workflow.get("keySlug")),
                workflowId));
        return new McpToolProjection(toolName, description, inputSchemaJson,
                McpPublicationItemKind.WORKFLOW, workflowId,
                longValue(activeVersion.get("id")), riskLevel(workflow, activeVersion));
    }

    /** Risk is version-pinned when present; an absent contract fails publish unless explicitly overridden. */
    private String riskLevel(Map<String, Object> workflow, Map<String, Object> version) {
        Map<String, Object> snapshot = readMap(text(version.get("snapshotJson")));
        Map<String, Object> graph = readMap(firstText(
                text(version.get("graphSpecSnapshotJson")), text(snapshot.get("graphSpec"))));
        Map<String, Object> metadata = mapValue(graph.get("metadata"));
        String value = firstText(
                text(snapshot.get("riskLevel")),
                text(metadata.get("riskLevel")),
                text(workflow.get("riskLevel")));
        if (value == null) return "UNKNOWN";
        String normalized = value.toUpperCase(Locale.ROOT);
        return Set.of("READ", "WRITE", "PAGE_ACTION", "IRREVERSIBLE").contains(normalized)
                ? normalized : "UNKNOWN";
    }

    private Map<String, Object> lookupWorkflow(String workflowId) {
        try {
            ResponseEntity<Object> response = runtimeProxyClient.getWorkflow(workflowId);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return convertMap(response.getBody());
            }
            throw failure("MCP_WORKFLOW_NOT_FOUND", workflowId,
                    "workflow is not found: " + workflowId);
        } catch (FeignException exception) {
            if (exception instanceof FeignException.NotFound) {
                throw failure("MCP_WORKFLOW_NOT_FOUND", workflowId,
                        "workflow is not found: " + workflowId);
            }
            throw failure("MCP_WORKFLOW_LOOKUP_FAILED", workflowId,
                    "runtime service lookup failed for workflow " + workflowId
                            + ": " + exception.getMessage());
        }
    }

    private Map<String, Object> activeVersion(String workflowId) {
        List<Map<String, Object>> versions;
        try {
            ResponseEntity<Object> response = runtimeProxyClient.listWorkflowVersions(workflowId);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw failure("MCP_WORKFLOW_VERSION_NOT_ACTIVE", workflowId,
                        "workflow has no ACTIVE published version: " + workflowId);
            }
            versions = convertList(response.getBody());
        } catch (FeignException exception) {
            throw failure("MCP_WORKFLOW_LOOKUP_FAILED", workflowId,
                    "runtime service version lookup failed for workflow " + workflowId
                            + ": " + exception.getMessage());
        }
        return versions.stream()
                .filter(version -> "ACTIVE".equalsIgnoreCase(text(version.get("status"))))
                .findFirst()
                .orElseThrow(() -> failure("MCP_WORKFLOW_VERSION_NOT_ACTIVE", workflowId,
                        "workflow has no ACTIVE published version: " + workflowId));
    }

    /**
     * Mirrors {@code RuntimeWorkflowSchemaResolver} precedence so an MCP tool
     * always exposes the contract pinned to the ACTIVE Workflow version:
     * version snapshot {@code inputSchemaJson}, then the version's GraphSpec
     * snapshot {@code inputSchema}, then the working copy's explicit schema,
     * then the working copy's GraphSpec.
     */
    private String inputSchemaJson(Map<String, Object> workflow,
                                   Map<String, Object> version,
                                   String workflowId) {
        Map<String, Object> snapshot = readMap(text(version.get("snapshotJson")));
        String explicitPublishedSchema = schemaJson(snapshot.get("inputSchemaJson"));
        if (explicitPublishedSchema != null) {
            return explicitPublishedSchema;
        }
        String publishedGraph = firstText(
                text(version.get("graphSpecSnapshotJson")),
                text(snapshot.get("graphSpec")));
        String publishedGraphSchema = graphSchemaJson(publishedGraph);
        if (publishedGraphSchema != null) {
            return publishedGraphSchema;
        }
        String explicitWorkingSchema = schemaJson(workflow.get("inputSchemaJson"));
        if (explicitWorkingSchema != null) {
            return explicitWorkingSchema;
        }
        String workingGraphSchema = graphSchemaJson(text(workflow.get("graphSpecJson")));
        if (workingGraphSchema != null) {
            return workingGraphSchema;
        }
        throw failure("MCP_WORKFLOW_SCHEMA_UNRESOLVABLE", workflowId,
                "workflow has no resolvable input schema: " + workflowId);
    }

    private String graphSchemaJson(String graphJson) {
        if (graphJson == null || graphJson.isBlank()) {
            return null;
        }
        return schemaJson(readMap(graphJson).get("inputSchema"));
    }

    private String schemaJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text.isBlank() ? null : text.trim();
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            return json.isBlank() ? null : json;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> convertMap(Object body) {
        return objectMapper.convertValue(body, MAP);
    }

    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> ? objectMapper.convertValue(value, MAP) : Map.of();
    }

    private List<Map<String, Object>> convertList(Object body) {
        return objectMapper.convertValue(body,
                new TypeReference<List<Map<String, Object>>>() { });
    }

    private static Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private McpContractResolutionException failure(String code, String sourceRef, String message) {
        return new McpContractResolutionException(code, McpPublicationItemKind.WORKFLOW,
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

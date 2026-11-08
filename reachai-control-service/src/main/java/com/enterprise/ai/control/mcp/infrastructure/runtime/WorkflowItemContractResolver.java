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

import static com.enterprise.ai.common.util.Texts.firstText;

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
        String floor = trustedRiskFloor(activeVersion, workflowId, 0);
        String declaredRisk = riskLevel(workflow, activeVersion);
        if ("UNKNOWN".equals(declaredRisk)) declaredRisk = floor;
        return new McpToolProjection(toolName, description, inputSchemaJson,
                McpPublicationItemKind.WORKFLOW, workflowId,
                longValue(activeVersion.get("id")), declaredRisk, null, floor, 1);
    }

    /** Classify only a pinned graph and owner-written release facts, never an editable draft. */
    private String trustedRiskFloor(Map<String, Object> version, String workflowId, int depth) {
        Map<String, Object> snapshot = readMap(text(version.get("snapshotJson")));
        String graphJson = firstText(text(version.get("graphSpecSnapshotJson")), text(snapshot.get("graphSpec")));
        Object rawNodes = readMap(graphJson).get("nodes");
        if (!(rawNodes instanceof List<?> nodes)) throw unclassified(workflowId);
        boolean business = false;
        boolean http = false;
        boolean knowledgeWrite = false;
        String childFloor = "READ";
        Set<String> safe = Set.of("LLM", "USERINPUT", "INTERACTION", "IFELSE", "CONDITION", "VARIABLEASSIGN",
                "VARIABLE", "TEMPLATE", "ANSWER", "INTENTCLASSIFIER", "CLASSIFIER", "VARIABLEAGGREGATOR",
                "AGGREGATE", "HUMANAPPROVAL", "APPROVAL", "LOOP", "DOCUMENTEXTRACT", "PARAMETEREXTRACT",
                "PARAMETER", "KNOWLEDGERETRIEVAL", "KNOWLEDGE", "PARALLEL", "CODE", "LISTOPERATOR");
        for (Object value : nodes) {
            if (!(value instanceof Map<?, ?> node)) throw unclassified(workflowId);
            String type = text(node.get("type"));
            if (type == null) throw unclassified(workflowId);
            String key = type.toUpperCase(Locale.ROOT).replace("_", "").replace("-", "");
            if ("TOOL".equals(key)) {
                Map<String, Object> ref = mapValue(node.get("ref"));
                Map<String, Object> config = mapValue(node.get("config"));
                String assetType = text(ref.get("assetType"));
                String qualifiedName = firstText(text(ref.get("qualifiedName")), text(ref.get("name")));
                if (!"HTTP_API".equals(assetType) && !"BUSINESS_METHOD".equals(assetType)) throw unclassified(workflowId);
                boolean apiReference = qualifiedName != null && qualifiedName.startsWith("http-api:")
                        || config.containsKey("httpApiAssetId");
                if ("HTTP_API".equals(assetType) || apiReference) http = true;
                if ("BUSINESS_METHOD".equals(assetType)) business = true;
            } else if ("KNOWLEDGEWRITE".equals(key)) {
                knowledgeWrite = true;
            } else if ("SUBWORKFLOW".equals(key)) {
                // A sub-workflow is pinned to one published version; its risk merges upward.
                childFloor = higherFloor(childFloor, subWorkflowFloor(mapValue(node.get("config")), workflowId, depth));
            } else if (!safe.contains(key)) {
                throw failure("MCP_WORKFLOW_RISK_UNRESOLVABLE", workflowId,
                        "Workflow 包含无法安全分类的节点，暂不能发布到 MCP: " + type);
            }
        }
        String floor = "READ";
        if (business) {
            String businessFloor = text(snapshot.get("businessMethodRiskFloor"));
            if (!Set.of("READ", "WRITE", "IRREVERSIBLE").contains(businessFloor == null ? "" : businessFloor)) throw unclassified(workflowId);
            floor = businessFloor;
        }
        if (http) {
            String apiFloor = text(snapshot.get("httpApiRiskFloor"));
            if (apiFloor != null && !"WRITE".equals(apiFloor)) throw unclassified(workflowId);
            if (apiFloor == null && !Boolean.TRUE.equals(snapshot.get("httpApiRiskClassified"))) throw unclassified(workflowId);
            if ("WRITE".equals(apiFloor) && !"IRREVERSIBLE".equals(floor)) floor = "WRITE";
        }
        if (knowledgeWrite && "READ".equals(floor)) floor = "WRITE";
        floor = higherFloor(floor, childFloor);
        // Any independently recorded owner floor applies even if a malformed graph would
        // otherwise hide its corresponding node from classification.
        String recordedBusinessFloor = text(snapshot.get("businessMethodRiskFloor"));
        String recordedApiFloor = text(snapshot.get("httpApiRiskFloor"));
        if (recordedBusinessFloor != null && !Set.of("READ", "WRITE", "IRREVERSIBLE").contains(recordedBusinessFloor)
                || recordedApiFloor != null && !"WRITE".equals(recordedApiFloor)) throw unclassified(workflowId);
        if ("IRREVERSIBLE".equals(recordedBusinessFloor)) floor = "IRREVERSIBLE";
        else if (("WRITE".equals(recordedBusinessFloor) || "WRITE".equals(recordedApiFloor))
                && !"IRREVERSIBLE".equals(floor)) floor = "WRITE";
        return floor;
    }

    private static final int MAX_SUB_WORKFLOW_DEPTH = 3;
    private static final List<String> FLOOR_ORDER = List.of("READ", "WRITE", "IRREVERSIBLE");

    private String higherFloor(String left, String right) {
        return FLOOR_ORDER.indexOf(right) > FLOOR_ORDER.indexOf(left) ? right : left;
    }

    private String subWorkflowFloor(Map<String, Object> config, String parentWorkflowId, int depth) {
        Map<String, Object> nested = mapValue(config.get("subWorkflowConfig"));
        String nestedId = text(nested.get("workflowId"));
        String topId = text(config.get("workflowId"));
        Long nestedVersion = longValue(nested.get("versionId"));
        Long topVersion = longValue(config.get("versionId"));
        if (nestedId != null && topId != null && !nestedId.equals(topId)
                || nested.get("versionId") != null && config.get("versionId") != null
                && !java.util.Objects.equals(nestedVersion, topVersion)) {
            throw unclassified(parentWorkflowId);
        }
        String childId = firstText(topId, nestedId);
        Long versionId = config.get("versionId") != null ? topVersion : nestedVersion;
        if (childId == null || versionId == null || depth + 1 > MAX_SUB_WORKFLOW_DEPTH
                || childId.equals(parentWorkflowId)) {
            throw unclassified(parentWorkflowId);
        }
        Map<String, Object> pinned = pinnedVersion(childId, versionId, parentWorkflowId);
        return trustedRiskFloor(pinned, childId, depth + 1);
    }

    private Map<String, Object> pinnedVersion(String workflowId, Long versionId, String parentWorkflowId) {
        List<Map<String, Object>> versions;
        try {
            ResponseEntity<Object> response = runtimeProxyClient.listWorkflowVersions(workflowId);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw unclassified(parentWorkflowId);
            }
            versions = convertList(response.getBody());
        } catch (FeignException exception) {
            throw failure("MCP_WORKFLOW_LOOKUP_FAILED", parentWorkflowId,
                    "runtime service version lookup failed for sub-workflow " + workflowId
                            + ": " + exception.getMessage());
        }
        return versions.stream()
                .filter(version -> versionId.equals(longValue(version.get("id"))))
                .filter(version -> Set.of("ACTIVE", "RETIRED").contains(
                        String.valueOf(text(version.get("status"))).toUpperCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> failure("MCP_WORKFLOW_RISK_UNRESOLVABLE", parentWorkflowId,
                        "子流程固定的版本不存在或未发布，请重新选择子流程版本后再发布"));
    }

    private McpContractResolutionException unclassified(String workflowId) {
        return failure("MCP_WORKFLOW_RISK_UNRESOLVABLE", workflowId,
                "无法核验 Workflow 的固定风险，请先重新发布 Workflow");
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

}

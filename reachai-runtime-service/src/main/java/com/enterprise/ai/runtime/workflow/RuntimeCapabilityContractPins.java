package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.graph.GraphSpecToolContract;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable releases pin the owner-provided execution contract, never a caller-provided hash. */
@Service
public class RuntimeCapabilityContractPins {
    private final RuntimeCapabilityCatalogClient catalog;
    private final ObjectMapper mapper;
    private final RuntimeWorkflowHttpApiService httpApis;

    @Autowired
    public RuntimeCapabilityContractPins(RuntimeCapabilityCatalogClient catalog, ObjectMapper mapper,
                                         RuntimeWorkflowHttpApiService httpApis) {
        this.catalog = catalog;
        this.mapper = mapper;
        this.httpApis = httpApis;
    }

    /** Existing non-API isolated fixtures do not need the HTTP API projection. */
    public RuntimeCapabilityContractPins(RuntimeCapabilityCatalogClient catalog, ObjectMapper mapper) {
        this(catalog, mapper, null);
    }

    public String pin(String graphJson) {
        return pin(graphJson, null);
    }

    public String pin(String graphJson, RuntimeWorkflowDefinitionEntity workflow) {
        GraphSpec graph = parse(graphJson);
        boolean changed = false;
        for (GraphSpec.Node node : graph.getNodes() == null ? List.<GraphSpec.Node>of() : graph.getNodes()) {
            if (!"TOOL".equals(node.getType())) continue;
            String reference = GraphSpecToolContract.resolve(node);
            if (isHttpApi(node, reference)) {
                if (httpApis == null) throw new IllegalArgumentException("HTTP_API_WORKFLOW_PROJECTION_UNAVAILABLE");
                RuntimeWorkflowHttpApiService.ReleasePin pin = httpApis.pin(node, workflow, reference);
                GraphSpec.CapabilityRef ref = node.getRef() == null ? new GraphSpec.CapabilityRef() : node.getRef();
                Object apiId = node.getConfig() == null ? null : node.getConfig().get("httpApiAssetId");
                if (!(apiId instanceof Number asset) || asset.longValue() <= 0) {
                    throw new IllegalArgumentException("HTTP_API_OWNER_REFERENCE_REQUIRED");
                }
                requireMatchingOwner(ref, "HTTP_API", asset.longValue(), workflow == null ? null : workflow.getProjectCode());
                ref.setKind("TOOL");
                ref.setQualifiedName(reference);
                ref.setDefinitionId(pin.id());
                ref.setContractHash(pin.acceptedContractHash());
                ref.setAssetType("HTTP_API");
                ref.setAssetId(asset.longValue());
                ref.setAcceptedRevisionId(null);
                ref.setBusinessContractHash(null);
                ref.setBindingHash(null);
                ref.setExecutionRevision(null);
                node.setRef(ref);
                changed = true;
                continue;
            }
            Map<String, Object> definition = requireDefinition(reference);
            if (workflow != null && (!Objects.equals(workflow.getProjectCode(), definition.get("projectCode"))
                    || !Objects.equals(workflow.getProjectId(), ((Number) definition.get("projectId")).longValue()))) {
                throw new IllegalArgumentException("BUSINESS_METHOD_WORKFLOW_PROJECT_MISMATCH");
            }
            GraphSpec.CapabilityRef ref = node.getRef();
            if (ref == null) ref = new GraphSpec.CapabilityRef();
            requireMatchingOwner(ref, "BUSINESS_METHOD", ((Number) definition.get("assetId")).longValue(),
                    String.valueOf(definition.get("projectCode")));
            ref.setKind("TOOL");
            ref.setDefinitionId(null);
            ref.setAssetType("BUSINESS_METHOD");
            ref.setAssetId(((Number) definition.get("assetId")).longValue());
            ref.setAcceptedRevisionId(((Number) definition.get("acceptedRevisionId")).longValue());
            ref.setProjectCode(String.valueOf(definition.get("projectCode")));
            ref.setBusinessContractHash(String.valueOf(definition.get("businessContractHash")));
            ref.setBindingHash(String.valueOf(definition.get("bindingHash")));
            ref.setExecutionRevision(String.valueOf(definition.get("executionRevision")));
            ref.setQualifiedName(String.valueOf(definition.get("qualifiedName")));
            ref.setName(String.valueOf(definition.get("name")));
            ref.setContractHash(String.valueOf(definition.get("contractHash")));
            node.setRef(ref);
            changed = true;
        }
        if (!changed) return graphJson;
        try { return mapper.writeValueAsString(graph); }
        catch (Exception invalid) { throw new IllegalArgumentException("能力契约固定失败", invalid); }
    }

    private static void requireMatchingOwner(GraphSpec.CapabilityRef ref, String assetType, Long assetId, String projectCode) {
        if (ref.getAssetType() != null && !assetType.equals(ref.getAssetType())
                || ref.getAssetId() != null && !assetId.equals(ref.getAssetId())
                || ref.getProjectCode() != null && projectCode != null && !projectCode.equals(ref.getProjectCode())) {
            throw new IllegalArgumentException("WORKFLOW_ASSET_REFERENCE_MISMATCH");
        }
    }

    public void validatePinned(String graphJson) {
        GraphSpec graph = parse(graphJson);
        for (GraphSpec.Node node : graph.getNodes() == null ? List.<GraphSpec.Node>of() : graph.getNodes()) {
            if (!"TOOL".equals(node.getType())) continue;
            if (isHttpApi(node, GraphSpecToolContract.resolve(node))) {
                if (httpApis == null) throw new IllegalArgumentException("HTTP_API_WORKFLOW_PROJECTION_UNAVAILABLE");
                httpApis.validatePinned(node);
                continue;
            }
            GraphSpec.CapabilityRef ref = node.getRef();
            Map<String, Object> definition = requireDefinition(GraphSpecToolContract.resolve(node));
            if (ref == null || !"BUSINESS_METHOD".equals(ref.getAssetType())
                    || !Objects.equals(ref.getAssetId(), ((Number) definition.get("assetId")).longValue())
                    || !Objects.equals(ref.getAcceptedRevisionId(), ((Number) definition.get("acceptedRevisionId")).longValue())
                    || !Objects.equals(ref.getProjectCode(), definition.get("projectCode"))
                    || !Objects.equals(ref.getBusinessContractHash(), definition.get("businessContractHash"))
                    || !Objects.equals(ref.getBindingHash(), definition.get("bindingHash"))
                    || !Objects.equals(ref.getExecutionRevision(), definition.get("executionRevision"))
                    || !Objects.equals(ref.getContractHash(), definition.get("contractHash"))) {
                throw new IllegalArgumentException("已发布能力契约不再匹配，请校验并重新发布 Workflow");
            }
        }
    }

    public void bindPublishedGraph(String graphJson, long versionId) {
        GraphSpec graph = parse(graphJson);
        for (GraphSpec.Node node : graph.getNodes() == null ? List.<GraphSpec.Node>of() : graph.getNodes()) {
            if (!"TOOL".equals(node.getType())) continue;
            if (isHttpApi(node, GraphSpecToolContract.resolve(node))) {
                if (httpApis == null) throw new IllegalArgumentException("HTTP_API_WORKFLOW_PROJECTION_UNAVAILABLE");
                httpApis.bindVersion(node, versionId);
            }
        }
    }

    /** Only the supported API owner chain contributes this scoped floor; not a platform-wide node risk inference. */
    public String httpApiRiskFloor(String graphJson) {
        GraphSpec graph = parse(graphJson);
        for (GraphSpec.Node node : graph.getNodes()) {
            if ("TOOL".equals(node.getType()) && isHttpApi(node, GraphSpecToolContract.resolve(node))) {
                if (httpApis == null) throw new IllegalArgumentException("HTTP_API_WORKFLOW_PROJECTION_UNAVAILABLE");
                if ("WRITE".equals(httpApis.publishedRiskFloor(node))) return "WRITE";
            }
        }
        return null;
    }

    private boolean isHttpApi(GraphSpec.Node node, String reference) {
        return reference != null && reference.startsWith("http-api:")
                || node.getConfig() != null && node.getConfig().containsKey("httpApiAssetId");
    }

    private Map<String, Object> requireDefinition(String reference) {
        if (reference == null) throw new IllegalArgumentException("能力引用缺失");
        Map<String, Object> definition = catalog.getToolDefinition(reference);
        if (definition != null && "LEGACY_SCAN_TOOL_RETIRED".equals(definition.get("sourceAvailability"))) {
            throw new IllegalArgumentException("CAPABILITY_LEGACY_SCAN_TOOL_RETIRED: 扫描人工调用投影已退场；请在所属项目 API 目录确认来源/接纳/连接，再在节点“选择 API”重新选择并显式发布；旧草稿和发布引用保持不变。");
        }
        if (definition == null || !Boolean.TRUE.equals(definition.get("enabled"))
                || !"BUSINESS_METHOD".equals(definition.get("assetType"))
                || !(definition.get("assetId") instanceof Number id) || id.longValue() <= 0
                || !(definition.get("acceptedRevisionId") instanceof Number revision) || revision.longValue() <= 0
                || !(definition.get("projectId") instanceof Number project) || project.longValue() <= 0
                || !(definition.get("projectCode") instanceof String code) || code.isBlank()
                || !hash(definition.get("businessContractHash")) || !hash(definition.get("bindingHash"))
                || !hash(definition.get("executionRevision"))
                || !(definition.get("contractHash") instanceof String hash) || !hash.matches("[0-9a-f]{64}")
                || !(definition.get("qualifiedName") instanceof String name) || name.isBlank()
                || !"READY".equals(definition.get("sourceAvailability"))) {
            throw new IllegalArgumentException("能力来源或契约尚未就绪: " + reference);
        }
        return definition;
    }

    private boolean hash(Object value) { return value instanceof String text && text.matches("[0-9a-f]{64}"); }

    private GraphSpec parse(String graphJson) {
        try {
            GraphSpec graph = mapper.readValue(graphJson, GraphSpec.class);
            if (graph == null || graph.getNodes() == null) throw new IllegalArgumentException("GraphSpec nodes 缺失");
            return graph;
        }
        catch (Exception invalid) { throw new IllegalArgumentException("Workflow GraphSpec 无法读取", invalid); }
    }

}

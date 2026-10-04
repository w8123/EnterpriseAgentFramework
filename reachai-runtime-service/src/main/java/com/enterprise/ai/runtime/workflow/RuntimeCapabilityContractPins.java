package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.graph.GraphSpecToolContract;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

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
                ref.setKind("TOOL");
                ref.setQualifiedName(reference);
                ref.setDefinitionId(pin.id());
                ref.setContractHash(pin.acceptedContractHash());
                node.setRef(ref);
                changed = true;
                continue;
            }
            Map<String, Object> definition = requireDefinition(reference);
            GraphSpec.CapabilityRef ref = node.getRef();
            if (ref == null) ref = new GraphSpec.CapabilityRef();
            ref.setKind("TOOL");
            ref.setDefinitionId(((Number) definition.get("id")).longValue());
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

    public void validatePinned(String graphJson) {
        GraphSpec graph = parse(graphJson);
        for (GraphSpec.Node node : graph.getNodes() == null ? List.<GraphSpec.Node>of() : graph.getNodes()) {
            if (!"TOOL".equals(node.getType())) continue;
            if (isHttpApi(node, GraphSpecToolContract.resolve(node))) {
                if (httpApis == null) throw new IllegalArgumentException("HTTP_API_WORKFLOW_PROJECTION_UNAVAILABLE");
                httpApis.validatePinned(node);
                continue;
            }
            Long definitionId = node.getRef() == null ? null : node.getRef().getDefinitionId();
            String hash = node.getRef() == null ? null : node.getRef().getContractHash();
            Map<String, Object> definition = requireDefinition(GraphSpecToolContract.resolve(node));
            if (definitionId == null || definitionId <= 0
                    || definitionId.longValue() != ((Number) definition.get("id")).longValue()
                    || hash == null || !hash.equals(definition.get("contractHash"))) {
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
                || !(definition.get("id") instanceof Number id) || id.longValue() <= 0
                || !(definition.get("contractHash") instanceof String hash) || !hash.matches("[0-9a-f]{64}")
                || !(definition.get("qualifiedName") instanceof String name) || name.isBlank()
                || !"READY".equals(definition.get("sourceAvailability"))) {
            throw new IllegalArgumentException("能力来源或契约尚未就绪: " + reference);
        }
        return definition;
    }

    private GraphSpec parse(String graphJson) {
        try {
            GraphSpec graph = mapper.readValue(graphJson, GraphSpec.class);
            if (graph == null || graph.getNodes() == null) throw new IllegalArgumentException("GraphSpec nodes 缺失");
            return graph;
        }
        catch (Exception invalid) { throw new IllegalArgumentException("Workflow GraphSpec 无法读取", invalid); }
    }

}

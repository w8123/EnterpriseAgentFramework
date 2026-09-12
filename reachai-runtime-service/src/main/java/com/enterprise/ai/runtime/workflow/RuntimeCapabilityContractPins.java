package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.graph.GraphSpecToolContract;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Immutable releases pin the owner-provided execution contract, never a caller-provided hash. */
@Service
@RequiredArgsConstructor
public class RuntimeCapabilityContractPins {
    private final RuntimeCapabilityCatalogClient catalog;
    private final ObjectMapper mapper;

    public String pin(String graphJson) {
        GraphSpec graph = parse(graphJson);
        boolean changed = false;
        for (GraphSpec.Node node : graph.getNodes() == null ? List.<GraphSpec.Node>of() : graph.getNodes()) {
            if (!"TOOL".equals(node.getType())) continue;
            String reference = GraphSpecToolContract.resolve(node);
            Map<String, Object> definition = requireDefinition(reference);
            GraphSpec.CapabilityRef ref = node.getRef();
            if (ref == null) ref = new GraphSpec.CapabilityRef();
            ref.setKind("TOOL");
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
            String hash = node.getRef() == null ? null : node.getRef().getContractHash();
            Map<String, Object> definition = requireDefinition(GraphSpecToolContract.resolve(node));
            if (hash == null || !hash.equals(definition.get("contractHash"))) {
                throw new IllegalArgumentException("已发布能力契约不再匹配，请校验并重新发布 Workflow");
            }
        }
    }

    private Map<String, Object> requireDefinition(String reference) {
        if (reference == null) throw new IllegalArgumentException("能力引用缺失");
        Map<String, Object> definition = catalog.getToolDefinition(reference);
        if (definition == null || !Boolean.TRUE.equals(definition.get("enabled"))
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

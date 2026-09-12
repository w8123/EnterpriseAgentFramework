package com.enterprise.ai.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** Same stable reference resolution for execution, publication and impact evidence. */
public final class GraphSpecToolContract {
    private GraphSpecToolContract() { }

    public static String requirePublishedPins(String graphJson, ObjectMapper mapper) {
        try {
            GraphSpec graph = mapper.readValue(graphJson, GraphSpec.class);
            if (graph == null || graph.getNodes() == null) throw new IllegalArgumentException("GraphSpec nodes 缺失");
            for (GraphSpec.Node node : graph.getNodes()) {
                if (node == null) throw new IllegalArgumentException("GraphSpec node 无效");
                if (!"TOOL".equals(node.getType())) continue;
                String hash = node.getRef() == null ? null : node.getRef().getContractHash();
                if (hash == null || !hash.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("CAPABILITY_PUBLISHED_CONTRACT_REQUIRED: 请重新校验并发布 Workflow");
                }
            }
            return graphJson;
        } catch (IllegalArgumentException invalid) { throw invalid; }
        catch (Exception invalid) { throw new IllegalArgumentException("Workflow GraphSpec 无法读取", invalid); }
    }

    public static String requirePublishedPins(String graphJson) {
        return requirePublishedPins(graphJson, new ObjectMapper());
    }

    public static String resolve(GraphSpec.Node node) {
        if (node.getRef() != null) {
            String ref = first(node.getRef().getQualifiedName(), node.getRef().getName());
            if (ref != null) return ref;
        }
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        String direct = configured(config, 0);
        if (direct != null) return direct;
        return config.get("toolConfig") instanceof Map<?, ?> nested ? configured(nested, 0) : null;
    }

    private static String configured(Map<?, ?> value, int depth) {
        if (depth > 4) return null;
        String direct = first(value.get("qualifiedName"));
        if (direct != null) return direct;
        Object ref = value.get("ref");
        String nested = ref instanceof Map<?, ?> map
                ? first(map.get("qualifiedName"), map.get("name"), configured(map, depth + 1)) : first(ref);
        return first(nested, value.get("toolName"));
    }

    private static String first(Object... values) {
        for (Object value : values) {
            if (value instanceof String text && !text.isBlank()) return text.trim();
        }
        return null;
    }
}

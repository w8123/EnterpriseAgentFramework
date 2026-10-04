package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Strict, shared preflight for the first saved-draft HTTP API trial shape. */
public final class HttpApiDraftTrialPolicy {
    private HttpApiDraftTrialPolicy() { }

    public record Target(String nodeId, long apiId, String qualifiedName) { }

    public static Target target(ObjectMapper json, String graphJson) {
        try {
            JsonNode graph = json.readTree(graphJson);
            JsonNode nodes = graph.path("nodes");
            JsonNode edges = graph.path("edges");
            if (!nodes.isArray() || nodes.size() != 2 || !edges.isArray() || edges.size() != 1) {
                throw unsupported();
            }
            JsonNode api = null;
            JsonNode variable = null;
            for (JsonNode node : nodes) {
                if (!Set.of("", "TERMINATE").contains(node.path("errorPolicy").path("strategy").asText())) {
                    throw unsupported();
                }
                if ("TOOL".equals(node.path("type").asText()) && api == null) api = node;
                else if ("VARIABLE_ASSIGN".equals(node.path("type").asText()) && variable == null) variable = node;
                else throw unsupported();
            }
            if (api == null || variable == null) throw unsupported();
            String apiNodeId = api.path("id").asText();
            String variableNodeId = variable.path("id").asText();
            JsonNode ref = api.path("ref");
            JsonNode config = api.path("config");
            JsonNode id = config.path("httpApiAssetId");
            String name = ref.path("qualifiedName").asText();
            if (!apiNodeId.matches("[A-Za-z0-9_-]{1,128}")
                    || !variableNodeId.matches("[A-Za-z0-9_-]{1,128}") || apiNodeId.equals(variableNodeId)
                    || !"TOOL".equals(ref.path("kind").asText())
                    || !name.matches("http-api:[A-Za-z0-9._:-]{1,240}")
                    || !id.isIntegralNumber() || id.longValue() <= 0
                    || ref.hasNonNull("definitionId") || ref.hasNonNull("contractHash")
                    || !apiNodeId.equals(graph.path("entryNodeId").asText())
                    || !graph.path("exitNodeIds").isArray() || graph.path("exitNodeIds").size() != 1
                    || !variableNodeId.equals(graph.path("exitNodeIds").get(0).asText())
                    || !apiNodeId.equals(edges.get(0).path("from").asText())
                    || !variableNodeId.equals(edges.get(0).path("to").asText())
                    || !Set.of("", "always").contains(edges.get(0).path("condition").asText())
                    || !config.path("inputMapping").isObject()
                    || !variable.path("config").path("assignments").isObject()) {
                throw unsupported();
            }
            var mappings = config.path("inputMapping").fields();
            if (!mappings.hasNext()) throw unsupported();
            while (mappings.hasNext()) {
                var mapping = mappings.next();
                String key = mapping.getKey();
                String source = mapping.getValue().asText();
                if (!key.matches("(?:pathParams|queryParams)\\.[A-Za-z][A-Za-z0-9_-]{0,127}")
                        || !mapping.getValue().isTextual()
                        || !source.matches("params\\.[A-Za-z][A-Za-z0-9_-]{0,127}")) {
                    throw unsupported();
                }
            }
            var assignments = variable.path("config").path("assignments").fields();
            if (!assignments.hasNext()) throw unsupported();
            String outputPrefix = "nodeOutput." + apiNodeId + ".";
            while (assignments.hasNext()) {
                var assignment = assignments.next();
                String source = assignment.getValue().asText();
                if (!assignment.getKey().matches("[A-Za-z][A-Za-z0-9_-]{0,127}")
                        || !assignment.getValue().isTextual() || !source.startsWith(outputPrefix)
                        || !source.substring(outputPrefix.length()).matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) {
                    throw unsupported();
                }
            }
            return new Target(apiNodeId, id.longValue(), name);
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw unsupported();
        }
    }

    public static boolean readOnlyOwner(String method, JsonNode acceptedContract) {
        if (acceptedContract == null || !acceptedContract.isObject()) return false;
        String contractMethod = acceptedContract.path("identity").path("method").asText();
        String sideEffect = acceptedContract.path("sideEffect").asText();
        return Set.of("GET", "HEAD").contains(method)
                && Objects.equals(method, contractMethod)
                && Set.of("READ_ONLY", "NONE").contains(sideEffect);
    }

    public static String graphSha256(String graphJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(graphJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception invalid) {
            throw new IllegalStateException("SHA-256 unavailable", invalid);
        }
    }

    private static IllegalArgumentException unsupported() {
        return new IllegalArgumentException("HTTP_API_TRIAL_GRAPH_UNSUPPORTED");
    }
}

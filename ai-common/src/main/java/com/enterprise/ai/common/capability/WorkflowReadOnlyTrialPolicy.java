package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One signed envelope for the two saved-draft read-only trial targets. */
public final class WorkflowReadOnlyTrialPolicy {
    public static final int VERSION = 1;
    public static final String HTTP_API = "HTTP_API";
    public static final String BUSINESS_METHOD = "BUSINESS_METHOD";
    public static final String STUDIO_CONSTRAINT = "studioReadOnlyTrial";
    private WorkflowReadOnlyTrialPolicy() { }

    public record Target(String nodeId, long apiId, String qualifiedName, String assetType) {
        public HttpApiDraftTrialPolicy.Target apiTarget() {
            return new HttpApiDraftTrialPolicy.Target(nodeId, apiId, qualifiedName);
        }
    }

    public record AllowedTarget(String nodeId, long apiId, String qualifiedName,
                                String environment, String acceptedContractHash, String sourceSetRevision,
                                String assetType, String methodName, String sourceContractHash) {
        public AllowedTarget(String nodeId, long apiId, String qualifiedName, String environment,
                             String acceptedContractHash, String sourceSetRevision) {
            this(nodeId, apiId, qualifiedName, environment, acceptedContractHash, sourceSetRevision,
                    HTTP_API, null, null);
        }
        public static AllowedTarget businessMethod(Target target,
                ConsoleCapabilityInvocationContracts.InvocationContext owner) {
            return new AllowedTarget(target.nodeId(), 0, target.qualifiedName(), null,
                    owner.acceptedContractHash(), null, BUSINESS_METHOD, owner.name(), owner.sourceContractHash());
        }
    }

    /** Browser input never supplies any of these attested identity/target/hash fields. */
    public record TrialCommand(int contractVersion, String workflowId, String expectedRevision,
                               String graphSha256, Long projectId, String projectCode,
                               String platformActorId, List<AllowedTarget> allowedTargets,
                               Map<String, Object> inputParams, long deadlineEpochMs) { }

    /** Request-local only; never persisted as a release pin or placed in GraphSpec. */
    public record BusinessMethodPin(String nodeId, Long projectId, String projectCode,
                                    ConsoleCapabilityInvocationContracts.InvocationContext owner,
                                    Map<String, Object> expectedInput, long deadlineEpochMs) { }

    public static Target target(ObjectMapper json, String graphJson) {
        try {
            JsonNode graph = json.readTree(graphJson);
            JsonNode nodes = graph.path("nodes");
            if (!nodes.isArray()) throw unsupported();
            for (JsonNode node : nodes) {
                if ("TOOL".equals(node.path("type").asText())
                        && (node.path("ref").path("qualifiedName").asText().startsWith("http-api:")
                        || node.path("config").has("httpApiAssetId"))) {
                    var api = HttpApiDraftTrialPolicy.target(json, graphJson);
                    return new Target(api.nodeId(), api.apiId(), api.qualifiedName(), HTTP_API);
                }
            }
            return businessTarget(graph);
        } catch (IllegalArgumentException invalid) { throw invalid; }
        catch (Exception invalid) { throw unsupported(); }
    }

    private static Target businessTarget(JsonNode graph) {
        JsonNode nodes = graph.path("nodes"), edges = graph.path("edges");
        if (nodes.size() != 2 || !edges.isArray() || edges.size() != 1) throw unsupported();
        JsonNode method = null, variable = null;
        for (JsonNode node : nodes) {
            if (!Set.of("", "TERMINATE").contains(node.path("errorPolicy").path("strategy").asText())) throw unsupported();
            if ("TOOL".equals(node.path("type").asText()) && method == null) method = node;
            else if ("VARIABLE_ASSIGN".equals(node.path("type").asText()) && variable == null) variable = node;
            else throw unsupported();
        }
        if (method == null || variable == null) throw unsupported();
        String nodeId = method.path("id").asText(), exitId = variable.path("id").asText();
        JsonNode ref = method.path("ref"), config = method.path("config");
        String name = ref.path("qualifiedName").asText();
        if (!nodeId.matches("[A-Za-z0-9_-]{1,128}") || !exitId.matches("[A-Za-z0-9_-]{1,128}")
                || nodeId.equals(exitId) || !"TOOL".equals(ref.path("kind").asText())
                || !name.matches("[A-Za-z0-9._-]+:[A-Za-z0-9._:-]{1,180}")
                || ref.hasNonNull("definitionId") || ref.hasNonNull("contractHash")
                || !nodeId.equals(graph.path("entryNodeId").asText())
                || !graph.path("exitNodeIds").isArray() || graph.path("exitNodeIds").size() != 1
                || !exitId.equals(graph.path("exitNodeIds").get(0).asText())
                || !nodeId.equals(edges.get(0).path("from").asText()) || !exitId.equals(edges.get(0).path("to").asText())
                || !Set.of("", "always").contains(edges.get(0).path("condition").asText())
                || !config.path("inputMapping").isObject() || config.path("inputMapping").isEmpty()
                || config.hasNonNull("args") && !config.path("args").isEmpty()
                || !variable.path("config").path("assignments").isObject()
                || variable.path("config").path("assignments").isEmpty()) throw unsupported();
        var mappings = config.path("inputMapping").fields();
        while (mappings.hasNext()) {
            var entry = mappings.next();
            if (!entry.getKey().matches("[A-Za-z][A-Za-z0-9_-]{0,127}")
                    || !entry.getValue().isTextual()
                    || !entry.getValue().asText().matches("params\\.[A-Za-z][A-Za-z0-9_-]{0,127}")) throw unsupported();
        }
        String prefix = "nodeOutput." + nodeId + ".";
        var assignments = variable.path("config").path("assignments").fields();
        while (assignments.hasNext()) {
            var entry = assignments.next();
            if (!entry.getKey().matches("[A-Za-z][A-Za-z0-9_-]{0,127}") || !entry.getValue().isTextual()
                    || !entry.getValue().asText().startsWith(prefix)
                    || !"data".equals(entry.getValue().asText().substring(prefix.length()))) {
                throw unsupported();
            }
        }
        return new Target(nodeId, 0, name, BUSINESS_METHOD);
    }

    public static String businessOwnerRejection(ConsoleCapabilityInvocationContracts.InvocationContext owner,
                                               Long projectId, String projectCode, String qualifiedName) {
        if (owner == null || owner.contractVersion() != 1 || !BUSINESS_METHOD.equals(owner.assetType())
                || !Objects.equals(projectId, owner.projectId()) || !Objects.equals(projectCode, owner.projectCode())
                || !Objects.equals(qualifiedName, owner.qualifiedName())
                || !Objects.equals(qualifiedName, owner.sourceQualifiedName())) return "BUSINESS_METHOD_TRIAL_OWNER_CHANGED";
        if (!owner.enabled()) return "BUSINESS_METHOD_TRIAL_DISABLED";
        if (!"READY".equals(owner.sourceAvailability()) || owner.currentContractHash() == null
                || !owner.currentContractHash().matches("[0-9a-f]{64}")
                || !owner.currentContractHash().equals(owner.acceptedContractHash())
                || !owner.currentContractHash().equals(owner.sourceContractHash())) return "BUSINESS_METHOD_TRIAL_SOURCE_CHANGED";
        if (!"READ_ONLY".equals(owner.sideEffect())) return "BUSINESS_METHOD_TRIAL_READ_ONLY_REQUIRED";
        if (!owner.credentialAvailable()) return "BUSINESS_METHOD_TRIAL_CREDENTIAL_REQUIRED";
        if (owner.businessIdentityRequired()) return "BUSINESS_METHOD_TRIAL_BUSINESS_IDENTITY_REQUIRED";
        return owner.executable() ? null : "BUSINESS_METHOD_TRIAL_UNAVAILABLE";
    }

    /** Initial bounded shape: scalar declared inputs, exact params mapping and SDK data return paths. */
    public static Map<String, Object> businessInput(ObjectMapper json, String graph, Target target,
            ConsoleCapabilityInvocationContracts.InvocationContext owner, Map<String, Object> params) {
        try {
            JsonNode method = null;
            for (JsonNode node : json.readTree(graph).path("nodes")) if (target.nodeId().equals(node.path("id").asText())) method = node;
            if (method == null) throw unsupported();
            if (scalarType(owner.responseType()) == null) {
                throw new IllegalArgumentException("BUSINESS_METHOD_TRIAL_OUTPUT_CONTRACT_UNSUPPORTED");
            }
            JsonNode mapping = method.path("config").path("inputMapping");
            Map<String, ConsoleCapabilityInvocationContracts.Parameter> declarations = new LinkedHashMap<>();
            for (var field : owner.parameters()) {
                if (Set.of("RETURN", "OUTPUT", "RESPONSE").contains(String.valueOf(field.location()))) continue;
                if (!field.children().isEmpty() || scalarType(field.type()) == null
                        || Boolean.TRUE.equals(field.metadata().get("sensitive"))
                        || field.name() == null || declarations.put(field.name(), field) != null) {
                    throw new IllegalArgumentException("BUSINESS_METHOD_TRIAL_INPUT_CONTRACT_UNSUPPORTED");
                }
            }
            Map<String, Object> input = new LinkedHashMap<>();
            var mappings = mapping.fields();
            while (mappings.hasNext()) {
                var item = mappings.next();
                var field = declarations.get(item.getKey());
                if (field == null) throw new IllegalArgumentException("BUSINESS_METHOD_TRIAL_MAPPING_INVALID");
                String key = item.getValue().asText().substring(7);
                Object value = params.get(key);
                if (value == null || value instanceof String text && text.isBlank()) {
                    if (field.required()) throw new IllegalArgumentException("BUSINESS_METHOD_TRIAL_INPUT_REQUIRED");
                } else if (!scalarValue(scalarType(field.type()), value)) {
                    throw new IllegalArgumentException("BUSINESS_METHOD_TRIAL_INPUT_INVALID");
                }
                input.put(item.getKey(), value);
            }
            for (var field : declarations.values()) if (field.required() && !input.containsKey(field.name())) {
                throw new IllegalArgumentException("BUSINESS_METHOD_TRIAL_MAPPING_INVALID");
            }
            return java.util.Collections.unmodifiableMap(input);
        } catch (IllegalArgumentException invalid) { throw invalid; }
        catch (Exception invalid) { throw new IllegalArgumentException("BUSINESS_METHOD_TRIAL_MAPPING_INVALID"); }
    }

    public static String scalarType(String raw) {
        String type = raw == null ? "" : raw.replace("java.lang.", "").toLowerCase(java.util.Locale.ROOT);
        if (Set.of("string", "char", "character").contains(type)) return "string";
        if (Set.of("boolean", "bool").contains(type)) return "boolean";
        if (Set.of("byte", "short", "int", "integer", "long").contains(type)) return "integer";
        if (Set.of("number", "float", "double", "java.math.bigdecimal").contains(type)) return "number";
        return null;
    }
    private static boolean scalarValue(String type, Object value) {
        if ("string".equals(type)) return value instanceof String;
        if ("boolean".equals(type)) return value instanceof Boolean;
        return value instanceof Number number && Double.isFinite(number.doubleValue())
                && (!"integer".equals(type) || number.doubleValue() == Math.rint(number.doubleValue()));
    }
    public static String graphSha256(String graph) { return HttpApiDraftTrialPolicy.graphSha256(graph); }
    private static IllegalArgumentException unsupported() { return new IllegalArgumentException("WORKFLOW_TRIAL_GRAPH_UNSUPPORTED"); }
}

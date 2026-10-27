package com.enterprise.ai.runtime.workflow.proposal;

import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalDraft.ProposalResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.Set;

/** Parses model proposals against their explicit JSON shape before normalization. */
final class RuntimeWorkflowProposalJsonCodec {

    private static final Set<String> PROPOSAL_FIELDS = Set.of(
            "summary", "entryNodeId", "exitNodeIds", "nodes", "edges", "warnings");

    private static final Set<String> PROPOSAL_NODE_FIELDS = Set.of(
            "id", "type", "label", "description", "config", "inputs", "outputs");

    private static final Set<String> PROPOSAL_EDGE_FIELDS = Set.of(
            "id", "from", "to", "condition", "sourceHandle", "targetHandle");

    private static final Set<String> PROPOSAL_PORT_FIELDS = Set.of(
            "id", "name", "type", "required", "schema", "source");

    private static final Set<String> OBJECT_CONFIG_FIELDS = Set.of(
            "inputSchema", "outputSchema", "inputMapping", "assignments", "args", "metadata",
            "modelParams", "queryParams", "headers", "behavior", "presentation", "renderSchema");

    private final ObjectMapper objectMapper;

    RuntimeWorkflowProposalJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ProposalResponse read(String raw) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(extractJsonObject(raw));
        validateProposalJson(root);
        return objectMapper.treeToValue(root, ProposalResponse.class);
    }

    private void validateProposalJson(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("workflow proposal must be a JSON object");
        }
        assertAllowedFields(root, PROPOSAL_FIELDS, "proposal");
        requireTextField(root, "entryNodeId", "proposal.entryNodeId");
        requireStringArray(root, "exitNodeIds", "proposal.exitNodeIds", true);

        JsonNode nodes = requireArray(root, "nodes", "proposal.nodes");
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = nodes.get(index);
            String context = "proposal.nodes[" + index + "]";
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException(context + " must be an object");
            }
            assertAllowedFields(node, PROPOSAL_NODE_FIELDS, context);
            requireTextField(node, "id", context + ".id");
            requireTextField(node, "type", context + ".type");
            if (node.has("config") && !node.get("config").isObject()) {
                throw new IllegalArgumentException(context + ".config must be an object");
            }
            JsonNode config = node.get("config");
            if (config != null) {
                for (String field : OBJECT_CONFIG_FIELDS) {
                    JsonNode value = config.get(field);
                    if (value != null && !value.isNull() && !value.isObject()) {
                        throw new IllegalArgumentException(context + ".config." + field + " must be a JSON object");
                    }
                }
            }
            validatePorts(node, "inputs", context);
            validatePorts(node, "outputs", context);
        }

        JsonNode edges = requireArray(root, "edges", "proposal.edges");
        for (int index = 0; index < edges.size(); index++) {
            JsonNode edge = edges.get(index);
            String context = "proposal.edges[" + index + "]";
            if (edge == null || !edge.isObject()) {
                throw new IllegalArgumentException(context + " must be an object");
            }
            assertAllowedFields(edge, PROPOSAL_EDGE_FIELDS, context);
            requireTextField(edge, "from", context + ".from");
            requireTextField(edge, "to", context + ".to");
        }

        if (root.has("warnings")) {
            requireStringArray(root, "warnings", "proposal.warnings", false);
        }
    }

    private void validatePorts(JsonNode node, String field, String context) {
        if (!node.has(field)) {
            return;
        }
        JsonNode ports = node.get(field);
        if (!ports.isArray()) {
            throw new IllegalArgumentException(context + "." + field + " must be an array");
        }
        for (int index = 0; index < ports.size(); index++) {
            JsonNode port = ports.get(index);
            String portContext = context + "." + field + "[" + index + "]";
            if (port == null || !port.isObject()) {
                throw new IllegalArgumentException(portContext + " must be a canonical port object");
            }
            assertAllowedFields(port, PROPOSAL_PORT_FIELDS, portContext);
        }
    }

    private JsonNode requireArray(JsonNode root, String field, String context) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(context + " must be an array");
        }
        return value;
    }

    private void requireStringArray(JsonNode root, String field, String context, boolean requireNonEmpty) {
        JsonNode values = requireArray(root, field, context);
        if (requireNonEmpty && values.isEmpty()) {
            throw new IllegalArgumentException(context + " must contain at least one node id");
        }
        for (int index = 0; index < values.size(); index++) {
            JsonNode value = values.get(index);
            if (value == null || !value.isTextual() || !StringUtils.hasText(value.asText())) {
                throw new IllegalArgumentException(context + "[" + index + "] must be a non-blank string");
            }
        }
    }

    private void requireTextField(JsonNode root, String field, String context) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || !StringUtils.hasText(value.asText())) {
            throw new IllegalArgumentException(context + " must be a non-blank string");
        }
    }

    private void assertAllowedFields(JsonNode object, Set<String> allowedFields, String context) {
        object.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException("unsupported " + context + " field: " + field);
            }
        });
    }

    private String extractJsonObject(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("empty model response");
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("missing JSON object");
        }
        return text.substring(start, end + 1);
    }
}

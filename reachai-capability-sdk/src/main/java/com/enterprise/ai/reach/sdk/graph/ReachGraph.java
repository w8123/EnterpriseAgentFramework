package com.enterprise.ai.reach.sdk.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ReachGraph {

    public static final String START = "START";
    public static final String END = "END";

    private ReachGraph() {
    }

    public static Builder agent(String code) {
        return new Builder(code);
    }

    public static class Builder {
        private final String code;
        private String name;
        private String description;
        private String executionEngine = "GRAPH_SPEC";
        private String modelInstanceId;
        private String systemPrompt;
        private String visibility = "PROJECT";
        private final List<NodeDraft> nodes = new ArrayList<NodeDraft>();
        private final List<EdgeDraft> edges = new ArrayList<EdgeDraft>();
        private final Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        private NodeDraft currentNode;

        private Builder(String code) {
            this.code = requireText(code, "agent graph code");
            this.name = this.code;
        }

        public Builder name(String name) {
            this.name = textOr(name, this.name);
            return this;
        }

        public Builder description(String description) {
            this.description = trimToNull(description);
            return this;
        }

        public Builder executionEngine(String executionEngine) {
            this.executionEngine = textOr(executionEngine, this.executionEngine).toUpperCase(Locale.ROOT);
            return this;
        }

        public Builder modelInstanceId(String modelInstanceId) {
            this.modelInstanceId = trimToNull(modelInstanceId);
            if (this.modelInstanceId != null) {
                for (NodeDraft node : nodes) {
                    if ("LLM".equals(node.type)) {
                        node.config.put("modelInstanceId", this.modelInstanceId);
                    }
                }
            }
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            String text = trimToNull(systemPrompt);
            if (currentNode != null && "LLM".equals(currentNode.type)) {
                currentNode.config.put("systemPrompt", text);
            } else {
                this.systemPrompt = text;
            }
            return this;
        }

        public Builder visibility(String visibility) {
            this.visibility = textOr(visibility, this.visibility).toUpperCase(Locale.ROOT);
            return this;
        }

        public Builder metadata(String key, Object value) {
            if (hasText(key) && value != null) {
                metadata.put(key.trim(), value);
            }
            return this;
        }

        public Builder llm(String id) {
            currentNode = addNode(id, "LLM");
            if (modelInstanceId != null) {
                currentNode.config.put("modelInstanceId", modelInstanceId);
            }
            return this;
        }

        public Builder capability(String id) {
            return tool(id);
        }

        public Builder tool(String id) {
            currentNode = addNode(id, "TOOL");
            currentNode.ref.setKind("TOOL");
            return this;
        }

        public Builder answer(String id) {
            currentNode = addNode(id, "ANSWER");
            return this;
        }

        public Builder nodeName(String name) {
            requireCurrentNode().name = trimToNull(name);
            return this;
        }

        public Builder nodeDescription(String description) {
            requireCurrentNode().description = trimToNull(description);
            return this;
        }

        public Builder qualifiedName(String qualifiedName) {
            NodeDraft node = requireCurrentNode();
            String text = requireText(qualifiedName, "qualified name");
            node.ref.setQualifiedName(text);
            node.ref.setName(lastSegment(text));
            node.config.put("qualifiedName", text);
            return this;
        }

        public Builder systemPrompt(String id, String systemPrompt) {
            findNode(requireText(id, "node id")).config.put("systemPrompt", trimToNull(systemPrompt));
            return this;
        }

        public Builder userPrompt(String userPrompt) {
            requireCurrentNode().config.put("userPrompt", trimToNull(userPrompt));
            return this;
        }

        public Builder input(String name, String expression) {
            NodeDraft node = requireCurrentNode();
            String inputName = requireText(name, "input name");
            String inputExpression = requireText(expression, "input expression");
            node.inputMapping.put(inputName, inputExpression);
            return this;
        }

        public Builder outputAlias(String alias) {
            String text = trimToNull(alias);
            if (text != null) {
                requireCurrentNode().config.put("outputAlias", text);
            }
            return this;
        }

        public Builder from(String expression) {
            requireCurrentNode().config.put("inputExpression", trimToNull(expression));
            return this;
        }

        public Builder edge(String from, String to) {
            edges.add(new EdgeDraft(requireText(from, "edge source"), requireText(to, "edge target")));
            return this;
        }

        public ReachAgentGraph build() {
            validate();
            ReachGraphSpec spec = new ReachGraphSpec();
            spec.setNodes(toNodes());
            spec.setEdges(toEdges(spec));
            if (!hasText(spec.getEntryNodeId())) {
                spec.setEntryNodeId(firstExecutableNodeId());
            }
            if (spec.getExitNodeIds().isEmpty()) {
                spec.getExitNodeIds().add(lastExecutableNodeId());
            }
            return new ReachAgentGraph(code, name, description, executionEngine, modelInstanceId, systemPrompt,
                    visibility, spec, metadata);
        }

        private NodeDraft addNode(String id, String type) {
            NodeDraft node = new NodeDraft(requireText(id, "node id"), type);
            nodes.add(node);
            return node;
        }

        private List<ReachGraphSpec.Node> toNodes() {
            List<ReachGraphSpec.Node> result = new ArrayList<ReachGraphSpec.Node>();
            for (NodeDraft draft : nodes) {
                if (!draft.inputMapping.isEmpty()) {
                    draft.config.put("inputMapping", new LinkedHashMap<String, Object>(draft.inputMapping));
                }
                ReachGraphSpec.Node node = new ReachGraphSpec.Node();
                node.setId(draft.id);
                node.setType(draft.type);
                node.setName(draft.name);
                node.setDescription(draft.description);
                if (hasRef(draft.ref)) {
                    node.setRef(draft.ref);
                }
                node.setConfig(draft.config.isEmpty() ? null : new LinkedHashMap<String, Object>(draft.config));
                result.add(node);
            }
            return result;
        }

        private List<ReachGraphSpec.Edge> toEdges(ReachGraphSpec spec) {
            List<ReachGraphSpec.Edge> result = new ArrayList<ReachGraphSpec.Edge>();
            for (EdgeDraft draft : edges) {
                if (START.equals(draft.from)) {
                    spec.setEntryNodeId(draft.to);
                    continue;
                }
                if (END.equals(draft.to)) {
                    spec.getExitNodeIds().add(draft.from);
                    continue;
                }
                ReachGraphSpec.Edge edge = new ReachGraphSpec.Edge();
                edge.setFrom(draft.from);
                edge.setTo(draft.to);
                edge.setCondition("always");
                result.add(edge);
            }
            return result;
        }

        private void validate() {
            Set<String> ids = new LinkedHashSet<String>();
            boolean hasLlm = false;
            for (NodeDraft node : nodes) {
                if (!ids.add(node.id)) {
                    throw new IllegalArgumentException("duplicate graph node id: " + node.id);
                }
                hasLlm = hasLlm || "LLM".equals(node.type);
            }
            if (nodes.isEmpty()) {
                throw new IllegalArgumentException("agent graph must declare at least one node");
            }
            if (!hasLlm) {
                throw new IllegalArgumentException("agent graph must declare at least one LLM node");
            }
            for (EdgeDraft edge : edges) {
                requireEndpoint(ids, edge.from);
                requireEndpoint(ids, edge.to);
            }
        }

        private void requireEndpoint(Set<String> ids, String endpoint) {
            if (START.equals(endpoint) || END.equals(endpoint)) {
                return;
            }
            if (!ids.contains(endpoint)) {
                throw new IllegalArgumentException("unknown graph edge endpoint: " + endpoint);
            }
        }

        private NodeDraft requireCurrentNode() {
            if (currentNode == null) {
                throw new IllegalStateException("No current node. Call a node builder method first.");
            }
            return currentNode;
        }

        private NodeDraft findNode(String id) {
            for (NodeDraft node : nodes) {
                if (node.id.equals(id)) {
                    return node;
                }
            }
            throw new IllegalArgumentException("unknown graph node: " + id);
        }

        private String firstExecutableNodeId() {
            return nodes.get(0).id;
        }

        private String lastExecutableNodeId() {
            return nodes.get(nodes.size() - 1).id;
        }
    }

    private static class NodeDraft {
        private final String id;
        private final String type;
        private String name;
        private String description;
        private final ReachGraphSpec.CapabilityRef ref = new ReachGraphSpec.CapabilityRef();
        private final Map<String, Object> config = new LinkedHashMap<String, Object>();
        private final Map<String, String> inputMapping = new LinkedHashMap<String, String>();

        private NodeDraft(String id, String type) {
            this.id = id;
            this.type = type;
            this.name = id;
        }
    }

    private static class EdgeDraft {
        private final String from;
        private final String to;

        private EdgeDraft(String from, String to) {
            this.from = normalizeEndpoint(from);
            this.to = normalizeEndpoint(to);
        }
    }

    private static boolean hasRef(ReachGraphSpec.CapabilityRef ref) {
        return ref != null && (hasText(ref.getKind()) || hasText(ref.getName()) || hasText(ref.getQualifiedName()));
    }

    private static String normalizeEndpoint(String value) {
        String text = requireText(value, "graph endpoint");
        if ("start".equalsIgnoreCase(text)) {
            return START;
        }
        if ("end".equalsIgnoreCase(text)) {
            return END;
        }
        return text;
    }

    private static String lastSegment(String value) {
        int index = value.lastIndexOf('.');
        return index >= 0 ? value.substring(index + 1) : value;
    }

    private static String requireText(String value, String label) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static String textOr(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

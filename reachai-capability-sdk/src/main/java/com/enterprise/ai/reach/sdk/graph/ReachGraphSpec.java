package com.enterprise.ai.reach.sdk.graph;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReachGraphSpec {

    private Integer schemaVersion = Integer.valueOf(2);
    private Map<String, Object> inputSchema;
    private Map<String, Object> stateSchema;
    private List<Node> nodes = new ArrayList<Node>();
    private List<Edge> edges = new ArrayList<Edge>();
    private String entryNodeId;
    private List<String> exitNodeIds = new ArrayList<String>();

    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(Integer schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    public void setInputSchema(Map<String, Object> inputSchema) {
        this.inputSchema = inputSchema;
    }

    public Map<String, Object> getStateSchema() {
        return stateSchema;
    }

    public void setStateSchema(Map<String, Object> stateSchema) {
        this.stateSchema = stateSchema;
    }

    public List<Node> getNodes() {
        return nodes;
    }

    public void setNodes(List<Node> nodes) {
        this.nodes = nodes;
    }

    public List<Edge> getEdges() {
        return edges;
    }

    public void setEdges(List<Edge> edges) {
        this.edges = edges;
    }

    public String getEntryNodeId() {
        return entryNodeId;
    }

    public void setEntryNodeId(String entryNodeId) {
        this.entryNodeId = entryNodeId;
    }

    public List<String> getExitNodeIds() {
        return exitNodeIds;
    }

    public void setExitNodeIds(List<String> exitNodeIds) {
        this.exitNodeIds = exitNodeIds;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Node {
        private String id;
        private String type;
        private String name;
        private String description;
        private CapabilityRef ref;
        private List<Port> inputs;
        private List<Port> outputs;
        private Map<String, Object> inputSchema;
        private Map<String, Object> outputSchema;
        private RetryPolicy retry;
        private ErrorPolicy errorPolicy;
        private Map<String, Object> config;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public CapabilityRef getRef() {
            return ref;
        }

        public void setRef(CapabilityRef ref) {
            this.ref = ref;
        }

        public List<Port> getInputs() {
            return inputs;
        }

        public void setInputs(List<Port> inputs) {
            this.inputs = inputs;
        }

        public List<Port> getOutputs() {
            return outputs;
        }

        public void setOutputs(List<Port> outputs) {
            this.outputs = outputs;
        }

        public Map<String, Object> getInputSchema() {
            return inputSchema;
        }

        public void setInputSchema(Map<String, Object> inputSchema) {
            this.inputSchema = inputSchema;
        }

        public Map<String, Object> getOutputSchema() {
            return outputSchema;
        }

        public void setOutputSchema(Map<String, Object> outputSchema) {
            this.outputSchema = outputSchema;
        }

        public RetryPolicy getRetry() {
            return retry;
        }

        public void setRetry(RetryPolicy retry) {
            this.retry = retry;
        }

        public ErrorPolicy getErrorPolicy() {
            return errorPolicy;
        }

        public void setErrorPolicy(ErrorPolicy errorPolicy) {
            this.errorPolicy = errorPolicy;
        }

        public Map<String, Object> getConfig() {
            return config;
        }

        public void setConfig(Map<String, Object> config) {
            this.config = config;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Edge {
        private String id;
        private String from;
        private String to;
        private String condition;
        private String sourceHandle;
        private String targetHandle;
        private Integer priority;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getTo() {
            return to;
        }

        public void setTo(String to) {
            this.to = to;
        }

        public String getCondition() {
            return condition;
        }

        public void setCondition(String condition) {
            this.condition = condition;
        }

        public String getSourceHandle() {
            return sourceHandle;
        }

        public void setSourceHandle(String sourceHandle) {
            this.sourceHandle = sourceHandle;
        }

        public String getTargetHandle() {
            return targetHandle;
        }

        public void setTargetHandle(String targetHandle) {
            this.targetHandle = targetHandle;
        }

        public Integer getPriority() {
            return priority;
        }

        public void setPriority(Integer priority) {
            this.priority = priority;
        }

    }

    public static class CapabilityRef {
        private String kind;
        private String name;
        private String qualifiedName;
        private Long definitionId;
        private String projectCode;

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getQualifiedName() {
            return qualifiedName;
        }

        public void setQualifiedName(String qualifiedName) {
            this.qualifiedName = qualifiedName;
        }

        public Long getDefinitionId() {
            return definitionId;
        }

        public void setDefinitionId(Long definitionId) {
            this.definitionId = definitionId;
        }

        public String getProjectCode() {
            return projectCode;
        }

        public void setProjectCode(String projectCode) {
            this.projectCode = projectCode;
        }
    }

    public static class Port {
        private String id;
        private String name;
        private String type;
        private Boolean required;
        private String schema;
        private String source;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Boolean getRequired() {
            return required;
        }

        public void setRequired(Boolean required) {
            this.required = required;
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }
    }

    public static class RetryPolicy {
        private Boolean enabled;
        private Integer maxAttempts;
        private Long backoffMs;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(Integer maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Long getBackoffMs() {
            return backoffMs;
        }

        public void setBackoffMs(Long backoffMs) {
            this.backoffMs = backoffMs;
        }
    }

    public static class ErrorPolicy {
        private String strategy;
        private String fallbackNodeId;
        private Map<String, Object> defaultOutput;

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public String getFallbackNodeId() {
            return fallbackNodeId;
        }

        public void setFallbackNodeId(String fallbackNodeId) {
            this.fallbackNodeId = fallbackNodeId;
        }

        public Map<String, Object> getDefaultOutput() {
            return defaultOutput;
        }

        public void setDefaultOutput(Map<String, Object> defaultOutput) {
            this.defaultOutput = defaultOutput;
        }
    }

}

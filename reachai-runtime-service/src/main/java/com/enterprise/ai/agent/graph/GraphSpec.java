package com.enterprise.ai.agent.graph;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Singular;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphSpec {

    @Builder.Default
    private Integer schemaVersion = 2;

    private Map<String, Object> inputSchema;

    private Map<String, Object> stateSchema;

    @Singular
    private List<Node> nodes;

    @Singular
    private List<Edge> edges;

    private String entryNodeId;

    private List<String> exitNodeIds;

    public List<String> getExitNodeIds() {
        return exitNodeIds == null ? List.of() : exitNodeIds;
    }

    @JsonAnySetter
    private void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported GraphSpec field: " + field);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Node {
        private String id;
        private String type;
        private String name;
        private String description;
        private CapabilityRef ref;
        @Singular
        private List<Port> inputs;
        @Singular
        private List<Port> outputs;
        private Map<String, Object> inputSchema;
        private Map<String, Object> outputSchema;
        private RetryPolicy retry;
        private ErrorPolicy errorPolicy;
        private Map<String, Object> config;

        @JsonAnySetter
        private void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("Unsupported GraphSpec node field: " + field);
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Edge {
        private String id;
        private String from;
        private String to;
        private String condition;
        private String sourceHandle;
        private String targetHandle;
        private Integer priority;

        @JsonAnySetter
        private void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("Unsupported GraphSpec edge field: " + field);
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CapabilityRef {
        private String kind;
        private String name;
        private String qualifiedName;
        private Long definitionId;
        private String projectCode;
        private String contractHash;

        @JsonAnySetter
        private void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("Unsupported GraphSpec capability ref field: " + field);
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Port {
        private String id;
        private String name;
        private String type;
        private Boolean required;
        private String schema;
        private String source;

        @JsonAnySetter
        private void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("Unsupported GraphSpec port field: " + field);
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetryPolicy {
        private Boolean enabled;
        private Integer maxAttempts;
        private Long backoffMs;

        @JsonAnySetter
        private void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("Unsupported GraphSpec retry field: " + field);
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorPolicy {
        private String strategy;
        private String fallbackNodeId;
        private Map<String, Object> defaultOutput;

        @JsonAnySetter
        private void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("Unsupported GraphSpec error policy field: " + field);
        }
    }

}

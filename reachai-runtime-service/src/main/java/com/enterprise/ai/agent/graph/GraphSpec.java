package com.enterprise.ai.agent.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class GraphSpec {

    private String code;

    private String name;

    @Builder.Default
    private String mode = "WORKFLOW";

    private String runtimeHint;

    private Map<String, Object> inputSchema;

    private Map<String, Object> stateSchema;

    private Layout layout;

    @Singular
    private List<Node> nodes;

    @Singular
    private List<Edge> edges;

    private String entry;

    @Singular("finishNode")
    private List<String> finish;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
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
        private Layout.NodeLayout layout;
        private Map<String, Object> config;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Edge {
        private String id;
        private String from;
        private String to;
        private String condition;
        private String sourceHandle;
        private String targetHandle;
        private Integer priority;
        private Layout.EdgeLayout layout;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CapabilityRef {
        private String kind;
        private String name;
        private String qualifiedName;
        private Long definitionId;
        private String projectCode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Port {
        private String id;
        private String name;
        private String type;
        private Boolean required;
        private String schema;
        private String source;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RetryPolicy {
        private Boolean enabled;
        private Integer maxAttempts;
        private Long backoffMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ErrorPolicy {
        private String strategy;
        private String fallbackNodeId;
        private Map<String, Object> defaultOutput;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Layout {
        private String engine;
        private String direction;
        private Map<String, Object> viewport;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class NodeLayout {
            private Double x;
            private Double y;
            private Double width;
            private Double height;
            private Boolean collapsed;
        }

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class EdgeLayout {
            private String label;
            private String style;
        }
    }
}

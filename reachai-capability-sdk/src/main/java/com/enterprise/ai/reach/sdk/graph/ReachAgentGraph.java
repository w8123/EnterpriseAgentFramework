package com.enterprise.ai.reach.sdk.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ReachAgentGraph {

    private final String code;
    private final String name;
    private final String description;
    private final String executionEngine;
    private final String modelInstanceId;
    private final String systemPrompt;
    private final String visibility;
    private final ReachGraphSpec graphSpec;
    private final Map<String, Object> metadata;

    public ReachAgentGraph(String code,
                           String name,
                           String description,
                           String executionEngine,
                           String modelInstanceId,
                           String systemPrompt,
                           String visibility,
                           ReachGraphSpec graphSpec,
                           Map<String, Object> metadata) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.executionEngine = executionEngine;
        this.modelInstanceId = modelInstanceId;
        this.systemPrompt = systemPrompt;
        this.visibility = visibility;
        this.graphSpec = graphSpec;
        this.metadata = metadata == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(metadata));
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getExecutionEngine() {
        return executionEngine;
    }

    public String getModelInstanceId() {
        return modelInstanceId;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getVisibility() {
        return visibility;
    }

    public ReachGraphSpec getGraphSpec() {
        return graphSpec;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}

package com.enterprise.ai.agent.workflow.aicoding;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowAiCodingCreateRequest {

    private String name;

    private String keySlug;

    private Long projectId;

    private String projectCode;

    private String description;

    private String workflowType;

    private String runtimeType;

    private String defaultModelInstanceId;

    private GraphSpec graphSpec;

    private Map<String, Object> canvas;

    private Map<String, Object> extra;

    private String reason;
}

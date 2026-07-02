package com.enterprise.ai.runtime.workflow.draft;

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
public class RuntimeWorkflowDraftEditOperationView {

    private RuntimeWorkflowDraftEditOperationType type;

    private String nodeId;

    private String edgeId;

    private Map<String, Object> node;

    private Map<String, Object> edge;

    private Map<String, Object> patch;

    private String reason;
}

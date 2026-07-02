package com.enterprise.ai.runtime.registry;

import com.enterprise.ai.agent.graph.GraphSpec;

import java.util.List;
import java.util.Map;

public final class RuntimeAgentGraphSyncContracts {

    private RuntimeAgentGraphSyncContracts() {
    }

    public record AgentGraphSyncRequest(
            String syncId,
            String source,
            Boolean apply,
            List<AgentGraphRegistration> graphs
    ) {
    }

    public record AgentGraphRegistration(
            String code,
            String name,
            String description,
            String runtimeType,
            String modelInstanceId,
            String systemPrompt,
            String visibility,
            GraphSpec graphSpec,
            Map<String, Object> metadata
    ) {
    }

    public record AgentGraphSyncResponse(
            String syncId,
            Long projectId,
            String projectCode,
            int received,
            int created,
            int updated,
            List<AgentGraphSyncItem> items
    ) {
    }

    public record AgentGraphSyncItem(
            String graphCode,
            String workflowId,
            String keySlug,
            String changeType,
            String message
    ) {
    }
}

package com.enterprise.ai.agent.capability.catalog.scan;

import java.util.List;

public interface ScanProjectAgentReferenceReader {

    List<AgentToolReference> listAgentToolReferences();

    record AgentToolReference(
            String agentId,
            String agentName,
            List<String> tools,
            List<String> skills) {
    }
}

package com.enterprise.ai.agent.capability.catalog.scan;

import java.util.List;

public record ScanProjectBlockers(
        boolean blocked,
        List<String> tools,
        List<String> skills,
        List<AgentRef> agents) {

    public static ScanProjectBlockers empty() {
        return new ScanProjectBlockers(false, List.of(), List.of(), List.of());
    }

    public record AgentRef(String agentId, String agentName) {
    }
}

package com.enterprise.ai.agent.capability.catalog.scan;

import java.util.List;

public record ScanProjectBlockers(
        boolean blocked,
        List<String> tools,
        List<AgentRef> agents,
        List<AssetRef> assets) {

    public static ScanProjectBlockers empty() {
        return new ScanProjectBlockers(false, List.of(), List.of(), List.of());
    }

    public enum Operation { DELETE, RESCAN }

    public record AssetRef(String assetType, Long assetId, String qualifiedName, String title) {
    }

    public record AgentRef(String agentId, String agentName) {
    }
}

package com.enterprise.ai.capability.catalog.graph;

import java.util.List;

public record CapabilityApiGraphSnapshotView(
        List<NodeView> nodes,
        List<EdgeView> edges,
        List<LayoutView> layouts) {

    public CapabilityApiGraphSnapshotView {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        layouts = layouts == null ? List.of() : List.copyOf(layouts);
    }

    public record NodeView(
            Long id,
            Long projectId,
            String kind,
            Long refId,
            Long parentId,
            String label,
            String typeName,
            String propsJson) {
    }

    public record EdgeView(
            Long id,
            Long projectId,
            Long sourceNodeId,
            Long targetNodeId,
            String kind,
            String source,
            Double confidence,
            String status,
            String inferStrategy,
            String confirmedBy,
            String confirmedAt,
            String rejectReason,
            String evidenceJson,
            String note,
            boolean enabled) {
    }

    public record LayoutView(Long nodeId, Double x, Double y, String extJson) {
    }
}

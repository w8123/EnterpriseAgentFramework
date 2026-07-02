package com.enterprise.ai.capability.catalog.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphEdgeEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphEdgeMapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphLayoutEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphLayoutMapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphNodeEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphNodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CapabilityApiGraphSnapshotService {

    private final ApiGraphNodeMapper nodeMapper;
    private final ApiGraphEdgeMapper edgeMapper;
    private final ApiGraphLayoutMapper layoutMapper;

    public CapabilityApiGraphSnapshotView loadSnapshot(Long projectId) {
        return new CapabilityApiGraphSnapshotView(
                nodeMapper.selectList(new LambdaQueryWrapper<ApiGraphNodeEntity>()
                                .eq(ApiGraphNodeEntity::getProjectId, projectId)
                                .orderByAsc(ApiGraphNodeEntity::getId))
                        .stream()
                        .map(CapabilityApiGraphSnapshotService::toNodeView)
                        .toList(),
                edgeMapper.selectList(new LambdaQueryWrapper<ApiGraphEdgeEntity>()
                                .eq(ApiGraphEdgeEntity::getProjectId, projectId)
                                .eq(ApiGraphEdgeEntity::getEnabled, true)
                                .orderByAsc(ApiGraphEdgeEntity::getId))
                        .stream()
                        .map(CapabilityApiGraphSnapshotService::toEdgeView)
                        .toList(),
                layoutMapper.selectList(new LambdaQueryWrapper<ApiGraphLayoutEntity>()
                                .eq(ApiGraphLayoutEntity::getProjectId, projectId))
                        .stream()
                        .map(CapabilityApiGraphSnapshotService::toLayoutView)
                        .toList());
    }

    private static CapabilityApiGraphSnapshotView.NodeView toNodeView(ApiGraphNodeEntity entity) {
        return new CapabilityApiGraphSnapshotView.NodeView(
                entity.getId(),
                entity.getProjectId(),
                entity.getKind(),
                entity.getRefId(),
                entity.getParentId(),
                entity.getLabel(),
                entity.getTypeName(),
                entity.getPropsJson());
    }

    private static CapabilityApiGraphSnapshotView.EdgeView toEdgeView(ApiGraphEdgeEntity entity) {
        return new CapabilityApiGraphSnapshotView.EdgeView(
                entity.getId(),
                entity.getProjectId(),
                entity.getSourceNodeId(),
                entity.getTargetNodeId(),
                entity.getKind(),
                entity.getSource(),
                entity.getConfidence(),
                entity.getStatus(),
                entity.getInferStrategy(),
                entity.getConfirmedBy(),
                entity.getConfirmedAt() == null ? null : entity.getConfirmedAt().toString(),
                entity.getRejectReason(),
                entity.getEvidenceJson(),
                entity.getNote(),
                Boolean.TRUE.equals(entity.getEnabled()));
    }

    private static CapabilityApiGraphSnapshotView.LayoutView toLayoutView(ApiGraphLayoutEntity entity) {
        return new CapabilityApiGraphSnapshotView.LayoutView(
                entity.getNodeId(),
                entity.getX(),
                entity.getY(),
                entity.getExtJson());
    }
}

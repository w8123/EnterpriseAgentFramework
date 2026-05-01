package com.enterprise.ai.agent.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 默认 MySQL 实现。规模假设：单项目 ≤ 5K 节点 / 10K 边，全表查询走索引秒级返回。
 */
@Repository
public class MysqlApiGraphRepository implements ApiGraphRepository {

    private final ApiGraphNodeMapper nodeMapper;
    private final ApiGraphEdgeMapper edgeMapper;
    private final ApiGraphLayoutMapper layoutMapper;

    public MysqlApiGraphRepository(ApiGraphNodeMapper nodeMapper,
                                   ApiGraphEdgeMapper edgeMapper,
                                   ApiGraphLayoutMapper layoutMapper) {
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.layoutMapper = layoutMapper;
    }

    @Override
    public ApiGraphNodeEntity upsertNode(ApiGraphNodeEntity node) {
        Optional<ApiGraphNodeEntity> existing = findNode(
                node.getProjectId(), node.getKind(), node.getRefId(), node.getParentId(), node.getLabel());
        if (existing.isPresent()) {
            ApiGraphNodeEntity ent = existing.get();
            ent.setTypeName(node.getTypeName());
            ent.setPropsJson(node.getPropsJson());
            nodeMapper.updateById(ent);
            return ent;
        }
        nodeMapper.insert(node);
        return node;
    }

    @Override
    public Optional<ApiGraphNodeEntity> findNode(Long projectId, String kind, Long refId, Long parentId, String label) {
        LambdaQueryWrapper<ApiGraphNodeEntity> qw = new LambdaQueryWrapper<ApiGraphNodeEntity>()
                .eq(ApiGraphNodeEntity::getProjectId, projectId)
                .eq(ApiGraphNodeEntity::getKind, kind)
                .eq(ApiGraphNodeEntity::getLabel, label)
                .last("limit 1");
        if (refId == null) {
            qw.isNull(ApiGraphNodeEntity::getRefId);
        } else {
            qw.eq(ApiGraphNodeEntity::getRefId, refId);
        }
        if (parentId == null) {
            qw.isNull(ApiGraphNodeEntity::getParentId);
        } else {
            qw.eq(ApiGraphNodeEntity::getParentId, parentId);
        }
        return Optional.ofNullable(nodeMapper.selectOne(qw));
    }

    @Override
    public List<ApiGraphNodeEntity> listNodesByProject(Long projectId) {
        return nodeMapper.selectList(new LambdaQueryWrapper<ApiGraphNodeEntity>()
                .eq(ApiGraphNodeEntity::getProjectId, projectId)
                .orderByAsc(ApiGraphNodeEntity::getId));
    }

    @Override
    public List<ApiGraphNodeEntity> listNodesByProjectAndKind(Long projectId, String kind) {
        return nodeMapper.selectList(new LambdaQueryWrapper<ApiGraphNodeEntity>()
                .eq(ApiGraphNodeEntity::getProjectId, projectId)
                .eq(ApiGraphNodeEntity::getKind, kind));
    }

    @Override
    public int deleteNodesNotIn(Long projectId, Collection<Long> keepIds) {
        LambdaQueryWrapper<ApiGraphNodeEntity> qw = new LambdaQueryWrapper<ApiGraphNodeEntity>()
                .eq(ApiGraphNodeEntity::getProjectId, projectId);
        if (keepIds != null && !keepIds.isEmpty()) {
            qw.notIn(ApiGraphNodeEntity::getId, keepIds);
        }
        // 先删被孤立的节点关联的边
        List<ApiGraphNodeEntity> orphans = nodeMapper.selectList(qw);
        if (orphans.isEmpty()) {
            return 0;
        }
        deleteEdgesByNodeIds(orphans.stream().map(ApiGraphNodeEntity::getId).toList());
        return nodeMapper.delete(qw);
    }

    @Override
    public int deleteAutoEdges(Long projectId, String kind) {
        LambdaQueryWrapper<ApiGraphEdgeEntity> qw = new LambdaQueryWrapper<ApiGraphEdgeEntity>()
                .eq(ApiGraphEdgeEntity::getProjectId, projectId)
                .eq(ApiGraphEdgeEntity::getSource, ApiGraphEdgeKind.SOURCE_AUTO);
        if (kind != null) {
            qw.eq(ApiGraphEdgeEntity::getKind, kind);
        }
        return edgeMapper.delete(qw);
    }

    @Override
    public int deleteEdgesByNodeIds(Collection<Long> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return 0;
        }
        return edgeMapper.delete(new LambdaQueryWrapper<ApiGraphEdgeEntity>()
                .in(ApiGraphEdgeEntity::getSourceNodeId, nodeIds)
                .or()
                .in(ApiGraphEdgeEntity::getTargetNodeId, nodeIds));
    }

    @Override
    public int deleteByProject(Long projectId) {
        edgeMapper.delete(new LambdaQueryWrapper<ApiGraphEdgeEntity>()
                .eq(ApiGraphEdgeEntity::getProjectId, projectId));
        layoutMapper.delete(new LambdaQueryWrapper<ApiGraphLayoutEntity>()
                .eq(ApiGraphLayoutEntity::getProjectId, projectId));
        return nodeMapper.delete(new LambdaQueryWrapper<ApiGraphNodeEntity>()
                .eq(ApiGraphNodeEntity::getProjectId, projectId));
    }

    @Override
    public ApiGraphEdgeEntity upsertEdge(ApiGraphEdgeEntity edge) {
        ApiGraphEdgeEntity existing = edgeMapper.selectOne(new LambdaQueryWrapper<ApiGraphEdgeEntity>()
                .eq(ApiGraphEdgeEntity::getProjectId, edge.getProjectId())
                .eq(ApiGraphEdgeEntity::getKind, edge.getKind())
                .eq(ApiGraphEdgeEntity::getSourceNodeId, edge.getSourceNodeId())
                .eq(ApiGraphEdgeEntity::getTargetNodeId, edge.getTargetNodeId())
                .eq(ApiGraphEdgeEntity::getSource, edge.getSource())
                .last("limit 1"));
        if (existing != null) {
            existing.setConfidence(edge.getConfidence());
            existing.setEvidenceJson(edge.getEvidenceJson());
            existing.setStatus(edge.getStatus());
            existing.setInferStrategy(edge.getInferStrategy());
            existing.setConfirmedBy(edge.getConfirmedBy());
            existing.setConfirmedAt(edge.getConfirmedAt());
            existing.setRejectReason(edge.getRejectReason());
            existing.setEnabled(edge.getEnabled() == null ? Boolean.TRUE : edge.getEnabled());
            if (edge.getNote() != null) {
                existing.setNote(edge.getNote());
            }
            edgeMapper.updateById(existing);
            return existing;
        }
        if (edge.getEnabled() == null) {
            edge.setEnabled(Boolean.TRUE);
        }
        edgeMapper.insert(edge);
        return edge;
    }

    @Override
    public Optional<ApiGraphEdgeEntity> findEdgeById(Long edgeId) {
        return Optional.ofNullable(edgeMapper.selectById(edgeId));
    }

    @Override
    public List<ApiGraphEdgeEntity> listEdgesByProject(Long projectId) {
        return edgeMapper.selectList(new LambdaQueryWrapper<ApiGraphEdgeEntity>()
                .eq(ApiGraphEdgeEntity::getProjectId, projectId)
                .eq(ApiGraphEdgeEntity::getEnabled, true)
                .orderByAsc(ApiGraphEdgeEntity::getId));
    }

    @Override
    public int deleteEdgeById(Long edgeId) {
        return edgeMapper.deleteById(edgeId);
    }

    @Override
    public void upsertLayout(ApiGraphLayoutEntity layout) {
        ApiGraphLayoutEntity existing = layoutMapper.selectOne(new LambdaQueryWrapper<ApiGraphLayoutEntity>()
                .eq(ApiGraphLayoutEntity::getProjectId, layout.getProjectId())
                .eq(ApiGraphLayoutEntity::getNodeId, layout.getNodeId())
                .last("limit 1"));
        if (existing != null) {
            LambdaUpdateWrapper<ApiGraphLayoutEntity> uw = new LambdaUpdateWrapper<ApiGraphLayoutEntity>()
                    .eq(ApiGraphLayoutEntity::getId, existing.getId())
                    .set(ApiGraphLayoutEntity::getX, layout.getX())
                    .set(ApiGraphLayoutEntity::getY, layout.getY())
                    .set(ApiGraphLayoutEntity::getExtJson, layout.getExtJson());
            layoutMapper.update(null, uw);
            return;
        }
        layoutMapper.insert(layout);
    }

    @Override
    public List<ApiGraphLayoutEntity> listLayoutByProject(Long projectId) {
        return layoutMapper.selectList(new LambdaQueryWrapper<ApiGraphLayoutEntity>()
                .eq(ApiGraphLayoutEntity::getProjectId, projectId));
    }

    @Override
    public int deleteLayoutByProject(Long projectId) {
        return layoutMapper.delete(new LambdaQueryWrapper<ApiGraphLayoutEntity>()
                .eq(ApiGraphLayoutEntity::getProjectId, projectId));
    }
}

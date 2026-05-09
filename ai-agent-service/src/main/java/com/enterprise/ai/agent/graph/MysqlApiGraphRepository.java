package com.enterprise.ai.agent.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 默认 MySQL 实现。规模假设：单项目 ≤ 5K 节点 / 10K 边，全表查询走索引秒级返回。
 */
@Repository
public class MysqlApiGraphRepository implements ApiGraphRepository {

    private record NodeKey(Long projectId, String kind, Long refId, Long parentId, String label) {
        static NodeKey from(ApiGraphNodeEntity n) {
            return new NodeKey(n.getProjectId(), n.getKind(), n.getRefId(), n.getParentId(), n.getLabel());
        }
    }

    private record EdgeKey(Long projectId, String kind, Long sourceNodeId, Long targetNodeId, String source) {
        static EdgeKey from(ApiGraphEdgeEntity e) {
            return new EdgeKey(e.getProjectId(), e.getKind(), e.getSourceNodeId(), e.getTargetNodeId(), e.getSource());
        }
    }

    private final ApiGraphNodeMapper nodeMapper;
    private final ApiGraphEdgeMapper edgeMapper;
    private final ApiGraphLayoutMapper layoutMapper;

    /** 非空时表示处于图谱全量投影阶段，upsert 走内存索引。 */
    private Long rebuildProjectId;
    private Map<NodeKey, ApiGraphNodeEntity> rebuildNodeByKey;
    private Map<EdgeKey, ApiGraphEdgeEntity> rebuildEdgeByKey;

    public MysqlApiGraphRepository(ApiGraphNodeMapper nodeMapper,
                                   ApiGraphEdgeMapper edgeMapper,
                                   ApiGraphLayoutMapper layoutMapper) {
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.layoutMapper = layoutMapper;
    }

    @Override
    public void beginRebuildCaches(Long projectId) {
        this.rebuildProjectId = projectId;
        this.rebuildNodeByKey = new HashMap<>(8192);
        for (ApiGraphNodeEntity n : nodeMapper.selectList(new LambdaQueryWrapper<ApiGraphNodeEntity>()
                .eq(ApiGraphNodeEntity::getProjectId, projectId))) {
            rebuildNodeByKey.put(NodeKey.from(n), n);
        }
        this.rebuildEdgeByKey = new HashMap<>(16384);
        for (ApiGraphEdgeEntity e : edgeMapper.selectList(new LambdaQueryWrapper<ApiGraphEdgeEntity>()
                .eq(ApiGraphEdgeEntity::getProjectId, projectId))) {
            rebuildEdgeByKey.put(EdgeKey.from(e), e);
        }
    }

    @Override
    public void endRebuildCaches() {
        this.rebuildProjectId = null;
        this.rebuildNodeByKey = null;
        this.rebuildEdgeByKey = null;
    }

    private boolean rebuildCachesActive(Long projectId) {
        return rebuildProjectId != null
                && rebuildNodeByKey != null
                && rebuildEdgeByKey != null
                && Objects.equals(rebuildProjectId, projectId);
    }

    @Override
    public ApiGraphNodeEntity upsertNode(ApiGraphNodeEntity node) {
        if (rebuildCachesActive(node.getProjectId())) {
            NodeKey key = NodeKey.from(node);
            ApiGraphNodeEntity existing = rebuildNodeByKey.get(key);
            if (existing != null) {
                if (!Objects.equals(existing.getTypeName(), node.getTypeName())
                        || !Objects.equals(existing.getPropsJson(), node.getPropsJson())) {
                    existing.setTypeName(node.getTypeName());
                    existing.setPropsJson(node.getPropsJson());
                    nodeMapper.updateById(existing);
                }
                return existing;
            }
            nodeMapper.insert(node);
            rebuildNodeByKey.put(key, node);
            return node;
        }
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
        if (rebuildCachesActive(projectId)) {
            NodeKey key = new NodeKey(projectId, kind, refId, parentId, label);
            return Optional.ofNullable(rebuildNodeByKey.get(key));
        }
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
        int deleted = nodeMapper.delete(qw);
        if (rebuildCachesActive(projectId)) {
            if (keepIds == null || keepIds.isEmpty()) {
                rebuildNodeByKey.entrySet().removeIf(e -> e.getKey().projectId().equals(projectId));
                rebuildEdgeByKey.entrySet().removeIf(e -> e.getKey().projectId().equals(projectId));
            } else {
                rebuildNodeByKey.entrySet().removeIf(e ->
                        e.getKey().projectId().equals(projectId) && !keepIds.contains(e.getValue().getId()));
            }
        }
        return deleted;
    }

    @Override
    public int deleteAutoEdges(Long projectId, String kind) {
        LambdaQueryWrapper<ApiGraphEdgeEntity> qw = new LambdaQueryWrapper<ApiGraphEdgeEntity>()
                .eq(ApiGraphEdgeEntity::getProjectId, projectId)
                .eq(ApiGraphEdgeEntity::getSource, ApiGraphEdgeKind.SOURCE_AUTO);
        if (kind != null) {
            qw.eq(ApiGraphEdgeEntity::getKind, kind);
        }
        int n = edgeMapper.delete(qw);
        if (rebuildCachesActive(projectId)) {
            rebuildEdgeByKey.entrySet().removeIf(e -> {
                if (!e.getKey().projectId().equals(projectId)) {
                    return false;
                }
                if (!ApiGraphEdgeKind.SOURCE_AUTO.equals(e.getKey().source())) {
                    return false;
                }
                return kind == null || kind.equals(e.getKey().kind());
            });
        }
        return n;
    }

    @Override
    public int deleteEdgesByNodeIds(Collection<Long> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return 0;
        }
        int r = edgeMapper.delete(new LambdaQueryWrapper<ApiGraphEdgeEntity>()
                .in(ApiGraphEdgeEntity::getSourceNodeId, nodeIds)
                .or()
                .in(ApiGraphEdgeEntity::getTargetNodeId, nodeIds));
        if (rebuildEdgeByKey != null && rebuildProjectId != null) {
            rebuildEdgeByKey.entrySet().removeIf(e ->
                    nodeIds.contains(e.getKey().sourceNodeId()) || nodeIds.contains(e.getKey().targetNodeId()));
        }
        return r;
    }

    @Override
    public int deleteByProject(Long projectId) {
        edgeMapper.delete(new LambdaQueryWrapper<ApiGraphEdgeEntity>()
                .eq(ApiGraphEdgeEntity::getProjectId, projectId));
        layoutMapper.delete(new LambdaQueryWrapper<ApiGraphLayoutEntity>()
                .eq(ApiGraphLayoutEntity::getProjectId, projectId));
        int nodes = nodeMapper.delete(new LambdaQueryWrapper<ApiGraphNodeEntity>()
                .eq(ApiGraphNodeEntity::getProjectId, projectId));
        if (rebuildCachesActive(projectId)) {
            rebuildNodeByKey.clear();
            rebuildEdgeByKey.clear();
        }
        return nodes;
    }

    @Override
    public ApiGraphEdgeEntity upsertEdge(ApiGraphEdgeEntity edge) {
        if (rebuildCachesActive(edge.getProjectId())) {
            EdgeKey key = EdgeKey.from(edge);
            ApiGraphEdgeEntity existing = rebuildEdgeByKey.get(key);
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
            rebuildEdgeByKey.put(key, edge);
            return edge;
        }
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

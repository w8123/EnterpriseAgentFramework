package com.enterprise.ai.agent.graph;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 接口图谱数据访问抽象。一期落 MySQL 双表（{@link MysqlApiGraphRepository}）；
 * 二期边规模 / 多跳推理需求出现时可新增图 DB 实现（Nebula / Neo4j）。所有业务调用必须
 * 走本接口而非 Mapper，保持替换面收敛。
 */
public interface ApiGraphRepository {

    // ---------- 节点 ----------

    /** 按 (project_id, kind, ref_id, parent_id, label) 唯一键 upsert，返回最终持久化实体。 */
    ApiGraphNodeEntity upsertNode(ApiGraphNodeEntity node);

    Optional<ApiGraphNodeEntity> findNode(Long projectId, String kind, Long refId, Long parentId, String label);

    List<ApiGraphNodeEntity> listNodesByProject(Long projectId);

    List<ApiGraphNodeEntity> listNodesByProjectAndKind(Long projectId, String kind);

    /** 软删除指定 id 集合外的项目节点（防止重扫导致历史孤儿节点堆积）。 */
    int deleteNodesNotIn(Long projectId, Collection<Long> keepIds);

    /** 仅删除 source=auto 的边（保留运营手动连线）。 */
    int deleteAutoEdges(Long projectId, String kind);

    int deleteEdgesByNodeIds(Collection<Long> nodeIds);

    int deleteByProject(Long projectId);

    // ---------- 边 ----------

    /** upsert 一条边；按 (project_id, kind, source_node_id, target_node_id, source) 幂等。 */
    ApiGraphEdgeEntity upsertEdge(ApiGraphEdgeEntity edge);

    Optional<ApiGraphEdgeEntity> findEdgeById(Long edgeId);

    List<ApiGraphEdgeEntity> listEdgesByProject(Long projectId);

    int deleteEdgeById(Long edgeId);

    // ---------- 布局 ----------

    void upsertLayout(ApiGraphLayoutEntity layout);

    List<ApiGraphLayoutEntity> listLayoutByProject(Long projectId);

    int deleteLayoutByProject(Long projectId);
}

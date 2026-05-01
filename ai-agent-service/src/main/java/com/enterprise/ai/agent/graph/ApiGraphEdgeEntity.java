package com.enterprise.ai.agent.graph;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口图谱边（Phase 4.0）。
 * <p>
 * 边来源 {@link #source} ∈ {@code auto, manual}：{@code manual} 永不被自动重算覆盖；
 * {@code auto} 边在 {@link ApiGraphService#inferModelRefEdges} 等场景下按
 * (project_id, kind, source_node_id, target_node_id, source) 维度幂等 upsert。
 */
@Data
@TableName("api_graph_edge")
public class ApiGraphEdgeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long sourceNodeId;

    private Long targetNodeId;

    /** REQUEST_REF（蓝） / RESPONSE_REF（绿） / MODEL_REF（紫虚线） / BELONGS_TO */
    private String kind;

    /** auto / manual */
    private String source;

    private Double confidence;

    /** CANDIDATE / CONFIRMED / REJECTED；历史数据为空时按 CONFIRMED 处理。 */
    private String status;

    /** schema_match / dto_match / trace_value_match / llm_assisted。 */
    private String inferStrategy;

    private String confirmedBy;

    private LocalDateTime confirmedAt;

    private String rejectReason;

    /** 推断依据 JSON：例如 {"by":"shared_type","type":"UserDTO"}。 */
    private String evidenceJson;

    private String note;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

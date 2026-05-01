package com.enterprise.ai.agent.graph;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口图谱画布布局（Phase 4.0）。
 * 单独表，避免每次写边都去改 {@link ApiGraphNodeEntity}（节点 upsert 频次远高于布局）。
 */
@Data
@TableName("api_graph_layout")
public class ApiGraphLayoutEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long nodeId;

    private Double x;

    private Double y;

    /** 扩展：折叠状态 / 锁定 / 自定义颜色等 JSON。 */
    private String extJson;

    private LocalDateTime updatedAt;
}

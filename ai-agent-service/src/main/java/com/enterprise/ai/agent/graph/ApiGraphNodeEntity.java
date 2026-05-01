package com.enterprise.ai.agent.graph;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口图谱节点（Phase 4.0）。
 * <p>
 * 一期采用「持久化投影」策略：DTO / 字段也建独立行，便于运营在节点上挂备注 / 布局 /
 * 手动边而不在重扫时丢失。{@link #kind} 取值见 {@link ApiGraphNodeKind}。
 */
@Data
@TableName("api_graph_node")
public class ApiGraphNodeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** API / FIELD_IN / FIELD_OUT / DTO / MODULE */
    private String kind;

    /** 业务表外键：API → scan_project_tool.id；MODULE → scan_module.id；FIELD/DTO 为 null。 */
    private Long refId;

    /** 字段树嵌套：指向父字段节点；DTO 内部 children 也建节点。 */
    private Long parentId;

    private String label;

    /** 字段类型 / DTO 全名（含泛型，如 List&lt;RoleDTO&gt;）。 */
    private String typeName;

    /** 附加属性 JSON：required / location / paramPath / httpMethod / endpointPath / aiDescription 等。 */
    private String propsJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

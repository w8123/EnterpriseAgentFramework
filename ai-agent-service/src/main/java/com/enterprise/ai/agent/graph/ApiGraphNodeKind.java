package com.enterprise.ai.agent.graph;

/**
 * 接口图谱节点类型。值与 SQL {@code api_graph_node.kind} 对齐。
 */
public final class ApiGraphNodeKind {

    /** 接口（API），ref_id 关联 scan_project_tool.id。 */
    public static final String API = "API";

    /** 入参字段（含嵌套，parent_id 指向所属 API 或父字段）。 */
    public static final String FIELD_IN = "FIELD_IN";

    /** 出参字段（含嵌套，parent_id 指向所属 API 或父字段）。 */
    public static final String FIELD_OUT = "FIELD_OUT";

    /** 复合数据模型（DTO / VO）；type_name 存原始全名，label 存简名。 */
    public static final String DTO = "DTO";

    /** 模块，ref_id 关联 scan_module.id。 */
    public static final String MODULE = "MODULE";

    private ApiGraphNodeKind() {
    }
}

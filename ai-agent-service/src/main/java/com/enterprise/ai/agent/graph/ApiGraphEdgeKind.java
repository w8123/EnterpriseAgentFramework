package com.enterprise.ai.agent.graph;

/**
 * 接口图谱边类型。值与 SQL {@code api_graph_edge.kind} 对齐。
 * <p>
 * 颜色语义对应前端 G6 渲染：
 * <ul>
 *   <li>{@link #REQUEST_REF}  — 蓝色实线，A 出参字段被 B 入参引用（"取值"语义）</li>
 *   <li>{@link #RESPONSE_REF} — 绿色实线，A 出参字段直接成为 B 出参字段</li>
 *   <li>{@link #MODEL_REF}    — 紫色虚线，两个字段共享同一个 DTO/VO 类型</li>
 *   <li>{@link #BELONGS_TO}   — 内部从属关系（API → MODULE，FIELD → API），仅供布局使用</li>
 * </ul>
 */
public final class ApiGraphEdgeKind {

    public static final String REQUEST_REF = "REQUEST_REF";
    public static final String RESPONSE_REF = "RESPONSE_REF";
    public static final String MODEL_REF = "MODEL_REF";
    public static final String BELONGS_TO = "BELONGS_TO";

    public static final String SOURCE_AUTO = "auto";
    public static final String SOURCE_MANUAL = "manual";

    public static final String STATUS_CANDIDATE = "CANDIDATE";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_REJECTED = "REJECTED";

    public static final String STRATEGY_SCHEMA_MATCH = "schema_match";
    public static final String STRATEGY_DTO_MATCH = "dto_match";

    private ApiGraphEdgeKind() {
    }
}

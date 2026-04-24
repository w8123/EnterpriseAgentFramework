package com.enterprise.ai.agent.agent.persist;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 发布版本快照（不可变）。
 * <p>
 * 每次点击"发布"都会从 {@link AgentDefinitionEntity} 当前态生成一个 snapshotJson，
 * 状态机：DRAFT → ACTIVE → RETIRED；同一 {@code agentId} 下可以有多个 ACTIVE
 * 版本（灰度场景），由 {@code rolloutPercent} 决定分流比例；发布新版本时，
 * {@code AgentVersionService} 会把历史 ACTIVE 版本置为 RETIRED。
 */
@Data
@TableName("agent_version")
public class AgentVersionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String agentId;

    private String version;

    /** Agent 定义冻结快照（完整 JSON）。 */
    private String snapshotJson;

    /** 灰度百分比 0-100，默认 100（全量）。 */
    private Integer rolloutPercent;

    /** DRAFT / ACTIVE / RETIRED。 */
    private String status;

    private String publishedBy;

    private LocalDateTime publishedAt;

    private String note;

    private LocalDateTime createTime;
}

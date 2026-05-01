package com.enterprise.ai.agent.governance;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生产护栏决策日志。
 * <p>
 * 限流、熔断、ACL、sideEffect、发布前检查都写入同一张表，便于 Trace Center 解释
 * “为什么某次调用被拦截 / 为什么某个 Tool 对 Agent 不可见”。
 */
@Data
@TableName("guard_decision_log")
public class GuardDecisionLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;

    private String decisionType;

    private String targetKind;

    private String targetName;

    private String decision;

    private String reason;

    private String metadataJson;

    private LocalDateTime createdAt;
}

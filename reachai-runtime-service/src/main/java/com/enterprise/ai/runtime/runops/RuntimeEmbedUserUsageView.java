package com.enterprise.ai.runtime.runops;

import lombok.Data;

import java.time.LocalDateTime;

/** 一个业务用户在嵌入式对话里的使用汇总；供管理端「AI 使用情况」按人统计。 */
@Data
public class RuntimeEmbedUserUsageView {
    private String appId;
    private String projectCode;
    private String externalUserId;
    private String globalUserId;
    private Integer runCount;
    private Integer sessionCount;
    private Integer failedCount;
    private Long tokenCost;
    private LocalDateTime lastStartedAt;
}

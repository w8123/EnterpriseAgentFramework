package com.enterprise.ai.runtime.runops;

import lombok.Data;

import java.time.LocalDateTime;

/** 一个对话会话内 Agent 根运行的汇总；只含计数和状态，不含对话正文。 */
@Data
public class RuntimeSessionRunStatsView {
    private String sessionId;
    private Integer runCount;
    private Integer failedCount;
    private Integer openCount;
    private LocalDateTime lastStartedAt;
}

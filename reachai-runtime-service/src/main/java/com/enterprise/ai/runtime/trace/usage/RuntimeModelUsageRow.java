package com.enterprise.ai.runtime.trace.usage;

import lombok.Data;

import java.time.LocalDate;

/** Grouped model token usage for 「AI 使用情况 · 费用」; no content, only counts. */
@Data
public class RuntimeModelUsageRow {
    private LocalDate day;
    private String appId;
    private String externalUserId;
    private String userId;
    private String projectCode;
    private String entryType;
    private String modelInstanceId;
    private Long calls;
    private Long inputTokens;
    private Long outputTokens;
    private Long runs;
}

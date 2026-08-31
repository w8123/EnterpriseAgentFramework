package com.enterprise.ai.control.a2a.infrastructure.persistence;

import lombok.Data;

@Data
public class A2aTransportMetricsRow {
    private Long totalCount;
    private Long successCount;
    private Long averageLatencyMs;
}

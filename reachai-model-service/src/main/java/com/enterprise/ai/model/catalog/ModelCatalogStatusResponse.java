package com.enterprise.ai.model.catalog;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ModelCatalogStatusResponse {
    private boolean enabled;
    private boolean autoSyncEnabled;
    private String zoneId;
    private LocalDate businessDate;
    private boolean analyzerConfigured;
    private boolean stale;
    private LocalDateTime catalogVerifiedAt;
    private LocalDateTime lastAnySuccessfulAt;
    private int sourceCount;
    private int completedToday;
    private String message;
    private List<SourceStatus> sources;

    @Data
    @Builder
    public static class SourceStatus {
        private String sourceKey;
        private String provider;
        private String name;
        private String sourceKind;
        private String sourceUrl;
        private LocalDateTime lastCheckedAt;
        private LocalDateTime lastSuccessAt;
        private String lastRunStatus;
        private Integer attemptCount;
        private Integer candidateCount;
        private Integer publishedCount;
        private Integer reviewCount;
        private String errorCode;
        private String errorMessage;
    }
}

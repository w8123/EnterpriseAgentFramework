package com.enterprise.ai.model.catalog;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class ModelCatalogManualSyncResponse {
    private LocalDate businessDate;
    private boolean accepted;
    private int sourceCount;
    private int completedToday;
    private int queuedCount;
    private String message;
}

package com.enterprise.ai.model.catalog;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

@Data
@Component
@ConfigurationProperties(prefix = "model.catalog-sync")
public class ModelCatalogSyncProperties {

    private boolean enabled = false;

    private String zoneId = "Asia/Shanghai";

    private int batchSize = 8;

    private int maxAttempts = 3;

    private int leaseSeconds = 1_800;

    private int retryDelayMinutes = 30;

    private int requestTimeoutSeconds = 45;

    private int maxContentBytes = 4 * 1024 * 1024;

    private int maxAnalyzerChars = 120_000;

    private String analyzerModelInstanceId;

    private boolean autoPublishSafeFacts = true;

    private double autoPublishMinConfidence = 0.92d;

    private int staleAfterHours = 36;

    public ZoneId resolvedZoneId() {
        return ZoneId.of(zoneId);
    }

    public boolean analyzerConfigured() {
        return analyzerModelInstanceId != null && !analyzerModelInstanceId.isBlank();
    }
}

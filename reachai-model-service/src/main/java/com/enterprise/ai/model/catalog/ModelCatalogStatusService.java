package com.enterprise.ai.model.catalog;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ModelCatalogStatusService {

    private final ModelCatalogSourceMapper sourceMapper;
    private final ModelCatalogSyncRunMapper runMapper;
    private final ModelCatalogSyncProperties properties;
    private final ModelCatalogSettingsService settingsService;

    public ModelCatalogStatusResponse status() {
        boolean autoSyncEnabled = settingsService.autoSyncEnabled();
        LocalDate businessDate = LocalDate.now(properties.resolvedZoneId());
        List<ModelCatalogSourceEntity> sources = sourceMapper.selectEnabled();
        List<ModelCatalogStatusResponse.SourceStatus> sourceStatuses = new ArrayList<>();
        int completedToday = 0;
        LocalDateTime latestSuccess = null;
        LocalDateTime catalogVerifiedAt = null;
        boolean allHaveSuccess = !sources.isEmpty();
        for (ModelCatalogSourceEntity source : sources) {
            ModelCatalogSyncRunEntity run = runMapper.findLatestForSource(source.getId());
            boolean completed = run != null
                    && businessDate.equals(run.getBusinessDate())
                    && ("SUCCESS".equals(run.getStatus()) || "NO_CHANGE".equals(run.getStatus()));
            if (completed) completedToday++;
            if (source.getLastSuccessAt() == null) {
                allHaveSuccess = false;
            } else {
                latestSuccess = max(latestSuccess, source.getLastSuccessAt());
                catalogVerifiedAt = min(catalogVerifiedAt, source.getLastSuccessAt());
            }
            sourceStatuses.add(ModelCatalogStatusResponse.SourceStatus.builder()
                    .sourceKey(source.getSourceKey())
                    .provider(source.getProvider())
                    .name(source.getName())
                    .sourceKind(source.getSourceKind())
                    .sourceUrl(source.getSourceUrl())
                    .lastCheckedAt(source.getLastCheckedAt())
                    .lastSuccessAt(source.getLastSuccessAt())
                    .lastRunStatus(run == null ? null : run.getStatus())
                    .attemptCount(run == null ? null : run.getAttemptCount())
                    .candidateCount(run == null ? null : run.getCandidateCount())
                    .publishedCount(run == null ? null : run.getPublishedCount())
                    .reviewCount(run == null ? null : run.getReviewCount())
                    .errorCode(run == null ? source.getLastErrorCode() : run.getLastErrorCode())
                    .errorMessage(run == null ? source.getLastErrorMessage() : run.getLastErrorMessage())
                    .build());
        }
        if (!allHaveSuccess) catalogVerifiedAt = null;
        boolean stale = catalogVerifiedAt == null
                || catalogVerifiedAt.isBefore(LocalDateTime.now().minusHours(Math.max(1, properties.getStaleAfterHours())));
        return ModelCatalogStatusResponse.builder()
                .enabled(autoSyncEnabled)
                .autoSyncEnabled(autoSyncEnabled)
                .zoneId(properties.getZoneId())
                .businessDate(businessDate)
                .analyzerConfigured(properties.analyzerConfigured())
                .stale(stale)
                .catalogVerifiedAt(catalogVerifiedAt)
                .lastAnySuccessfulAt(latestSuccess)
                .sourceCount(sources.size())
                .completedToday(completedToday)
                .message(message(autoSyncEnabled, sources.size(), completedToday))
                .sources(List.copyOf(sourceStatuses))
                .build();
    }

    private String message(boolean autoSyncEnabled, int sourceCount, int completedToday) {
        if (sourceCount == 0) return "尚未配置启用的官方模型来源";
        if (!properties.analyzerConfigured()) return "未配置目录分析模型，官方快照不会直接发布为模型模板";
        if (completedToday == sourceCount) return "今日官方模型目录已同步";
        if (!autoSyncEnabled) return "自动同步已关闭，可在目录设置中手动同步";
        if (completedToday == 0) return "等待今日首次同步，服务启动 10 分钟后会自动执行";
        return "今日已完成 " + completedToday + "/" + sourceCount + " 个官方来源，后台将继续处理";
    }

    private LocalDateTime min(LocalDateTime first, LocalDateTime second) {
        if (first == null) return second;
        return Comparator.<LocalDateTime>naturalOrder().compare(first, second) <= 0 ? first : second;
    }

    private LocalDateTime max(LocalDateTime first, LocalDateTime second) {
        if (first == null) return second;
        return Comparator.<LocalDateTime>naturalOrder().compare(first, second) >= 0 ? first : second;
    }
}

package com.enterprise.ai.model.catalog;

import com.enterprise.ai.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Slf4j
@Service
public class ModelCatalogManualSyncService {

    private final ModelCatalogSourceMapper sourceMapper;
    private final ModelCatalogSyncRunMapper runMapper;
    private final ModelCatalogSyncWorker worker;
    private final ModelCatalogSyncProperties properties;
    private final TaskExecutor taskExecutor;

    public ModelCatalogManualSyncService(
            ModelCatalogSourceMapper sourceMapper,
            ModelCatalogSyncRunMapper runMapper,
            ModelCatalogSyncWorker worker,
            ModelCatalogSyncProperties properties,
            @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.sourceMapper = sourceMapper;
        this.runMapper = runMapper;
        this.worker = worker;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
    }

    public ModelCatalogManualSyncResponse trigger() {
        if (!properties.analyzerConfigured()) {
            throw new BizException(409, "请先配置目录分析模型，再执行手动同步");
        }
        int sourceCount = sourceMapper.selectEnabled().size();
        if (sourceCount == 0) {
            throw new BizException(409, "尚未配置启用的官方模型来源");
        }

        LocalDate businessDate = LocalDate.now(properties.resolvedZoneId());
        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        runMapper.createDailySlots(businessDate, "MANUAL", maxAttempts);
        runMapper.prepareManualDailySlots(businessDate, maxAttempts);
        int completedToday = runMapper.countCompletedForDate(businessDate);
        int queuedCount = runMapper.countIncompleteForDate(businessDate);
        boolean accepted = queuedCount > 0;
        if (accepted) {
            try {
                taskExecutor.execute(worker::processQueuedRunsNow);
            } catch (RuntimeException ex) {
                log.warn("Manual model catalog sync was persisted but immediate dispatch failed; scheduled polling will retry: {}",
                        ex.getClass().getSimpleName());
            }
        }

        return ModelCatalogManualSyncResponse.builder()
                .businessDate(businessDate)
                .accepted(accepted)
                .sourceCount(sourceCount)
                .completedToday(completedToday)
                .queuedCount(queuedCount)
                .message(accepted
                        ? "手动同步已提交，后台将处理今日尚未完成的官方来源"
                        : "今日所有官方来源已经同步，无需重复执行")
                .build();
    }
}

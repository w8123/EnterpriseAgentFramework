package com.enterprise.ai.model.catalog;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelCatalogSyncWorkerTest {

    @Test
    void startupPollRequeuesDeadTodayOnceButNeverReopensSuccessRowsInCode() {
        ModelCatalogSyncRunMapper runMapper = mock(ModelCatalogSyncRunMapper.class);
        when(runMapper.findLeaseCandidateId()).thenReturn(null);
        ModelCatalogSyncProperties properties = new ModelCatalogSyncProperties();
        properties.setBatchSize(8);
        properties.setMaxAttempts(3);
        ModelCatalogSettingsService settingsService = mock(ModelCatalogSettingsService.class);
        when(settingsService.autoSyncEnabled()).thenReturn(true);
        ModelCatalogSyncWorker worker = new ModelCatalogSyncWorker(
                runMapper,
                mock(ModelCatalogSourceMapper.class),
                mock(ModelCatalogSyncService.class),
                properties,
                settingsService);

        worker.synchronizeDueSources();
        worker.synchronizeDueSources();

        LocalDate today = LocalDate.now(properties.resolvedZoneId());
        verify(runMapper, times(2)).createDailySlots(eq(today), any(), eq(3));
        verify(runMapper, times(1)).requeueDeadSlotsOnStartup(today, 3);
    }

    @Test
    void disabledAutomaticSyncDoesNotCreateDailySlotsButStillDrainsQueuedManualWork() {
        ModelCatalogSyncRunMapper runMapper = mock(ModelCatalogSyncRunMapper.class);
        when(runMapper.findLeaseCandidateId()).thenReturn(null);
        ModelCatalogSettingsService settingsService = mock(ModelCatalogSettingsService.class);
        when(settingsService.autoSyncEnabled()).thenReturn(false);
        ModelCatalogSyncWorker worker = new ModelCatalogSyncWorker(
                runMapper,
                mock(ModelCatalogSourceMapper.class),
                mock(ModelCatalogSyncService.class),
                new ModelCatalogSyncProperties(),
                settingsService);

        worker.synchronizeDueSources();

        verify(runMapper, never()).createDailySlots(any(), any(), anyInt());
        verify(runMapper).markExpiredExhausted();
        verify(runMapper).findLeaseCandidateId();
    }
}

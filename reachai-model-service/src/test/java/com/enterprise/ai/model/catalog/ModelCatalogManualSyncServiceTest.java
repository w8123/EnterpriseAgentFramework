package com.enterprise.ai.model.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelCatalogManualSyncServiceTest {

    private ModelCatalogSourceMapper sourceMapper;
    private ModelCatalogSyncRunMapper runMapper;
    private ModelCatalogSyncWorker worker;
    private ModelCatalogSyncProperties properties;
    private TaskExecutor taskExecutor;
    private ModelCatalogManualSyncService service;

    @BeforeEach
    void setUp() {
        sourceMapper = mock(ModelCatalogSourceMapper.class);
        runMapper = mock(ModelCatalogSyncRunMapper.class);
        worker = mock(ModelCatalogSyncWorker.class);
        properties = new ModelCatalogSyncProperties();
        properties.setAnalyzerModelInstanceId("catalog-analyzer");
        properties.setMaxAttempts(3);
        taskExecutor = mock(TaskExecutor.class);
        service = new ModelCatalogManualSyncService(
                sourceMapper, runMapper, worker, properties, taskExecutor);
    }

    @Test
    void queuesOnlyTodaysIncompleteSourcesAndDispatchesImmediately() {
        when(sourceMapper.selectEnabled()).thenReturn(List.of(source(1L), source(2L)));
        when(runMapper.countCompletedForDate(any(LocalDate.class))).thenReturn(1);
        when(runMapper.countIncompleteForDate(any(LocalDate.class))).thenReturn(1);

        ModelCatalogManualSyncResponse response = service.trigger();

        assertTrue(response.isAccepted());
        verify(runMapper).createDailySlots(eq(response.getBusinessDate()), eq("MANUAL"), eq(3));
        verify(runMapper).prepareManualDailySlots(response.getBusinessDate(), 3);
        verify(taskExecutor).execute(any(Runnable.class));
    }

    @Test
    void doesNotRepeatWhenAllOfficialSourcesAlreadyCompletedToday() {
        when(sourceMapper.selectEnabled()).thenReturn(List.of(source(1L), source(2L)));
        when(runMapper.countCompletedForDate(any(LocalDate.class))).thenReturn(2);
        when(runMapper.countIncompleteForDate(any(LocalDate.class))).thenReturn(0);

        ModelCatalogManualSyncResponse response = service.trigger();

        assertFalse(response.isAccepted());
        verify(taskExecutor, never()).execute(any(Runnable.class));
    }

    private ModelCatalogSourceEntity source(Long id) {
        ModelCatalogSourceEntity source = new ModelCatalogSourceEntity();
        source.setId(id);
        source.setEnabled(true);
        return source;
    }
}

package com.enterprise.ai.agent.platform.control.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContextLifecycleSchedulerTest {

    private ContextLifecycleService lifecycleService;

    @BeforeEach
    void setUp() {
        lifecycleService = mock(ContextLifecycleService.class);
    }

    @Test
    void scheduledLifecycleIsDisabledByDefault() {
        ContextLifecycleScheduler scheduler = new ContextLifecycleScheduler(lifecycleService);

        scheduler.runScheduledLifecycle();

        verifyNoInteractions(lifecycleService);
    }

    @Test
    void enabledScheduledLifecycleBuildsServiceRequest() {
        ContextLifecycleScheduler scheduler = new ContextLifecycleScheduler(lifecycleService);
        scheduler.enabled = true;
        scheduler.tenantId = " default ";
        scheduler.projectCode = "demo-project";
        scheduler.projectId = 42L;
        scheduler.dryRun = false;
        scheduler.includeRuntimeUserItems = false;
        scheduler.candidateExpireLimit = 100;
        scheduler.itemStaleLimit = 200;
        when(lifecycleService.run(any())).thenReturn(ContextLifecycleRunResponse.builder()
                .tenantId("default")
                .expiredCandidateCount(2)
                .staleItemCount(3)
                .build());

        scheduler.runScheduledLifecycle();

        ArgumentCaptor<ContextLifecycleRunRequest> captor =
                ArgumentCaptor.forClass(ContextLifecycleRunRequest.class);
        verify(lifecycleService).run(captor.capture());
        ContextLifecycleRunRequest request = captor.getValue();
        assertEquals("default", request.getTenantId());
        assertEquals("demo-project", request.getProjectCode());
        assertEquals(42L, request.getProjectId());
        assertFalse(request.getDryRun());
        assertFalse(request.getIncludeRuntimeUserItems());
        assertEquals(100, request.getCandidateExpireLimit());
        assertEquals(200, request.getItemStaleLimit());
    }

    @Test
    void enabledScheduledLifecycleSkipsBlankTenant() {
        ContextLifecycleScheduler scheduler = new ContextLifecycleScheduler(lifecycleService);
        scheduler.enabled = true;
        scheduler.tenantId = " ";

        scheduler.runScheduledLifecycle();

        verifyNoInteractions(lifecycleService);
    }

    @Test
    void scheduledLifecycleDoesNotThrowWhenServiceFails() {
        ContextLifecycleScheduler scheduler = new ContextLifecycleScheduler(lifecycleService);
        scheduler.enabled = true;
        scheduler.tenantId = "default";
        when(lifecycleService.run(any())).thenThrow(new IllegalStateException("db unavailable"));

        assertDoesNotThrow(scheduler::runScheduledLifecycle);
        verify(lifecycleService).run(any());
    }
}

package com.enterprise.ai.agent.platform.control.controller;

import com.enterprise.ai.agent.platform.control.controller.ContextController;
import com.enterprise.ai.agent.platform.control.context.*;
import com.enterprise.ai.common.dto.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContextControllerLifecycleTest {

    private ContextLifecycleService lifecycleService;
    private ContextAuditService auditService;
    private ContextController controller;

    @BeforeEach
    void setUp() {
        lifecycleService = mock(ContextLifecycleService.class);
        auditService = mock(ContextAuditService.class);
        controller = new ContextController(
                mock(ContextNamespaceService.class),
                mock(ContextItemService.class),
                mock(ContextBindingService.class),
                mock(ContextEvidenceService.class),
                mock(ContextRetrievalService.class),
                mock(ContextComposerService.class),
                auditService,
                lifecycleService,
                mock(ContextOpsSummaryService.class));
    }

    @Test
    void lifecycleDryRunEndpointReturnsSummary() {
        ContextLifecycleRunResponse response = ContextLifecycleRunResponse.builder()
                .tenantId("tenant-a")
                .dryRun(true)
                .expiredCandidateCount(2)
                .staleItemCount(3)
                .skippedRuntimeUserItemCount(1)
                .scannedItemCount(4)
                .warnings(List.of("dry run only"))
                .build();
        when(lifecycleService.run(any())).thenReturn(response);

        ContextLifecycleRunRequest request = new ContextLifecycleRunRequest();
        request.setTenantId("tenant-a");
        request.setDryRun(true);

        ApiResult<ContextLifecycleRunResponse> result = controller.runLifecycle(request);

        assertEquals(200, result.getCode());
        assertTrue(result.getData().isDryRun());
        assertEquals(2, result.getData().getExpiredCandidateCount());
        assertEquals(3, result.getData().getStaleItemCount());
    }

    @Test
    void lifecycleEndpointRejectsMissingTenantId() {
        when(lifecycleService.run(any())).thenThrow(new IllegalArgumentException("tenantId is required"));

        ApiResult<ContextLifecycleRunResponse> result = controller.runLifecycle(new ContextLifecycleRunRequest());

        assertNotEquals(200, result.getCode());
        assertTrue(result.getMessage().contains("tenantId is required"));
    }

    @Test
    void listAuditAcceptsProjectIdParam() {
        when(auditService.listAuditEvents(any(ContextAuditListRequest.class))).thenReturn(List.of());

        ApiResult<List<ContextAuditEventResponse>> result = controller.listAudit(
                "tenant-a", "demo-project", 42L, null, null,
                null, null, null, null, null, null, null, 50);

        assertEquals(200, result.getCode());
        ArgumentCaptor<ContextAuditListRequest> captor = ArgumentCaptor.forClass(ContextAuditListRequest.class);
        verify(auditService).listAuditEvents(captor.capture());
        assertEquals(42L, captor.getValue().getProjectId());
        assertEquals("demo-project", captor.getValue().getProjectCode());
    }

    @Test
    void listAuditAcceptsCreatedAtRangeParams() throws Exception {
        when(auditService.listAuditEvents(any(ContextAuditListRequest.class))).thenReturn(List.of());
        LocalDateTime dateFrom = LocalDateTime.of(2026, 6, 24, 9, 30);
        LocalDateTime dateTo = LocalDateTime.of(2026, 6, 24, 18, 45);

        ApiResult<List<ContextAuditEventResponse>> result = invokeAuditListWithDates(dateFrom, dateTo);

        assertEquals(200, result.getCode());
        ArgumentCaptor<ContextAuditListRequest> captor = ArgumentCaptor.forClass(ContextAuditListRequest.class);
        verify(auditService).listAuditEvents(captor.capture());
        assertEquals(dateFrom, getAuditDateValue(captor.getValue(), "getDateFrom"));
        assertEquals(dateTo, getAuditDateValue(captor.getValue(), "getDateTo"));
    }

    @SuppressWarnings("unchecked")
    private ApiResult<List<ContextAuditEventResponse>> invokeAuditListWithDates(LocalDateTime dateFrom,
                                                                                 LocalDateTime dateTo) throws Exception {
        try {
            Method method = ContextController.class.getMethod("listAudit",
                    String.class, String.class, Long.class, Long.class, Long.class,
                    String.class, String.class, String.class, String.class, String.class,
                    LocalDateTime.class, LocalDateTime.class, int.class);
            return (ApiResult<List<ContextAuditEventResponse>>) method.invoke(controller,
                    "tenant-a", "demo-project", 42L, null, null,
                    null, null, null, null, "trace-1",
                    dateFrom, dateTo, 50);
        } catch (NoSuchMethodException ex) {
            fail("ContextController.listAudit should accept dateFrom/dateTo request params", ex);
            throw ex;
        }
    }

    private LocalDateTime getAuditDateValue(ContextAuditListRequest request, String getterName) throws Exception {
        try {
            Method getter = ContextAuditListRequest.class.getMethod(getterName);
            return (LocalDateTime) getter.invoke(request);
        } catch (ReflectiveOperationException ex) {
            fail("ContextAuditListRequest should expose " + getterName, ex);
            throw ex;
        }
    }
}

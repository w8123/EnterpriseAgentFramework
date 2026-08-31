package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PublishedWorkflowView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowExecutionReadinessView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchWorkflowTraceReadinessApplicationService.WorkflowAcceptanceTarget;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchObservationPort.TraceCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PageWorkbenchWorkflowTraceReadinessApplicationServiceTest {

    private PageWorkbenchObservationPort embedEvidence;
    private PageWorkbenchRuntimePort runtimeClient;
    private PageWorkbenchWorkflowTraceReadinessApplicationService service;
    private LocalDateTime observedAfter;

    @BeforeEach
    void setUp() {
        embedEvidence = mock(PageWorkbenchObservationPort.class);
        runtimeClient = mock(PageWorkbenchRuntimePort.class);
        service = new PageWorkbenchWorkflowTraceReadinessApplicationService(
                embedEvidence,
                runtimeClient,
                new ObjectMapper());
        observedAfter = LocalDateTime.of(2026, 7, 27, 10, 0);
    }

    @Test
    void pageOnlyAcceptanceDoesNotInventAWorkflowRequirement() {
        ReadinessItem result = service.evaluate(
                "orders",
                "orders.detail",
                observedAfter,
                null,
                null);

        assertEquals("PASS", result.status());
        assertEquals("PAGE_ONLY", result.evidence().path("mode").asText());
        verify(embedEvidence, never()).successfulTraceCandidates(
                "orders",
                "orders.detail",
                observedAfter);
    }

    @Test
    void passesOnlyTheReportedTraceFromTheExactPageSession() {
        WorkflowAcceptanceTarget target = target();
        TraceCandidate candidate = candidate("trace-orders");
        when(embedEvidence.successfulTraceCandidates(
                "orders",
                "orders.detail",
                observedAfter)).thenReturn(List.of(
                        candidate("trace-other"),
                        candidate));
        when(runtimeClient.pageWorkbenchExecutionReadiness(
                "orders",
                "orders.detail",
                "embed-session",
                "page-instance",
                "trace-orders",
                "wf-orders",
                21L,
                "v1.0.0")).thenReturn(ResponseEntity.ok(
                        observed("PASS", "PAGE_WORKFLOW_TRACE_READY")));

        ReadinessItem result = service.evaluate(
                "orders",
                "orders.detail",
                observedAfter,
                target,
                "trace-orders");

        assertEquals("PASS", result.status());
        assertEquals("trace-orders",
                result.evidence().path("traceId").asText());
        verify(runtimeClient).pageWorkbenchExecutionReadiness(
                "orders",
                "orders.detail",
                "embed-session",
                "page-instance",
                "trace-orders",
                "wf-orders",
                21L,
                "v1.0.0");
    }

    @Test
    void reportedTraceCannotBeSatisfiedByAnotherObservedTrace() {
        when(embedEvidence.successfulTraceCandidates(
                "orders",
                "orders.detail",
                observedAfter)).thenReturn(List.of(
                        candidate("trace-other")));

        ReadinessItem result = service.evaluate(
                "orders",
                "orders.detail",
                observedAfter,
                target(),
                "trace-orders");

        assertEquals("PENDING", result.status());
        verify(runtimeClient, never()).pageWorkbenchExecutionReadiness(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void keepsTaskDetailReadableWhenTraceEvidenceDatabaseIsUnavailable() {
        when(embedEvidence.successfulTraceCandidates(
                "orders",
                "orders.detail",
                observedAfter)).thenThrow(
                        new RecoverableDataAccessException(
                                "connection closed"));

        ReadinessItem result = service.evaluate(
                "orders",
                "orders.detail",
                observedAfter,
                target(),
                null);

        assertEquals("PENDING", result.status());
        assertEquals(
                "DATA_ACCESS_UNAVAILABLE",
                result.evidence().path("reason").asText());
        assertEquals(
                "reachai-control-database",
                result.evidence().path("dependency").asText());
        assertTrue(result.evidence().path("connectionError")
                .isMissingNode());
        verify(runtimeClient, never()).pageWorkbenchExecutionReadiness(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void multipleEligibleTracesRequireAnExplicitSelection() {
        when(embedEvidence.successfulTraceCandidates(
                "orders",
                "orders.detail",
                observedAfter)).thenReturn(List.of(
                        candidate("trace-orders"),
                        candidate("trace-orders-2")));
        when(runtimeClient.pageWorkbenchExecutionReadiness(
                "orders",
                "orders.detail",
                "embed-session",
                "page-instance",
                "trace-orders",
                "wf-orders",
                21L,
                "v1.0.0")).thenReturn(ResponseEntity.ok(
                        observed(
                                "trace-orders",
                                "PASS",
                                "PAGE_WORKFLOW_TRACE_READY")));
        when(runtimeClient.pageWorkbenchExecutionReadiness(
                "orders",
                "orders.detail",
                "embed-session",
                "page-instance",
                "trace-orders-2",
                "wf-orders",
                21L,
                "v1.0.0")).thenReturn(ResponseEntity.ok(
                        observed(
                                "trace-orders-2",
                                "PASS",
                                "PAGE_WORKFLOW_TRACE_READY")));

        ReadinessItem result = service.evaluate(
                "orders",
                "orders.detail",
                observedAfter,
                target(),
                null);

        assertEquals("PENDING", result.status());
        assertEquals(2,
                result.evidence().path("eligibleTraceCount").asInt());
        assertEquals(2,
                result.evidence().path("eligibleTraceIds").size());
    }

    @Test
    void oneEligibleTraceCanBeResolvedWithoutAReportedTraceId() {
        when(embedEvidence.successfulTraceCandidates(
                "orders",
                "orders.detail",
                observedAfter)).thenReturn(List.of(
                        candidate("trace-orders"),
                        candidate("trace-other")));
        when(runtimeClient.pageWorkbenchExecutionReadiness(
                "orders",
                "orders.detail",
                "embed-session",
                "page-instance",
                "trace-orders",
                "wf-orders",
                21L,
                "v1.0.0")).thenReturn(ResponseEntity.ok(
                        observed(
                                "trace-orders",
                                "PASS",
                                "PAGE_WORKFLOW_TRACE_READY")));
        when(runtimeClient.pageWorkbenchExecutionReadiness(
                "orders",
                "orders.detail",
                "embed-session",
                "page-instance",
                "trace-other",
                "wf-orders",
                21L,
                "v1.0.0")).thenReturn(ResponseEntity.ok(
                        observed(
                                "trace-other",
                                "FAIL",
                                "WORKFLOW_EXECUTION_NOT_OBSERVED")));

        ReadinessItem result = service.evaluate(
                "orders",
                "orders.detail",
                observedAfter,
                target(),
                null);

        assertEquals("PASS", result.status());
        assertEquals("trace-orders",
                result.evidence().path("traceId").asText());
    }

    @Test
    void structuredPresentationRequiresEmbedUiRequestEvidence() {
        when(embedEvidence.successfulTraceCandidates(
                "orders",
                "orders.detail",
                observedAfter)).thenReturn(List.of(
                        candidate("trace-orders")));
        when(runtimeClient.pageWorkbenchExecutionReadiness(
                "orders",
                "orders.detail",
                "embed-session",
                "page-instance",
                "trace-orders",
                "wf-orders",
                21L,
                "v1.0.0")).thenReturn(ResponseEntity.ok(
                        observed(
                                "trace-orders",
                                "PASS",
                                "PAGE_WORKFLOW_TRACE_READY",
                                true,
                                true)));

        ReadinessItem result = service.evaluate(
                "orders",
                "orders.detail",
                observedAfter,
                target(),
                "trace-orders");

        assertEquals("PENDING", result.status());
        assertTrue(result.message().contains("uiRequest"));
        assertTrue(result.evidence().path(
                "structuredPresentationObserved").asBoolean());
        assertTrue(!result.evidence().path(
                "uiRequestObserved").asBoolean());
    }

    @Test
    void structuredPresentationPassesWithSupportedEmbedCard() {
        TraceCandidate candidate = new TraceCandidate(
                "orders",
                "orders.detail",
                "embed-session",
                "page-instance",
                "trace-orders",
                LocalDateTime.of(2026, 7, 27, 10, 5),
                true,
                "list_card");
        when(embedEvidence.successfulTraceCandidates(
                "orders",
                "orders.detail",
                observedAfter)).thenReturn(List.of(candidate));
        when(runtimeClient.pageWorkbenchExecutionReadiness(
                "orders",
                "orders.detail",
                "embed-session",
                "page-instance",
                "trace-orders",
                "wf-orders",
                21L,
                "v1.0.0")).thenReturn(ResponseEntity.ok(
                        observed(
                                "trace-orders",
                                "PASS",
                                "PAGE_WORKFLOW_TRACE_READY",
                                true,
                                true)));

        ReadinessItem result = service.evaluate(
                "orders",
                "orders.detail",
                observedAfter,
                target(),
                "trace-orders");

        assertEquals("PASS", result.status());
        assertTrue(result.evidence().path(
                "uiRequestObserved").asBoolean());
        assertEquals("list_card",
                result.evidence().path("uiRequestComponent").asText());
    }

    @Test
    void creationValidationResolvesTheExactPublishedTarget() {
        when(runtimeClient.pageWorkbenchPublished(
                "orders",
                "orders.detail")).thenReturn(ResponseEntity.ok(List.of(
                        published())));

        WorkflowAcceptanceTarget resolved = service.requirePublishedTarget(
                "orders",
                "orders.detail",
                target());

        assertEquals("wf-orders", resolved.workflowId());
        assertEquals(21L, resolved.workflowVersionId());
        assertEquals("订单页面助手", resolved.workflowName());
    }

    @Test
    void creationValidationRejectsAFrontendVersionMismatch() {
        when(runtimeClient.pageWorkbenchPublished(
                "orders",
                "orders.detail")).thenReturn(ResponseEntity.ok(List.of(
                        published())));
        WorkflowAcceptanceTarget mismatched = new WorkflowAcceptanceTarget(
                "wf-orders",
                22L,
                "v2.0.0",
                "前端快照",
                "model-orders");

        assertThrows(
                IllegalArgumentException.class,
                () -> service.requirePublishedTarget(
                        "orders",
                        "orders.detail",
                        mismatched));
    }

    private WorkflowAcceptanceTarget target() {
        return new WorkflowAcceptanceTarget(
                "wf-orders",
                21L,
                "v1.0.0",
                "订单页面助手",
                "model-orders");
    }

    private TraceCandidate candidate(String traceId) {
        return new TraceCandidate(
                "orders",
                "orders.detail",
                "embed-session",
                "page-instance",
                traceId,
                LocalDateTime.of(2026, 7, 27, 10, 5));
    }

    private WorkflowExecutionReadinessView observed(
            String status,
            String code) {
        return observed("trace-orders", status, code);
    }

    private WorkflowExecutionReadinessView observed(
            String traceId,
            String status,
            String code) {
        return observed(traceId, status, code, false, false);
    }

    private WorkflowExecutionReadinessView observed(
            String traceId,
            String status,
            String code,
            boolean structuredPresentationRequired,
            boolean structuredPresentationObserved) {
        return new WorkflowExecutionReadinessView(
                status,
                code,
                "test",
                "orders",
                "orders.detail",
                "embed-session",
                "page-instance",
                traceId,
                "wf-orders",
                21L,
                "v1.0.0",
                "COMPLETED",
                "EMBED",
                true,
                false,
                false,
                false,
                false,
                false,
                structuredPresentationRequired,
                structuredPresentationObserved,
                LocalDateTime.of(2026, 7, 27, 10, 6));
    }

    private PublishedWorkflowView published() {
        return new PublishedWorkflowView(
                "orders.detail",
                "wf-orders",
                "orders-page-assistant",
                "订单页面助手",
                null,
                "ACTIVE",
                21L,
                "v1.0.0",
                "tester",
                LocalDateTime.of(2026, 7, 27, 9, 0),
                "agent-orders",
                "orders-copilot",
                "订单助手",
                31L,
                3,
                "model-orders",
                "query_orders",
                "READ",
                null,
                0,
                null,
                null,
                null,
                null);
    }
}

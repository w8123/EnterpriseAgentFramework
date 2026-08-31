package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchObservationPort.ConversationEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.RecoverableDataAccessException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PageWorkbenchBrowserReadinessApplicationServiceTest {

    @Test
    void mapsExactPageConversationObservationToPass() {
        PageWorkbenchObservationPort evidenceService =
                mock(PageWorkbenchObservationPort.class);
        LocalDateTime observedAfter =
                LocalDateTime.of(2026, 7, 26, 10, 0);
        when(evidenceService.latestSuccessfulConversation(
                "orders",
                "orders.detail",
                observedAfter))
                .thenReturn(ConversationEvidence.passed(
                        "Observed exact page conversation.",
                        "sessionId=session-1; pageKey=orders.detail"));
        PageWorkbenchBrowserReadinessApplicationService service =
                new PageWorkbenchBrowserReadinessApplicationService(
                        evidenceService,
                        new ObjectMapper());

        ReadinessItem readiness = service.evaluate(
                "orders",
                "orders.detail",
                observedAfter);

        assertEquals(
                PageWorkbenchBrowserReadinessApplicationService.KEY,
                readiness.key());
        assertEquals("PASS", readiness.status());
        assertTrue(readiness.evidence().path("summary").asText()
                .contains("pageKey=orders.detail"));
        verify(evidenceService).latestSuccessfulConversation(
                "orders",
                "orders.detail",
                observedAfter);
    }

    @Test
    void keepsMissingObservationPending() {
        PageWorkbenchObservationPort evidenceService =
                mock(PageWorkbenchObservationPort.class);
        LocalDateTime observedAfter =
                LocalDateTime.of(2026, 7, 26, 10, 0);
        when(evidenceService.latestSuccessfulConversation(
                "orders",
                "orders.detail",
                observedAfter))
                .thenReturn(ConversationEvidence.pending(
                        "No exact page conversation observed."));
        PageWorkbenchBrowserReadinessApplicationService service =
                new PageWorkbenchBrowserReadinessApplicationService(
                        evidenceService,
                        new ObjectMapper());

        ReadinessItem readiness = service.evaluate(
                "orders",
                "orders.detail",
                observedAfter);

        assertEquals("PENDING", readiness.status());
        assertTrue(readiness.message().contains("No exact page"));
        assertTrue(readiness.message().contains("业务系统打开当前页面"));
    }

    @Test
    void keepsTaskDetailReadableWhenConversationEvidenceDatabaseIsUnavailable() {
        PageWorkbenchObservationPort evidenceService =
                mock(PageWorkbenchObservationPort.class);
        LocalDateTime observedAfter =
                LocalDateTime.of(2026, 7, 26, 10, 0);
        when(evidenceService.latestSuccessfulConversation(
                "orders",
                "orders.detail",
                observedAfter))
                .thenThrow(new RecoverableDataAccessException(
                        "connection closed"));
        PageWorkbenchBrowserReadinessApplicationService service =
                new PageWorkbenchBrowserReadinessApplicationService(
                        evidenceService,
                        new ObjectMapper());

        ReadinessItem readiness = service.evaluate(
                "orders",
                "orders.detail",
                observedAfter);

        assertEquals("PENDING", readiness.status());
        assertTrue(readiness.message().contains("平台数据库连接"));
        assertEquals(
                "DATA_ACCESS_UNAVAILABLE",
                readiness.evidence().path("reason").asText());
        assertTrue(readiness.evidence().path("connectionError").isMissingNode());
    }
}

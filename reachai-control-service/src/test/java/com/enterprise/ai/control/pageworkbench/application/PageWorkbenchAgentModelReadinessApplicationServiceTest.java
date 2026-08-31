package com.enterprise.ai.control.pageworkbench.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PageWorkbenchAgentModelReadinessApplicationServiceTest {

    @Test
    void failsWhenBoundModelMostRecentlyFailed() {
        PageWorkbenchModelPort client = mock(PageWorkbenchModelPort.class);
        when(client.getInternal("model-orders")).thenReturn(ResponseEntity.ok(Map.of(
                "code", 200,
                "data", Map.of(
                        "id", "model-orders",
                        "name", "Orders LLM",
                        "status", "ACTIVE",
                        "lastTestStatus", "FAILED",
                        "lastTestAt", LocalDateTime.now().toString()))));
        PageWorkbenchAgentModelReadinessApplicationService service =
                new PageWorkbenchAgentModelReadinessApplicationService(
                        client,
                        new ObjectMapper().findAndRegisterModules());

        var result = service.evaluate("model-orders");

        assertEquals("FAIL", result.status());
        assertEquals("FAILED", result.evidence().path("lastTestStatus").asText());
    }

    @Test
    void passesOnlyForRecentlySuccessfulActiveModel() {
        PageWorkbenchModelPort client = mock(PageWorkbenchModelPort.class);
        when(client.getInternal("model-orders")).thenReturn(ResponseEntity.ok(Map.of(
                "code", 200,
                "data", Map.of(
                        "id", "model-orders",
                        "name", "Orders LLM",
                        "status", "ACTIVE",
                        "lastTestStatus", "SUCCESS",
                        "lastTestAt", LocalDateTime.now().minusMinutes(5).toString()))));
        PageWorkbenchAgentModelReadinessApplicationService service =
                new PageWorkbenchAgentModelReadinessApplicationService(
                        client,
                        new ObjectMapper().findAndRegisterModules());

        assertEquals("PASS", service.evaluate("model-orders").status());
    }

    @Test
    void staleSuccessRequiresRetest() {
        PageWorkbenchModelPort client = mock(PageWorkbenchModelPort.class);
        when(client.getInternal("model-orders")).thenReturn(ResponseEntity.ok(Map.of(
                "code", 200,
                "data", Map.of(
                        "id", "model-orders",
                        "name", "Orders LLM",
                        "status", "ACTIVE",
                        "lastTestStatus", "SUCCESS",
                        "lastTestAt", LocalDateTime.now().minusHours(25).toString()))));
        PageWorkbenchAgentModelReadinessApplicationService service =
                new PageWorkbenchAgentModelReadinessApplicationService(
                        client,
                        new ObjectMapper().findAndRegisterModules());

        assertEquals("PENDING", service.evaluate("model-orders").status());
    }
}

package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowReleaseReadinessView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PageWorkbenchReleaseReadinessApplicationServiceTest {

    private RuntimeProxyClient runtimeClient;
    private PageWorkbenchReleaseReadinessApplicationService service;

    @BeforeEach
    void setUp() {
        runtimeClient = mock(RuntimeProxyClient.class);
        service = new PageWorkbenchReleaseReadinessApplicationService(
                runtimeClient,
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void mapsRuntimePassAndEvidenceWithoutTrustingClientBooleans() {
        when(runtimeClient.pageWorkbenchReleaseReadiness(
                "orders",
                "orders.detail",
                "wf-orders",
                "v2.0.0")).thenReturn(ResponseEntity.ok(view("PASS")));

        ReadinessItem result = service.evaluate(
                "orders",
                "orders.detail",
                "wf-orders",
                "v2.0.0");

        assertEquals("PRE_RELEASE_READY", result.key());
        assertEquals("PASS", result.status());
        assertEquals("wf-orders", result.evidence().path("workflowId").asText());
        assertTrue(result.evidence().path("releaseValid").asBoolean());
    }

    @Test
    void keepsMissingRuntimeResponsePending() {
        when(runtimeClient.pageWorkbenchReleaseReadiness(
                "orders",
                "orders.detail",
                "wf-orders",
                "v2.0.0")).thenReturn(ResponseEntity.ok().build());

        ReadinessItem result = service.evaluate(
                "orders",
                "orders.detail",
                "wf-orders",
                "v2.0.0");

        assertEquals("PENDING", result.status());
    }

    @Test
    void exposesWaitingStateBeforeAnArtifactExists() {
        ReadinessItem result = service.waitingForArtifact();

        assertEquals("PRE_RELEASE_READY", result.key());
        assertEquals("PENDING", result.status());
    }

    private WorkflowReleaseReadinessView view(String status) {
        return new WorkflowReleaseReadinessView(
                status,
                "PRE_RELEASE_READY",
                "ReachAI 发布校验通过",
                "orders",
                "orders.detail",
                "wf-orders",
                "v2.0.0",
                "PAGE_ASSISTANT",
                "DRAFT",
                "VALIDATED_DRAFT",
                true,
                null,
                null,
                List.of(),
                List.of(),
                LocalDateTime.now());
    }
}

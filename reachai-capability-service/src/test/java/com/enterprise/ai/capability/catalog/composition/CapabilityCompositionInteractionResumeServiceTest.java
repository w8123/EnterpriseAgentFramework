package com.enterprise.ai.capability.catalog.composition;

import com.enterprise.ai.capability.client.runtime.CapabilityRuntimeInteractionClient;
import com.enterprise.ai.capability.internal.CapabilityToolExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CapabilityCompositionInteractionResumeServiceTest {

    private final CapabilityRuntimeInteractionClient interactionClient = mock(CapabilityRuntimeInteractionClient.class);
    private final CapabilityToolExecutionService executionService = mock(CapabilityToolExecutionService.class);
    private final CapabilityCompositionInteractionResumeService service =
            new CapabilityCompositionInteractionResumeService(interactionClient, new ObjectMapper(), executionService);

    @Test
    void submitsAdminTestConfirmInteractionToTargetTool() {
        CapabilityRuntimeInteractionClient.SkillInteractionRecord row = pendingConfirmInteraction();
        when(interactionClient.getInteraction("ix-1")).thenReturn(row);
        when(executionService.execute("orders_create", Map.of("input", Map.of("orderId", "A-1"))))
                .thenReturn(Map.of("success", true, "data", Map.of("status", "ok")));

        CapabilityCompositionInteractionResumeService.ResumeResult result =
                service.resumeAdminTest("order_form", "ix-1", "submit", Map.of());

        assertEquals(true, result.success());
        assertEquals("{status=ok}", result.result());
        assertEquals(false, result.interactionPending());
        verify(executionService).execute("orders_create", Map.of("input", Map.of("orderId", "A-1")));
        verify(interactionClient).updateInteraction("ix-1", new CapabilityRuntimeInteractionClient.InteractionUpdateRequest(
                "SUBMITTED", null, null, null, null));
    }

    @Test
    void collectInteractionReturnsSummaryBeforeExecutingTargetTool() {
        CapabilityRuntimeInteractionClient.SkillInteractionRecord row = pendingCollectInteraction();
        when(interactionClient.getInteraction("ix-2")).thenReturn(row);

        CapabilityCompositionInteractionResumeService.ResumeResult result =
                service.resumeAdminTest("order_form", "ix-2", "submit", Map.of("orderId", "A-1"));

        assertEquals(true, result.success());
        assertEquals(true, result.interactionPending());
        assertEquals("ix-2", result.interactionId());
        assertEquals("summary_card", result.uiRequest().get("component"));
        assertEquals(2, ((java.util.List<?>) result.uiRequest().get("actions")).size());
        verifyNoInteractions(executionService);
    }

    @Test
    void modifyConfirmInteractionReturnsFormWithoutExecutingTargetTool() {
        CapabilityRuntimeInteractionClient.SkillInteractionRecord row = pendingConfirmInteraction();
        when(interactionClient.getInteraction("ix-1")).thenReturn(row);

        CapabilityCompositionInteractionResumeService.ResumeResult result =
                service.resumeAdminTest("order_form", "ix-1", "modify", Map.of());

        assertEquals(true, result.success());
        assertEquals(true, result.interactionPending());
        assertEquals("form", result.uiRequest().get("component"));
        verifyNoInteractions(executionService);
    }

    @Test
    void reportsMissingInteractionWhenRuntimeInternalApiReturnsNotFound() {
        when(interactionClient.getInteraction("missing")).thenThrow(new FeignException.NotFound(
                "not found",
                Request.create(Request.HttpMethod.GET, "/internal/runtime/interactions/missing", Map.of(),
                        null, StandardCharsets.UTF_8, null),
                new byte[0],
                Map.of()));

        CapabilityCompositionInteractionResumeService.ResumeResult result =
                service.resumeAdminTest("order_form", "missing", "submit", Map.of());

        assertEquals(false, result.success());
        assertEquals("交互不存在或已失效", result.errorMessage());
        verifyNoInteractions(executionService);
    }

    private CapabilityRuntimeInteractionClient.SkillInteractionRecord pendingConfirmInteraction() {
        return interaction("ix-1", "{\"phase\":\"CONFIRM\",\"slots\":{\"orderId\":\"A-1\"}}");
    }

    private CapabilityRuntimeInteractionClient.SkillInteractionRecord pendingCollectInteraction() {
        return interaction("ix-2", "{\"phase\":\"COLLECT\",\"slots\":{}}");
    }

    private CapabilityRuntimeInteractionClient.SkillInteractionRecord interaction(String id, String slotState) {
        return new CapabilityRuntimeInteractionClient.SkillInteractionRecord(
                id,
                "trace",
                "session",
                CapabilityCompositionInteractionService.COMPOSITION_TEST_SESSION_ID,
                null,
                "order_form",
                "PENDING",
                slotState,
                null,
                null,
                "{\"targetTool\":\"orders_create\",\"fields\":[{\"key\":\"orderId\",\"label\":\"Order\",\"required\":true}]}",
                null,
                null,
                null
        );
    }
}

package com.enterprise.ai.capability.catalog.composition;

import com.enterprise.ai.capability.client.runtime.CapabilityRuntimeInteractionClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityCompositionInteractionServiceTest {

    private final CapabilityRuntimeInteractionClient interactionClient = mock(CapabilityRuntimeInteractionClient.class);
    private final CapabilityCompositionInteractionService service =
            new CapabilityCompositionInteractionService(interactionClient, new ObjectMapper());

    @Test
    void listsAdminTestPendingInteractionsWithUiTitlePreview() {
        CapabilityRuntimeInteractionClient.SkillInteractionRecord row = pending("ix-1", "order_form", "PENDING",
                "{\"title\":\"Confirm order\"}", LocalDateTime.of(2026, 6, 30, 10, 0));
        when(interactionClient.listPendingAdminTestInteractions()).thenReturn(List.of(row));

        List<CapabilityCompositionInteractionService.PendingAdminTestInteractionDTO> result =
                service.listPendingForAdminTest();

        assertEquals(1, result.size());
        assertEquals("ix-1", result.get(0).interactionId());
        assertEquals("order_form", result.get(0).skillName());
        assertEquals("Confirm order", result.get(0).uiTitle());
        verify(interactionClient).listPendingAdminTestInteractions();
    }

    @Test
    void cancelsOnlyAdminTestPendingInteraction() {
        when(interactionClient.cancelAdminTestInteraction("ix-1"))
                .thenReturn(new CapabilityRuntimeInteractionClient.CancelResult("CANCELLED", null));

        CapabilityCompositionInteractionService.CancelResult result =
                service.cancelPendingForAdminTest("ix-1");

        assertEquals(CapabilityCompositionInteractionService.CancelStatus.CANCELLED, result.status());
        verify(interactionClient).cancelAdminTestInteraction("ix-1");
    }

    @Test
    void rejectsCancellingNonAdminInteraction() {
        when(interactionClient.cancelAdminTestInteraction("ix-1"))
                .thenReturn(new CapabilityRuntimeInteractionClient.CancelResult("FORBIDDEN", "not admin"));

        CapabilityCompositionInteractionService.CancelResult result =
                service.cancelPendingForAdminTest("ix-1");

        assertEquals(CapabilityCompositionInteractionService.CancelStatus.FORBIDDEN, result.status());
    }

    @Test
    void cancelsAllAdminTestPendingInteractions() {
        when(interactionClient.cancelAllAdminTestInteractions())
                .thenReturn(new CapabilityRuntimeInteractionClient.CountResult(2));

        int cancelled = service.cancelAllPendingForAdminTest();

        assertEquals(2, cancelled);
        verify(interactionClient).cancelAllAdminTestInteractions();
    }

    private CapabilityRuntimeInteractionClient.SkillInteractionRecord pending(String id,
                                                                              String skillName,
                                                                              String status,
                                                                              String uiPayload,
                                                                              LocalDateTime createdAt) {
        return new CapabilityRuntimeInteractionClient.SkillInteractionRecord(
                id,
                "trace",
                "session",
                CapabilityCompositionInteractionService.COMPOSITION_TEST_SESSION_ID,
                null,
                skillName,
                status,
                null,
                null,
                uiPayload,
                null,
                createdAt,
                null,
                null
        );
    }
}

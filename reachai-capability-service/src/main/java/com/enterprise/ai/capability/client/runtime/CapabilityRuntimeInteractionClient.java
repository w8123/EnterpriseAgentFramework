package com.enterprise.ai.capability.client.runtime;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.time.LocalDateTime;
import java.util.List;

@FeignClient(name = "reachai-runtime-interactions", url = "${services.runtime-service.url:http://localhost:18604}")
public interface CapabilityRuntimeInteractionClient {

    @GetMapping("/internal/runtime/interactions/admin-test/pending")
    List<SkillInteractionRecord> listPendingAdminTestInteractions();

    @GetMapping("/internal/runtime/interactions/{interactionId}")
    SkillInteractionRecord getInteraction(@PathVariable("interactionId") String interactionId);

    @PatchMapping("/internal/runtime/interactions/{interactionId}")
    SkillInteractionRecord updateInteraction(@PathVariable("interactionId") String interactionId,
                                             @RequestBody InteractionUpdateRequest request);

    @DeleteMapping("/internal/runtime/interactions/admin-test/{interactionId}")
    CancelResult cancelAdminTestInteraction(@PathVariable("interactionId") String interactionId);

    @DeleteMapping("/internal/runtime/interactions/admin-test")
    CountResult cancelAllAdminTestInteractions();

    record SkillInteractionRecord(
            String id,
            String traceId,
            String sessionId,
            String userId,
            Long agentId,
            String skillName,
            String status,
            String slotState,
            String pendingKeys,
            String uiPayload,
            String specSnapshot,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime expiresAt
    ) {
    }

    record InteractionUpdateRequest(
            String status,
            String slotState,
            String pendingKeys,
            String uiPayload,
            Boolean clearPendingKeys
    ) {
    }

    record CancelResult(String status, String message) {
    }

    record CountResult(int count) {
    }
}

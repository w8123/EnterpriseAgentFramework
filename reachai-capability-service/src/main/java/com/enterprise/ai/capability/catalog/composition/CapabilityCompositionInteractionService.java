package com.enterprise.ai.capability.catalog.composition;

import com.enterprise.ai.capability.client.runtime.CapabilityRuntimeInteractionClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CapabilityCompositionInteractionService {

    public static final String COMPOSITION_TEST_SESSION_ID = "composition-admin-test";

    private final CapabilityRuntimeInteractionClient interactionClient;
    private final ObjectMapper objectMapper;

    public List<PendingAdminTestInteractionDTO> listPendingForAdminTest() {
        List<CapabilityRuntimeInteractionClient.SkillInteractionRecord> rows =
                interactionClient.listPendingAdminTestInteractions();
        return rows.stream().map(this::toDto).toList();
    }

    public CancelResult cancelPendingForAdminTest(String interactionId) {
        CapabilityRuntimeInteractionClient.CancelResult result =
                interactionClient.cancelAdminTestInteraction(interactionId);
        return new CancelResult(cancelStatus(result == null ? null : result.status()),
                result == null ? null : result.message());
    }

    public int cancelAllPendingForAdminTest() {
        CapabilityRuntimeInteractionClient.CountResult result =
                interactionClient.cancelAllAdminTestInteractions();
        return result == null ? 0 : result.count();
    }

    private PendingAdminTestInteractionDTO toDto(CapabilityRuntimeInteractionClient.SkillInteractionRecord entity) {
        return new PendingAdminTestInteractionDTO(
                entity.id(),
                entity.skillName(),
                entity.status(),
                entity.createdAt(),
                entity.updatedAt(),
                entity.expiresAt(),
                previewTitle(entity.uiPayload()));
    }

    private CancelStatus cancelStatus(String status) {
        if ("CANCELLED".equalsIgnoreCase(status)) {
            return CancelStatus.CANCELLED;
        }
        if ("FORBIDDEN".equalsIgnoreCase(status)) {
            return CancelStatus.FORBIDDEN;
        }
        if ("NOT_PENDING".equalsIgnoreCase(status)) {
            return CancelStatus.NOT_PENDING;
        }
        return CancelStatus.NOT_FOUND;
    }

    private String previewTitle(String uiPayloadJson) {
        if (uiPayloadJson == null || uiPayloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(uiPayloadJson);
            JsonNode title = node.get("title");
            return title != null && title.isTextual() ? title.asText() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public record PendingAdminTestInteractionDTO(
            String interactionId,
            String skillName,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime expiresAt,
            String uiTitle) {
    }

    public record CancelResult(CancelStatus status, String message) {
        static CancelResult cancelled() {
            return new CancelResult(CancelStatus.CANCELLED, null);
        }
    }

    public enum CancelStatus {
        CANCELLED,
        NOT_FOUND,
        FORBIDDEN,
        NOT_PENDING
    }
}

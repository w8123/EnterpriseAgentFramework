package com.enterprise.ai.runtime.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.runtime.interaction.RuntimeSkillInteractionEntity;
import com.enterprise.ai.runtime.interaction.RuntimeSkillInteractionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/internal/runtime/interactions")
@RequiredArgsConstructor
public class RuntimeInteractionInternalController {

    public static final String COMPOSITION_TEST_SESSION_ID = "composition-admin-test";
    private static final String PENDING = "PENDING";
    private static final String CANCELLED = "CANCELLED";

    private final RuntimeSkillInteractionMapper mapper;

    @GetMapping("/admin-test/pending")
    public ResponseEntity<List<SkillInteractionRecord>> listPendingAdminTestInteractions() {
        List<RuntimeSkillInteractionEntity> rows = mapper.selectList(new LambdaQueryWrapper<RuntimeSkillInteractionEntity>()
                .eq(RuntimeSkillInteractionEntity::getUserId, COMPOSITION_TEST_SESSION_ID)
                .eq(RuntimeSkillInteractionEntity::getStatus, PENDING)
                .orderByDesc(RuntimeSkillInteractionEntity::getCreatedAt));
        return ResponseEntity.ok(rows.stream().map(this::toRecord).toList());
    }

    @GetMapping("/{interactionId}")
    public ResponseEntity<SkillInteractionRecord> getInteraction(@PathVariable String interactionId) {
        RuntimeSkillInteractionEntity row = mapper.selectById(interactionId);
        if (row == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toRecord(row));
    }

    @PatchMapping("/{interactionId}")
    public ResponseEntity<SkillInteractionRecord> updateInteraction(@PathVariable String interactionId,
                                                                    @RequestBody InteractionUpdateRequest request) {
        RuntimeSkillInteractionEntity row = mapper.selectById(interactionId);
        if (row == null) {
            return ResponseEntity.notFound().build();
        }
        apply(row, request);
        row.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(row);
        return ResponseEntity.ok(toRecord(row));
    }

    @DeleteMapping("/admin-test/{interactionId}")
    public ResponseEntity<CancelResult> cancelAdminTestInteraction(@PathVariable String interactionId) {
        RuntimeSkillInteractionEntity row = mapper.selectById(interactionId);
        if (row == null) {
            return ResponseEntity.ok(new CancelResult("NOT_FOUND", "interaction does not exist"));
        }
        if (!COMPOSITION_TEST_SESSION_ID.equals(row.getUserId())) {
            return ResponseEntity.ok(new CancelResult("FORBIDDEN", "interaction is not an admin-test interaction"));
        }
        if (!PENDING.equalsIgnoreCase(row.getStatus())) {
            return ResponseEntity.ok(new CancelResult("NOT_PENDING", "interaction is already closed"));
        }
        row.setStatus(CANCELLED);
        row.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(row);
        return ResponseEntity.ok(new CancelResult(CANCELLED, null));
    }

    @DeleteMapping("/admin-test")
    public ResponseEntity<CountResult> cancelAllAdminTestInteractions() {
        List<RuntimeSkillInteractionEntity> rows = mapper.selectList(new LambdaQueryWrapper<RuntimeSkillInteractionEntity>()
                .eq(RuntimeSkillInteractionEntity::getUserId, COMPOSITION_TEST_SESSION_ID)
                .eq(RuntimeSkillInteractionEntity::getStatus, PENDING));
        LocalDateTime now = LocalDateTime.now();
        int cancelled = 0;
        for (RuntimeSkillInteractionEntity row : rows) {
            row.setStatus(CANCELLED);
            row.setUpdatedAt(now);
            mapper.updateById(row);
            cancelled++;
        }
        return ResponseEntity.ok(new CountResult(cancelled));
    }

    private void apply(RuntimeSkillInteractionEntity row, InteractionUpdateRequest request) {
        if (request == null) {
            return;
        }
        if (request.status() != null) {
            row.setStatus(request.status());
        }
        if (request.slotState() != null) {
            row.setSlotState(request.slotState());
        }
        if (request.pendingKeys() != null) {
            row.setPendingKeys(request.pendingKeys());
        }
        if (request.uiPayload() != null) {
            row.setUiPayload(request.uiPayload());
        }
        if (request.clearPendingKeys() != null && request.clearPendingKeys()) {
            row.setPendingKeys(null);
        }
    }

    private SkillInteractionRecord toRecord(RuntimeSkillInteractionEntity row) {
        return new SkillInteractionRecord(
                row.getId(),
                row.getTraceId(),
                row.getSessionId(),
                row.getUserId(),
                row.getAgentId(),
                row.getSkillName(),
                row.getStatus(),
                row.getSlotState(),
                row.getPendingKeys(),
                row.getUiPayload(),
                row.getSpecSnapshot(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getExpiresAt()
        );
    }

    public record SkillInteractionRecord(
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

    public record InteractionUpdateRequest(
            String status,
            String slotState,
            String pendingKeys,
            String uiPayload,
            Boolean clearPendingKeys
    ) {
    }

    public record CancelResult(String status, String message) {
    }

    public record CountResult(int count) {
    }
}

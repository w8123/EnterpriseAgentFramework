package com.enterprise.ai.agent.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.model.ChatRequest;
import com.enterprise.ai.agent.model.interactive.UiRequestPayload;
import com.enterprise.ai.agent.model.interactive.UiSubmitPayload;
import com.enterprise.ai.agent.skill.interactive.HumanApprovalResumeService;
import com.enterprise.ai.agent.skill.interactive.InteractiveFormResumeService;
import com.enterprise.ai.agent.skill.interactive.SkillInteractionEntity;
import com.enterprise.ai.agent.skill.interactive.SkillInteractionMapper;
import com.enterprise.ai.agent.skill.interactive.SkillInteractionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/interactions")
@RequiredArgsConstructor
public class AgentInteractionController {

    private final SkillInteractionMapper skillInteractionMapper;
    private final InteractiveFormResumeService interactiveFormResumeService;
    private final ObjectMapper objectMapper;

    @GetMapping("/human-approvals")
    public ResponseEntity<List<PendingHumanApprovalDTO>> humanApprovals(@RequestParam(required = false) Long agentId,
                                                                        @RequestParam(required = false) String userId,
                                                                        @RequestParam(defaultValue = "50") int limit) {
        LambdaQueryWrapper<SkillInteractionEntity> query = new LambdaQueryWrapper<SkillInteractionEntity>()
                .eq(SkillInteractionEntity::getStatus, SkillInteractionStatus.PENDING)
                .likeRight(SkillInteractionEntity::getSkillName, HumanApprovalResumeService.SKILL_PREFIX)
                .orderByDesc(SkillInteractionEntity::getCreatedAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200)));
        if (agentId != null) {
            query.eq(SkillInteractionEntity::getAgentId, agentId);
        }
        if (userId != null && !userId.isBlank()) {
            query.eq(SkillInteractionEntity::getUserId, userId.trim());
        }
        return ResponseEntity.ok(skillInteractionMapper.selectList(query).stream()
                .map(this::toPendingHumanApproval)
                .toList());
    }

    @PostMapping("/human-approvals/{interactionId}/submit")
    public ResponseEntity<AgentResult> submitHumanApproval(@PathVariable String interactionId,
                                                           @RequestBody HumanApprovalSubmitRequest request) {
        ChatRequest chat = ChatRequest.builder()
                .interactionId(interactionId)
                .userId(request == null ? null : request.userId())
                .uiSubmit(UiSubmitPayload.builder()
                        .action(request == null ? null : request.action())
                        .values(request == null || request.values() == null ? Map.of() : request.values())
                        .build())
                .build();
        return ResponseEntity.ok(interactiveFormResumeService.resume(chat, request == null ? null : request.sessionId()));
    }

    @DeleteMapping("/human-approvals/{interactionId}")
    public ResponseEntity<AgentResult> cancelHumanApproval(@PathVariable String interactionId,
                                                           @RequestParam(required = false) String userId) {
        ChatRequest chat = ChatRequest.builder()
                .interactionId(interactionId)
                .userId(userId)
                .uiSubmit(UiSubmitPayload.builder()
                        .action("cancel")
                        .values(Map.of())
                        .build())
                .build();
        return ResponseEntity.ok(interactiveFormResumeService.resume(chat, null));
    }

    private PendingHumanApprovalDTO toPendingHumanApproval(SkillInteractionEntity row) {
        UiRequestPayload uiRequest = readUiPayload(row.getUiPayload());
        return new PendingHumanApprovalDTO(
                row.getId(),
                row.getTraceId(),
                row.getSessionId(),
                row.getUserId(),
                row.getAgentId(),
                nodeId(row.getSkillName()),
                row.getStatus(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getExpiresAt(),
                uiRequest == null ? null : uiRequest.getTitle(),
                uiRequest == null ? null : uiRequest.getMessage(),
                uiRequest,
                readJsonMap(row.getSlotState()));
    }

    private UiRequestPayload readUiPayload(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, UiRequestPayload.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Object value = objectMapper.readValue(json, Object.class);
            return value instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : Map.of();
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String nodeId(String skillName) {
        if (skillName == null || !skillName.startsWith(HumanApprovalResumeService.SKILL_PREFIX)) {
            return "";
        }
        return skillName.substring(HumanApprovalResumeService.SKILL_PREFIX.length());
    }

    public record HumanApprovalSubmitRequest(String action,
                                             Map<String, Object> values,
                                             String userId,
                                             String sessionId) {
    }

    public record PendingHumanApprovalDTO(String interactionId,
                                          String traceId,
                                          String sessionId,
                                          String userId,
                                          Long agentId,
                                          String nodeId,
                                          String status,
                                          LocalDateTime createdAt,
                                          LocalDateTime updatedAt,
                                          LocalDateTime expiresAt,
                                          String title,
                                          String message,
                                          UiRequestPayload uiRequest,
                                          Map<String, Object> state) {
    }
}

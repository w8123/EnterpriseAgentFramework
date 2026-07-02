package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ContextMemoryCandidateController {

    private final ContextMemoryCandidateMapper candidateMapper;
    private final ContextItemMapper itemMapper;
    private final ContextNamespaceMapper namespaceMapper;
    private final ContextAuditEventMapper auditMapper;

    @GetMapping("/api/context/memory/candidates")
    public ResponseEntity<List<CandidateView>> list(
            @RequestParam String tenantId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "PROJECT_DEV") String memoryLane,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long namespaceId,
            @RequestParam(required = false) String candidateType,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String pageInstanceId,
            @RequestParam(required = false) String origin,
            @RequestParam(defaultValue = "false") Boolean includeExpired,
            @RequestParam(defaultValue = "100") Integer limit) {
        int boundedLimit = Math.max(1, Math.min(limit == null ? 100 : limit, 500));
        return ResponseEntity.ok(candidateMapper.selectList(Wrappers.<ContextMemoryCandidateEntity>lambdaQuery()
                        .eq(ContextMemoryCandidateEntity::getTenantId, trim(tenantId))
                        .eq(StringUtils.hasText(projectCode), ContextMemoryCandidateEntity::getProjectCode, trim(projectCode))
                        .eq(projectId != null, ContextMemoryCandidateEntity::getProjectId, projectId)
                        .eq(ContextMemoryCandidateEntity::getMemoryLane, defaultUpper(memoryLane, "PROJECT_DEV"))
                        .eq(StringUtils.hasText(userId), ContextMemoryCandidateEntity::getUserId, trim(userId))
                        .eq(StringUtils.hasText(status), ContextMemoryCandidateEntity::getStatus, upper(status))
                        .eq(namespaceId != null, ContextMemoryCandidateEntity::getNamespaceId, namespaceId)
                        .eq(StringUtils.hasText(candidateType), ContextMemoryCandidateEntity::getCandidateType, upper(candidateType))
                        .eq(StringUtils.hasText(sourceType), ContextMemoryCandidateEntity::getSourceType, upper(sourceType))
                        .eq(StringUtils.hasText(traceId), ContextMemoryCandidateEntity::getTraceId, trim(traceId))
                        .eq(StringUtils.hasText(pageInstanceId), ContextMemoryCandidateEntity::getPageInstanceId, trim(pageInstanceId))
                        .eq(StringUtils.hasText(origin), ContextMemoryCandidateEntity::getOrigin, trim(origin))
                        .and(!Boolean.TRUE.equals(includeExpired), q -> q
                                .isNull(ContextMemoryCandidateEntity::getExpiresAt)
                                .or()
                                .gt(ContextMemoryCandidateEntity::getExpiresAt, LocalDateTime.now()))
                        .ne(ContextMemoryCandidateEntity::getStatus, "DELETED")
                        .orderByDesc(ContextMemoryCandidateEntity::getUpdatedAt)
                        .orderByDesc(ContextMemoryCandidateEntity::getId)
                        .last("LIMIT " + boundedLimit))
                .stream()
                .map(this::candidateView)
                .toList());
    }

    @PostMapping("/api/context/memory/candidates")
    @Transactional
    public ResponseEntity<CandidateView> create(@RequestBody CandidateCommand command) {
        ContextMemoryCandidateEntity entity = new ContextMemoryCandidateEntity();
        entity.setCandidateKey("ctx-candidate-" + UUID.randomUUID().toString().replace("-", ""));
        entity.setTenantId(required(command.tenantId(), "tenantId"));
        entity.setProjectId(command.projectId());
        entity.setProjectCode(trim(command.projectCode()));
        entity.setNamespaceId(command.namespaceId());
        entity.setNamespaceKey(trim(command.namespaceKey()));
        entity.setMemoryLane(defaultUpper(command.memoryLane(), "PROJECT_DEV"));
        entity.setCandidateType(defaultUpper(command.candidateType(), "NOTE"));
        entity.setTitle(trim(command.title()));
        entity.setContent(required(command.content(), "content"));
        entity.setSummary(trim(command.summary()));
        entity.setReason(trim(command.reason()));
        entity.setSourceType(required(command.sourceType(), "sourceType").toUpperCase(Locale.ROOT));
        entity.setSourceRef(trim(command.sourceRef()));
        entity.setTraceId(trim(command.traceId()));
        entity.setSessionId(trim(command.sessionId()));
        entity.setUserId(trim(command.userId()));
        entity.setExternalUserId(trim(command.externalUserId()));
        entity.setGlobalUserId(trim(command.globalUserId()));
        entity.setAgentId(trim(command.agentId()));
        entity.setAgentKey(trim(command.agentKey()));
        entity.setWorkflowId(trim(command.workflowId()));
        entity.setWorkflowKey(trim(command.workflowKey()));
        entity.setPageInstanceId(trim(command.pageInstanceId()));
        entity.setOrigin(trim(command.origin()));
        entity.setConfidence(command.confidence() == null ? BigDecimal.valueOf(0.7) : command.confidence());
        entity.setTrustLevel(defaultUpper(command.trustLevel(), "LOW"));
        entity.setVisibility(defaultUpper(command.visibility(), "PRIVATE"));
        entity.setStatus("PENDING");
        entity.setProposedBy(trim(command.proposedBy()));
        entity.setExpiresAt(parseDate(command.expiresAt()));
        entity.setMetadataJson(trim(command.metadataJson()));
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(entity.getCreatedAt());
        candidateMapper.insert(entity);
        audit("CANDIDATE_CREATE", entity, null, entity.getProposedBy());
        return ResponseEntity.ok(candidateView(entity));
    }

    @PostMapping("/api/context/memory/candidates/{id}/approve")
    @Transactional
    public ResponseEntity<CandidateView> approve(@PathVariable Long id, @RequestBody ReviewCommand command) {
        return ResponseEntity.ok(candidateView(approveCandidate(id, command)));
    }

    @PostMapping("/api/context/memory/candidates/{id}/reject")
    @Transactional
    public ResponseEntity<CandidateView> reject(@PathVariable Long id, @RequestBody ReviewCommand command) {
        return ResponseEntity.ok(candidateView(rejectCandidate(id, command)));
    }

    @PutMapping("/api/context/memory/candidates/{id}")
    @Transactional
    public ResponseEntity<CandidateView> update(@PathVariable Long id, @RequestBody CandidateUpdateCommand command) {
        ContextMemoryCandidateEntity entity = pendingCandidate(id);
        if (command.namespaceId() != null) {
            entity.setNamespaceId(command.namespaceId());
        }
        if (command.namespaceKey() != null) {
            entity.setNamespaceKey(trim(command.namespaceKey()));
        }
        if (command.candidateType() != null) {
            entity.setCandidateType(upper(command.candidateType()));
        }
        if (command.title() != null) {
            entity.setTitle(trim(command.title()));
        }
        if (command.content() != null) {
            entity.setContent(command.content());
        }
        if (command.summary() != null) {
            entity.setSummary(trim(command.summary()));
        }
        if (command.reason() != null) {
            entity.setReason(trim(command.reason()));
        }
        if (command.sourceType() != null) {
            entity.setSourceType(upper(command.sourceType()));
        }
        if (command.sourceRef() != null) {
            entity.setSourceRef(trim(command.sourceRef()));
        }
        if (command.workflowId() != null) {
            entity.setWorkflowId(trim(command.workflowId()));
        }
        if (command.workflowKey() != null) {
            entity.setWorkflowKey(trim(command.workflowKey()));
        }
        if (command.pageInstanceId() != null) {
            entity.setPageInstanceId(trim(command.pageInstanceId()));
        }
        if (command.origin() != null) {
            entity.setOrigin(trim(command.origin()));
        }
        if (command.confidence() != null) {
            entity.setConfidence(command.confidence());
        }
        if (command.trustLevel() != null) {
            entity.setTrustLevel(upper(command.trustLevel()));
        }
        if (command.visibility() != null) {
            entity.setVisibility(upper(command.visibility()));
        }
        if (command.expiresAt() != null) {
            entity.setExpiresAt(parseDate(command.expiresAt()));
        }
        if (command.metadataJson() != null) {
            entity.setMetadataJson(trim(command.metadataJson()));
        }
        entity.setUpdatedAt(LocalDateTime.now());
        candidateMapper.updateById(entity);
        audit("CANDIDATE_UPDATE", entity, command.updateReason(), command.updatedBy());
        return ResponseEntity.ok(candidateView(entity));
    }

    @PostMapping("/api/context/memory/candidates/batch/approve")
    @Transactional
    public ResponseEntity<List<CandidateView>> batchApprove(@RequestBody BatchReviewCommand command) {
        return ResponseEntity.ok(command.candidateIds().stream()
                .map(id -> approveCandidate(id, command.review()))
                .map(this::candidateView)
                .toList());
    }

    @PostMapping("/api/context/memory/candidates/batch/reject")
    @Transactional
    public ResponseEntity<List<CandidateView>> batchReject(@RequestBody BatchReviewCommand command) {
        return ResponseEntity.ok(command.candidateIds().stream()
                .map(id -> rejectCandidate(id, command.review()))
                .map(this::candidateView)
                .toList());
    }

    private ContextMemoryCandidateEntity approveCandidate(Long id, ReviewCommand command) {
        ContextMemoryCandidateEntity candidate = pendingCandidate(id);
        ContextNamespaceEntity namespace = resolveNamespace(candidate.getNamespaceId(), candidate.getNamespaceKey());
        ContextItemEntity item = new ContextItemEntity();
        item.setItemKey("ctx-item-" + UUID.randomUUID().toString().replace("-", ""));
        item.setNamespaceId(namespace.getId());
        item.setItemType(candidateItemType(candidate.getCandidateType()));
        item.setMemoryLane(candidate.getMemoryLane());
        item.setTitle(candidate.getTitle());
        item.setContent(candidate.getContent());
        item.setSummary(candidate.getSummary());
        item.setMetadataJson(candidate.getMetadataJson());
        item.setSourceType(candidate.getSourceType());
        item.setSourceRef(candidate.getSourceRef());
        item.setConfidence(command.confidence() == null ? candidate.getConfidence() : command.confidence());
        item.setTrustLevel(defaultUpper(command.trustLevel(), candidate.getTrustLevel()));
        item.setVisibility(candidate.getVisibility());
        item.setStatus("ACTIVE");
        item.setCreatedBy(command.reviewedBy());
        item.setUpdatedBy(command.reviewedBy());
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(item.getCreatedAt());
        itemMapper.insert(item);
        candidate.setStatus("APPROVED");
        candidate.setReviewedBy(trim(command.reviewedBy()));
        candidate.setReviewedAt(LocalDateTime.now());
        candidate.setReviewReason(trim(command.reviewReason()));
        candidate.setApprovedItemId(item.getId());
        candidate.setUpdatedAt(candidate.getReviewedAt());
        candidateMapper.updateById(candidate);
        audit("CANDIDATE_APPROVE", candidate, command.reviewReason(), command.reviewedBy());
        return candidate;
    }

    private ContextMemoryCandidateEntity rejectCandidate(Long id, ReviewCommand command) {
        ContextMemoryCandidateEntity candidate = pendingCandidate(id);
        candidate.setStatus("REJECTED");
        candidate.setReviewedBy(trim(command.reviewedBy()));
        candidate.setReviewedAt(LocalDateTime.now());
        candidate.setReviewReason(trim(command.reviewReason()));
        candidate.setUpdatedAt(candidate.getReviewedAt());
        candidateMapper.updateById(candidate);
        audit("CANDIDATE_REJECT", candidate, command.reviewReason(), command.reviewedBy());
        return candidate;
    }

    private ContextMemoryCandidateEntity pendingCandidate(Long id) {
        ContextMemoryCandidateEntity entity = candidateMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Context memory candidate not found: " + id);
        }
        if (!"PENDING".equals(entity.getStatus())) {
            throw new IllegalArgumentException("Context memory candidate is not pending: " + id);
        }
        return entity;
    }

    private ContextNamespaceEntity resolveNamespace(Long namespaceId, String namespaceKey) {
        ContextNamespaceEntity namespace;
        if (namespaceId != null) {
            namespace = namespaceMapper.selectById(namespaceId);
        } else if (StringUtils.hasText(namespaceKey)) {
            namespace = namespaceMapper.selectOne(Wrappers.<ContextNamespaceEntity>lambdaQuery()
                    .eq(ContextNamespaceEntity::getNamespaceKey, trim(namespaceKey))
                    .last("LIMIT 1"));
        } else {
            throw new IllegalArgumentException("Context candidate namespaceId or namespaceKey is required");
        }
        if (namespace == null) {
            throw new IllegalArgumentException("Context namespace not found");
        }
        return namespace;
    }

    private void audit(String eventType, ContextMemoryCandidateEntity candidate, String reason, String actorId) {
        ContextAuditEventEntity event = new ContextAuditEventEntity();
        event.setEventType(eventType);
        event.setItemId(candidate.getApprovedItemId());
        event.setNamespaceId(candidate.getNamespaceId());
        event.setActorType("PLATFORM");
        event.setActorId(trim(actorId));
        event.setTenantId(candidate.getTenantId());
        event.setProjectId(candidate.getProjectId());
        event.setProjectCode(candidate.getProjectCode());
        event.setWorkflowId(candidate.getWorkflowId());
        event.setSessionId(candidate.getSessionId());
        event.setTraceId(candidate.getTraceId());
        event.setDecision(candidate.getStatus());
        event.setReason(trim(reason));
        event.setMetadataJson(candidate.getMetadataJson());
        event.setCreatedAt(LocalDateTime.now());
        auditMapper.insert(event);
    }

    private String candidateItemType(String candidateType) {
        String type = upper(candidateType);
        return "API_CONTEXT".equals(type) ? "API_CONTRACT" : type;
    }

    private CandidateView candidateView(ContextMemoryCandidateEntity entity) {
        return new CandidateView(entity.getId(), entity.getCandidateKey(), entity.getTenantId(),
                entity.getProjectId(), entity.getProjectCode(), entity.getNamespaceId(), entity.getNamespaceKey(),
                entity.getMemoryLane(), entity.getCandidateType(), entity.getTitle(), entity.getContent(),
                entity.getSummary(), entity.getReason(), entity.getSourceType(), entity.getSourceRef(),
                entity.getTraceId(), entity.getSessionId(), entity.getUserId(), entity.getExternalUserId(),
                entity.getGlobalUserId(), entity.getAgentId(), entity.getAgentKey(), entity.getWorkflowId(),
                entity.getWorkflowKey(), entity.getPageInstanceId(), entity.getOrigin(), entity.getConfidence(),
                entity.getTrustLevel(), entity.getVisibility(), entity.getStatus(), entity.getProposedBy(),
                entity.getReviewedBy(), entity.getReviewedAt(), entity.getReviewReason(), entity.getApprovedItemId(),
                entity.getMetadataJson(), entity.getExpiresAt(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private LocalDateTime parseDate(String value) {
        String text = trim(value);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toLocalDateTime();
        } catch (Exception ignored) {
            return LocalDateTime.parse(text);
        }
    }

    private String required(String value, String field) {
        String text = trim(value);
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("Context candidate " + field + " is required");
        }
        return text;
    }

    private String defaultUpper(String value, String fallback) {
        String text = trim(value);
        return StringUtils.hasText(text) ? text.toUpperCase(Locale.ROOT) : fallback;
    }

    private String upper(String value) {
        String text = trim(value);
        return StringUtils.hasText(text) ? text.toUpperCase(Locale.ROOT) : null;
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record CandidateCommand(String tenantId, Long projectId, String projectCode,
                                   Long namespaceId, String namespaceKey, String memoryLane,
                                   String candidateType, String title, String content, String summary,
                                   String reason, String sourceType, String sourceRef, String traceId,
                                   String sessionId, String userId, String externalUserId, String globalUserId,
                                   String agentId, String agentKey, String workflowId, String workflowKey,
                                   String pageInstanceId, String origin, BigDecimal confidence, String trustLevel,
                                   String visibility, String proposedBy, String expiresAt, String metadataJson) {
    }

    public record CandidateUpdateCommand(String tenantId, String projectCode, Long projectId, String memoryLane,
                                         String userId, String updatedBy, String updateReason,
                                         Long namespaceId, String namespaceKey, String candidateType,
                                         String title, String content, String summary, String reason,
                                         String sourceType, String sourceRef, String workflowId,
                                         String workflowKey, String pageInstanceId, String origin,
                                         BigDecimal confidence, String trustLevel, String visibility,
                                         String expiresAt, String metadataJson) {
    }

    public record ReviewCommand(String tenantId, String projectCode, Long projectId, String memoryLane,
                                String userId, String reviewedBy, String reviewReason,
                                BigDecimal confidence, String trustLevel) {
    }

    public record BatchReviewCommand(String tenantId, String projectCode, Long projectId, String memoryLane,
                                     String userId, String reviewedBy, String reviewReason,
                                     BigDecimal confidence, String trustLevel, List<Long> candidateIds) {
        ReviewCommand review() {
            return new ReviewCommand(tenantId, projectCode, projectId, memoryLane, userId, reviewedBy,
                    reviewReason, confidence, trustLevel);
        }
    }

    public record CandidateView(Long id, String candidateKey, String tenantId, Long projectId, String projectCode,
                                Long namespaceId, String namespaceKey, String memoryLane, String candidateType,
                                String title, String content, String summary, String reason, String sourceType,
                                String sourceRef, String traceId, String sessionId, String userId,
                                String externalUserId, String globalUserId, String agentId, String agentKey,
                                String workflowId, String workflowKey, String pageInstanceId, String origin,
                                BigDecimal confidence, String trustLevel, String visibility, String status,
                                String proposedBy, String reviewedBy, LocalDateTime reviewedAt,
                                String reviewReason, Long approvedItemId, String metadataJson,
                                LocalDateTime expiresAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }
}

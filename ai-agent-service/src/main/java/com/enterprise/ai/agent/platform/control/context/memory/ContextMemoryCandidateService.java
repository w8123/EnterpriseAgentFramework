package com.enterprise.ai.agent.platform.control.context.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.platform.control.context.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContextMemoryCandidateService {

    private static final int DEFAULT_TTL_DAYS = 7;

    private final ContextMemoryCandidateMapper candidateMapper;
    private final ContextItemService itemService;
    private final ContextNamespaceService namespaceService;
    private final ContextAuditService auditService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ContextMemoryCandidateResponse createCandidate(ContextMemoryCandidateCreateRequest request) {
        validateCreateRequest(request);
        MemoryLane memoryLane = parseMemoryLaneOrDefault(request.getMemoryLane(), MemoryLane.RUNTIME_USER);
        String resolvedUserId = resolveUserId(
                request.getGlobalUserId(), request.getExternalUserId(), request.getUserId());
        if (memoryLane == MemoryLane.RUNTIME_USER && !StringUtils.hasText(resolvedUserId)) {
            throw new IllegalArgumentException("userId is required for memory candidate");
        }
        if (memoryLane == MemoryLane.PROJECT_DEV) {
            requireProjectDevScope(request.getProjectCode(), request.getProjectId());
            resolvedUserId = null;
        }
        ContextMemoryCandidateType candidateType = parseCandidateType(request.getCandidateType());
        ContextMemoryCandidateEntity duplicate = findPendingDuplicate(request, resolvedUserId, candidateType, memoryLane);
        if (duplicate != null) {
            return ContextMemoryCandidateViewMapper.toResponse(duplicate);
        }

        ContextMemoryCandidateEntity entity = new ContextMemoryCandidateEntity();
        entity.setCandidateKey("ctx-candidate-" + java.util.UUID.randomUUID().toString().replace("-", ""));
        entity.setTenantId(request.getTenantId().trim());
        entity.setProjectId(request.getProjectId());
        entity.setProjectCode(request.getProjectCode());
        entity.setNamespaceId(request.getNamespaceId());
        entity.setNamespaceKey(request.getNamespaceKey());
        entity.setMemoryLane(memoryLane.name());
        entity.setCandidateType(candidateType.name());
        entity.setTitle(request.getTitle());
        entity.setContent(request.getContent().trim());
        entity.setSummary(request.getSummary());
        entity.setReason(request.getReason());
        entity.setSourceType(parseSourceType(request.getSourceType()).name());
        entity.setSourceRef(request.getSourceRef());
        entity.setTraceId(request.getTraceId());
        entity.setSessionId(request.getSessionId());
        entity.setUserId(resolvedUserId);
        entity.setExternalUserId(memoryLane == MemoryLane.PROJECT_DEV ? null : request.getExternalUserId());
        entity.setGlobalUserId(memoryLane == MemoryLane.PROJECT_DEV ? null : request.getGlobalUserId());
        entity.setAgentId(request.getAgentId());
        entity.setAgentKey(request.getAgentKey());
        entity.setWorkflowId(request.getWorkflowId());
        entity.setWorkflowKey(request.getWorkflowKey());
        entity.setPageInstanceId(request.getPageInstanceId());
        entity.setOrigin(request.getOrigin());
        entity.setConfidence(defaultConfidence(request.getConfidence()));
        entity.setTrustLevel(defaultTrust(request.getTrustLevel()));
        entity.setVisibility(defaultVisibility(request.getVisibility(), memoryLane));
        entity.setStatus(ContextMemoryCandidateStatus.PENDING.name());
        entity.setProposedBy(StringUtils.hasText(request.getProposedBy()) ? request.getProposedBy() : resolvedUserId);
        entity.setMetadataJson(request.getMetadataJson());
        entity.setExpiresAt(request.getExpiresAt() != null
                ? request.getExpiresAt()
                : LocalDateTime.now().plusDays(DEFAULT_TTL_DAYS));
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        candidateMapper.insert(entity);

        auditCandidate(ContextAuditEventType.CANDIDATE_CREATE, ContextAuditDecision.ALLOW,
                "candidate created", entity, null, null, entity.getProposedBy());
        return ContextMemoryCandidateViewMapper.toResponse(entity);
    }

    @Transactional
    public List<ContextMemoryCandidateResponse> createCandidates(List<ContextMemoryCandidateCreateRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        prevalidateCreateCandidates(requests);
        return requests.stream()
                .map(this::createCandidate)
                .toList();
    }

    private void prevalidateCreateCandidates(List<ContextMemoryCandidateCreateRequest> requests) {
        for (ContextMemoryCandidateCreateRequest request : requests) {
            validateCreateRequest(request);
            MemoryLane memoryLane = parseMemoryLaneOrDefault(request.getMemoryLane(), MemoryLane.RUNTIME_USER);
            String resolvedUserId = resolveUserId(
                    request.getGlobalUserId(), request.getExternalUserId(), request.getUserId());
            if (memoryLane == MemoryLane.RUNTIME_USER && !StringUtils.hasText(resolvedUserId)) {
                throw new IllegalArgumentException("userId is required for memory candidate");
            }
            if (memoryLane == MemoryLane.PROJECT_DEV) {
                requireProjectDevScope(request.getProjectCode(), request.getProjectId());
            }
        }
    }

    private ContextMemoryCandidateEntity findPendingDuplicate(ContextMemoryCandidateCreateRequest request,
                                                              String resolvedUserId,
                                                              ContextMemoryCandidateType candidateType,
                                                              MemoryLane memoryLane) {
        String normalizedContent = normalizeCandidateContent(request.getContent());
        if (!StringUtils.hasText(normalizedContent)) {
            return null;
        }
        var wrapper = Wrappers.lambdaQuery(ContextMemoryCandidateEntity.class)
                .eq(ContextMemoryCandidateEntity::getTenantId, request.getTenantId().trim())
                .eq(ContextMemoryCandidateEntity::getMemoryLane, memoryLane.name())
                .eq(ContextMemoryCandidateEntity::getStatus, ContextMemoryCandidateStatus.PENDING.name())
                .eq(ContextMemoryCandidateEntity::getCandidateType, candidateType.name())
                .eq(StringUtils.hasText(request.getProjectCode()),
                        ContextMemoryCandidateEntity::getProjectCode, request.getProjectCode())
                .isNull(!StringUtils.hasText(request.getProjectCode()),
                        ContextMemoryCandidateEntity::getProjectCode)
                .eq(request.getProjectId() != null, ContextMemoryCandidateEntity::getProjectId, request.getProjectId())
                .isNull(request.getProjectId() == null, ContextMemoryCandidateEntity::getProjectId)
                .and(w -> w.isNull(ContextMemoryCandidateEntity::getExpiresAt)
                        .or()
                        .gt(ContextMemoryCandidateEntity::getExpiresAt, LocalDateTime.now()))
                .orderByDesc(ContextMemoryCandidateEntity::getCreatedAt)
                .last("LIMIT 20");
        if (memoryLane == MemoryLane.RUNTIME_USER) {
            wrapper.eq(ContextMemoryCandidateEntity::getUserId, resolvedUserId);
        }
        List<ContextMemoryCandidateEntity> existing = candidateMapper.selectList(wrapper);
        if (existing == null || existing.isEmpty()) {
            return null;
        }
        for (ContextMemoryCandidateEntity entity : existing) {
            if (normalizedContent.equals(normalizeCandidateContent(entity.getContent()))) {
                return entity;
            }
        }
        return null;
    }

    public List<ContextMemoryCandidateResponse> listCandidates(ContextMemoryCandidateQueryRequest query) {
        validateQueryScope(query);
        MemoryLane memoryLane = parseMemoryLaneOrDefault(query.getMemoryLane(), MemoryLane.RUNTIME_USER);
        String resolvedUserId = resolveUserId(null, null, query.getUserId());
        int limit = query.getLimit() == null || query.getLimit() <= 0 ? 50 : Math.min(query.getLimit(), 200);
        boolean includeExpired = Boolean.TRUE.equals(query.getIncludeExpired());
        String status = StringUtils.hasText(query.getStatus())
                ? query.getStatus().trim().toUpperCase()
                : ContextMemoryCandidateStatus.PENDING.name();
        String traceId = StringUtils.hasText(query.getTraceId()) ? query.getTraceId().trim() : null;
        String candidateType = normalizeCandidateTypeFilter(query.getCandidateType());
        String sourceType = normalizeSourceTypeFilter(query.getSourceType());
        String pageInstanceId = StringUtils.hasText(query.getPageInstanceId()) ? query.getPageInstanceId().trim() : null;
        String origin = StringUtils.hasText(query.getOrigin()) ? query.getOrigin().trim() : null;

        var wrapper = Wrappers.lambdaQuery(ContextMemoryCandidateEntity.class)
                .eq(ContextMemoryCandidateEntity::getTenantId, query.getTenantId().trim())
                .eq(ContextMemoryCandidateEntity::getMemoryLane, memoryLane.name())
                .eq(ContextMemoryCandidateEntity::getStatus, status)
                .ne(ContextMemoryCandidateEntity::getStatus, ContextMemoryCandidateStatus.DELETED.name())
                .eq(StringUtils.hasText(query.getProjectCode()),
                        ContextMemoryCandidateEntity::getProjectCode, query.getProjectCode())
                .eq(query.getProjectId() != null, ContextMemoryCandidateEntity::getProjectId, query.getProjectId())
                .eq(StringUtils.hasText(traceId), ContextMemoryCandidateEntity::getTraceId, traceId)
                .eq(query.getNamespaceId() != null, ContextMemoryCandidateEntity::getNamespaceId, query.getNamespaceId())
                .eq(StringUtils.hasText(candidateType), ContextMemoryCandidateEntity::getCandidateType, candidateType)
                .eq(StringUtils.hasText(sourceType), ContextMemoryCandidateEntity::getSourceType, sourceType)
                .eq(StringUtils.hasText(pageInstanceId), ContextMemoryCandidateEntity::getPageInstanceId, pageInstanceId)
                .eq(StringUtils.hasText(origin), ContextMemoryCandidateEntity::getOrigin, origin)
                .orderByDesc(ContextMemoryCandidateEntity::getCreatedAt)
                .last("LIMIT " + limit);
        if (memoryLane == MemoryLane.RUNTIME_USER) {
            wrapper.eq(ContextMemoryCandidateEntity::getUserId, resolvedUserId);
        }
        if (!includeExpired && ContextMemoryCandidateStatus.PENDING.name().equals(status)) {
            wrapper.and(w -> w.isNull(ContextMemoryCandidateEntity::getExpiresAt)
                    .or()
                    .gt(ContextMemoryCandidateEntity::getExpiresAt, LocalDateTime.now()));
        }
        return candidateMapper.selectList(wrapper).stream()
                .map(ContextMemoryCandidateViewMapper::toResponse)
                .collect(Collectors.toList());
    }

    public ContextMemoryCandidateResponse getCandidate(Long id, ContextMemoryCandidateQueryRequest query) {
        ContextMemoryCandidateEntity entity = requireAccessibleCandidate(id, query);
        return ContextMemoryCandidateViewMapper.toResponse(entity);
    }

    @Transactional
    public ContextMemoryCandidateResponse updateCandidate(Long id, ContextMemoryCandidateUpdateRequest request) {
        ContextMemoryCandidateEntity entity = requireEditableCandidate(id, request);
        applyCandidateUpdates(entity, request);
        entity.setUpdatedAt(LocalDateTime.now());
        candidateMapper.updateById(entity);

        auditCandidate(ContextAuditEventType.CANDIDATE_UPDATE, ContextAuditDecision.ALLOW,
                StringUtils.hasText(request.getUpdateReason()) ? request.getUpdateReason() : "candidate updated",
                entity, null, null, firstText(request.getUpdatedBy(), entity.getUserId(), entity.getProposedBy()));
        return ContextMemoryCandidateViewMapper.toResponse(entity);
    }

    @Transactional
    public ContextMemoryCandidateResponse approveCandidate(Long id, ContextMemoryCandidateReviewRequest request) {
        ContextMemoryCandidateEntity entity = requirePendingCandidate(id, request);
        MemoryLane memoryLane = parseMemoryLaneOrDefault(entity.getMemoryLane(), MemoryLane.RUNTIME_USER);
        String resolvedUserId = memoryLane == MemoryLane.PROJECT_DEV
                ? null
                : resolveUserId(entity.getGlobalUserId(), entity.getExternalUserId(), entity.getUserId());
        String reviewer = firstText(request.getReviewedBy(), resolvedUserId, entity.getProposedBy());

        validateProjectDevCandidateTarget(entity, memoryLane);
        Long namespaceId = resolveNamespaceForApprove(entity, resolvedUserId, reviewer);
        ContextItemCreateRequest createRequest = buildItemCreateRequest(entity, namespaceId, resolvedUserId, reviewer, request);
        ContextItemResponse createdItem = itemService.createItem(createRequest);

        entity.setStatus(ContextMemoryCandidateStatus.APPROVED.name());
        entity.setApprovedItemId(createdItem.getId());
        entity.setReviewedBy(reviewer);
        entity.setReviewedAt(LocalDateTime.now());
        entity.setReviewReason(request.getReviewReason());
        entity.setUpdatedAt(LocalDateTime.now());
        candidateMapper.updateById(entity);

        auditCandidate(ContextAuditEventType.CANDIDATE_APPROVE, ContextAuditDecision.ALLOW,
                "candidate approved", entity, createdItem.getId(), namespaceId, reviewer);
        return ContextMemoryCandidateViewMapper.toResponse(entity);
    }

    @Transactional
    public List<ContextMemoryCandidateResponse> approveCandidates(ContextMemoryCandidateBatchReviewRequest request) {
        validateBatchReviewRequest(request);
        prevalidateApproveCandidates(request);
        return request.getCandidateIds().stream()
                .map(id -> approveCandidate(id, request))
                .toList();
    }

    @Transactional
    public ContextMemoryCandidateResponse rejectCandidate(Long id, ContextMemoryCandidateReviewRequest request) {
        ContextMemoryCandidateEntity entity = requirePendingCandidate(id, request);
        String reviewer = StringUtils.hasText(request.getReviewedBy())
                ? request.getReviewedBy()
                : resolveUserId(entity.getGlobalUserId(), entity.getExternalUserId(), entity.getUserId());
        entity.setStatus(ContextMemoryCandidateStatus.REJECTED.name());
        entity.setReviewedBy(reviewer);
        entity.setReviewedAt(LocalDateTime.now());
        entity.setReviewReason(request.getReviewReason());
        entity.setUpdatedAt(LocalDateTime.now());
        candidateMapper.updateById(entity);

        auditCandidate(ContextAuditEventType.CANDIDATE_REJECT, ContextAuditDecision.ALLOW,
                "candidate rejected", entity, null, null, reviewer);
        return ContextMemoryCandidateViewMapper.toResponse(entity);
    }

    @Transactional
    public List<ContextMemoryCandidateResponse> rejectCandidates(ContextMemoryCandidateBatchReviewRequest request) {
        validateBatchReviewRequest(request);
        prevalidateRejectCandidates(request);
        return request.getCandidateIds().stream()
                .map(id -> rejectCandidate(id, request))
                .toList();
    }

    @Transactional
    public ContextMemoryCandidateResponse deleteCandidate(Long id, ContextMemoryCandidateQueryRequest query) {
        ContextMemoryCandidateEntity entity = requireAccessibleCandidate(id, query);
        entity.setStatus(ContextMemoryCandidateStatus.DELETED.name());
        entity.setDeletedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        candidateMapper.updateById(entity);

        auditCandidate(ContextAuditEventType.CANDIDATE_DELETE, ContextAuditDecision.ALLOW,
                "candidate deleted", entity, null, null, query.getUserId());
        return ContextMemoryCandidateViewMapper.toResponse(entity);
    }

    @Transactional
    public int markExpiredCandidates(String tenantId) {
        return markExpiredCandidates(tenantId, false, 5000);
    }

    @Transactional
    public int markExpiredCandidates(String tenantId, boolean dryRun, int limit) {
        int effectiveLimit = limit <= 0 ? 500 : Math.min(limit, 5000);
        List<ContextMemoryCandidateEntity> expired = candidateMapper.selectList(
                Wrappers.lambdaQuery(ContextMemoryCandidateEntity.class)
                        .eq(StringUtils.hasText(tenantId), ContextMemoryCandidateEntity::getTenantId, tenantId)
                        .eq(ContextMemoryCandidateEntity::getStatus, ContextMemoryCandidateStatus.PENDING.name())
                        .isNotNull(ContextMemoryCandidateEntity::getExpiresAt)
                        .le(ContextMemoryCandidateEntity::getExpiresAt, LocalDateTime.now())
                        .last("LIMIT " + effectiveLimit));
        if (dryRun) {
            return expired.size();
        }
        for (ContextMemoryCandidateEntity entity : expired) {
            entity.setStatus(ContextMemoryCandidateStatus.EXPIRED.name());
            entity.setUpdatedAt(LocalDateTime.now());
            candidateMapper.updateById(entity);
            auditCandidate(ContextAuditEventType.CANDIDATE_EXPIRE, ContextAuditDecision.ALLOW,
                    "candidate expired", entity, null, null, entity.getUserId());
        }
        return expired.size();
    }

    ContextMemoryCandidateResponse createRuntimeUserCandidateFromInteraction(ContextMemoryCandidateCreateRequest request) {
        return createCandidate(request);
    }

    private ContextMemoryCandidateEntity requireEditableCandidate(Long id, ContextMemoryCandidateUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        ContextMemoryCandidateQueryRequest scope = new ContextMemoryCandidateQueryRequest();
        scope.setTenantId(request.getTenantId());
        scope.setUserId(request.getUserId());
        scope.setProjectCode(request.getProjectCode());
        scope.setProjectId(request.getProjectId());
        scope.setMemoryLane(request.getMemoryLane());
        ContextMemoryCandidateEntity entity = requireAccessibleCandidate(id, scope);
        if (!ContextMemoryCandidateStatus.PENDING.name().equals(entity.getStatus())) {
            throw new IllegalArgumentException("Only PENDING candidates can be edited");
        }
        if (entity.getExpiresAt() != null && !entity.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Candidate has expired");
        }
        return entity;
    }

    private void applyCandidateUpdates(ContextMemoryCandidateEntity entity, ContextMemoryCandidateUpdateRequest request) {
        if (StringUtils.hasText(request.getCandidateType())) {
            entity.setCandidateType(parseCandidateType(request.getCandidateType()).name());
        }
        if (request.getTitle() != null) {
            entity.setTitle(trimToNull(request.getTitle()));
        }
        if (request.getContent() != null) {
            if (!StringUtils.hasText(request.getContent())) {
                throw new IllegalArgumentException("content is required");
            }
            entity.setContent(request.getContent().trim());
        }
        if (request.getSummary() != null) {
            entity.setSummary(trimToNull(request.getSummary()));
        }
        if (request.getReason() != null) {
            entity.setReason(trimToNull(request.getReason()));
        }
        if (StringUtils.hasText(request.getSourceType())) {
            entity.setSourceType(parseSourceType(request.getSourceType()).name());
        }
        if (request.getSourceRef() != null) {
            entity.setSourceRef(trimToNull(request.getSourceRef()));
        }
        if (request.getNamespaceId() != null) {
            entity.setNamespaceId(request.getNamespaceId());
        }
        if (request.getNamespaceKey() != null) {
            entity.setNamespaceKey(trimToNull(request.getNamespaceKey()));
        }
        if (request.getWorkflowId() != null) {
            entity.setWorkflowId(trimToNull(request.getWorkflowId()));
        }
        if (request.getWorkflowKey() != null) {
            entity.setWorkflowKey(trimToNull(request.getWorkflowKey()));
        }
        if (request.getPageInstanceId() != null) {
            entity.setPageInstanceId(trimToNull(request.getPageInstanceId()));
        }
        if (request.getOrigin() != null) {
            entity.setOrigin(trimToNull(request.getOrigin()));
        }
        if (request.getConfidence() != null) {
            entity.setConfidence(request.getConfidence());
        }
        if (StringUtils.hasText(request.getTrustLevel())) {
            entity.setTrustLevel(defaultTrust(request.getTrustLevel()));
        }
        if (StringUtils.hasText(request.getVisibility())) {
            MemoryLane memoryLane = parseMemoryLaneOrDefault(entity.getMemoryLane(), MemoryLane.RUNTIME_USER);
            entity.setVisibility(defaultVisibility(request.getVisibility(), memoryLane));
        }
        if (request.getExpiresAt() != null) {
            entity.setExpiresAt(request.getExpiresAt());
        }
        if (request.getMetadataJson() != null) {
            entity.setMetadataJson(trimToNull(request.getMetadataJson()));
        }
    }

    private ContextMemoryCandidateEntity requirePendingCandidate(Long id, ContextMemoryCandidateReviewRequest request) {
        ContextMemoryCandidateQueryRequest scope = new ContextMemoryCandidateQueryRequest();
        scope.setTenantId(request.getTenantId());
        scope.setUserId(request.getUserId());
        scope.setProjectCode(request.getProjectCode());
        scope.setProjectId(request.getProjectId());
        scope.setMemoryLane(request.getMemoryLane());
        ContextMemoryCandidateEntity entity = requireAccessibleCandidate(id, scope);
        if (!ContextMemoryCandidateStatus.PENDING.name().equals(entity.getStatus())) {
            throw new IllegalArgumentException("Only PENDING candidates can be reviewed");
        }
        if (entity.getExpiresAt() != null && !entity.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Candidate has expired");
        }
        return entity;
    }

    private void validateBatchReviewRequest(ContextMemoryCandidateBatchReviewRequest request) {
        if (request == null || request.getCandidateIds() == null || request.getCandidateIds().isEmpty()) {
            throw new IllegalArgumentException("candidateIds is required");
        }
        if (request.getCandidateIds().stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("candidateIds must be positive");
        }
        if (request.getCandidateIds().stream().distinct().count() != request.getCandidateIds().size()) {
            throw new IllegalArgumentException("candidateIds must be unique");
        }
    }

    private void prevalidateApproveCandidates(ContextMemoryCandidateBatchReviewRequest request) {
        for (Long id : request.getCandidateIds()) {
            ContextMemoryCandidateEntity entity = requirePendingCandidate(id, request);
            MemoryLane memoryLane = parseMemoryLaneOrDefault(entity.getMemoryLane(), MemoryLane.RUNTIME_USER);
            validateProjectDevCandidateTarget(entity, memoryLane);
        }
    }

    private void prevalidateRejectCandidates(ContextMemoryCandidateBatchReviewRequest request) {
        for (Long id : request.getCandidateIds()) {
            requirePendingCandidate(id, request);
        }
    }

    private ContextMemoryCandidateEntity requireAccessibleCandidate(Long id, ContextMemoryCandidateQueryRequest query) {
        validateQueryScope(query);
        ContextMemoryCandidateEntity entity = candidateMapper.selectById(id);
        if (entity == null || ContextMemoryCandidateStatus.DELETED.name().equals(entity.getStatus())) {
            throw new IllegalArgumentException("Memory candidate not found: " + id);
        }
        assertCandidateOwnership(entity, query);
        return entity;
    }

    private void assertCandidateOwnership(ContextMemoryCandidateEntity entity, ContextMemoryCandidateQueryRequest query) {
        if (!entity.getTenantId().equals(query.getTenantId().trim())) {
            throw new IllegalArgumentException("Memory candidate access denied");
        }
        MemoryLane requestedLane = parseMemoryLaneOrDefault(query.getMemoryLane(), MemoryLane.RUNTIME_USER);
        MemoryLane entityLane = parseMemoryLaneOrDefault(entity.getMemoryLane(), MemoryLane.RUNTIME_USER);
        if (requestedLane != entityLane) {
            throw new IllegalArgumentException("Memory candidate access denied");
        }
        if (entityLane == MemoryLane.RUNTIME_USER) {
            String resolvedUserId = resolveUserId(null, null, query.getUserId());
            if (!entity.getUserId().equals(resolvedUserId)) {
                throw new IllegalArgumentException("Memory candidate access denied");
            }
            return;
        }
        if (!ContextProjectIdentity.matches(
                query.getProjectCode(), query.getProjectId(),
                entity.getProjectCode(), entity.getProjectId())) {
            throw new IllegalArgumentException("Memory candidate access denied");
        }
    }

    private Long resolveNamespaceForApprove(ContextMemoryCandidateEntity candidate,
                                            String resolvedUserId,
                                            String reviewer) {
        MemoryLane memoryLane = parseMemoryLaneOrDefault(candidate.getMemoryLane(), MemoryLane.RUNTIME_USER);
        if (candidate.getNamespaceId() != null) {
            ContextNamespaceEntity namespace = namespaceService.requireActiveNamespace(candidate.getNamespaceId());
            validateNamespaceForCandidate(namespace, candidate, resolvedUserId, memoryLane);
            return namespace.getId();
        }
        if (StringUtils.hasText(candidate.getNamespaceKey())) {
            ContextNamespaceEntity namespace = namespaceService.resolveNamespaceByKey(candidate.getNamespaceKey());
            validateNamespaceForCandidate(namespace, candidate, resolvedUserId, memoryLane);
            return namespace.getId();
        }
        if (memoryLane == MemoryLane.PROJECT_DEV) {
            ContextNamespaceRequest request = new ContextNamespaceRequest();
            ContextNamespaceTarget target = resolveProjectDevNamespaceTarget(candidate);
            request.setNamespaceType(target.namespaceType().name());
            request.setTenantId(candidate.getTenantId());
            request.setProjectId(candidate.getProjectId());
            request.setProjectCode(candidate.getProjectCode());
            request.setOwnerType(target.ownerType());
            request.setOwnerId(target.ownerId());
            request.setDisplayName(target.displayName());
            request.setCreatedBy(reviewer);
            return namespaceService.createOrGetNamespace(request).getId();
        }
        ContextNamespaceRequest request = new ContextNamespaceRequest();
        request.setNamespaceType(ContextNamespaceType.USER.name());
        request.setTenantId(candidate.getTenantId());
        request.setProjectId(candidate.getProjectId());
        request.setProjectCode(candidate.getProjectCode());
        request.setOwnerType("USER");
        request.setOwnerId(resolvedUserId);
        request.setDisplayName("Runtime User Memory: " + resolvedUserId);
        request.setCreatedBy(reviewer);
        return namespaceService.createOrGetNamespace(request).getId();
    }

    private void validateNamespaceForCandidate(ContextNamespaceEntity namespace,
                                               ContextMemoryCandidateEntity candidate,
                                               String resolvedUserId,
                                               MemoryLane memoryLane) {
        if (!candidate.getTenantId().equals(namespace.getTenantId())) {
            throw new IllegalArgumentException("Namespace tenant mismatch for candidate approve");
        }
        ContextProjectIdentity.requireMatch(
                candidate.getProjectCode(), candidate.getProjectId(),
                namespace.getProjectCode(), namespace.getProjectId(),
                "Namespace project mismatch for candidate approve");
        if (memoryLane == MemoryLane.PROJECT_DEV) {
            ContextNamespaceType type = ContextNamespaceType.valueOf(namespace.getNamespaceType().trim().toUpperCase());
            if (type == ContextNamespaceType.USER || type == ContextNamespaceType.SESSION || type == ContextNamespaceType.PERSONAL) {
                throw new IllegalArgumentException("PROJECT_DEV candidate approve requires project-scoped namespace");
            }
            return;
        }
        if (!ContextNamespaceType.USER.name().equalsIgnoreCase(namespace.getNamespaceType())) {
            throw new IllegalArgumentException("Only USER namespace is allowed for runtime user candidate approve");
        }
        if (!StringUtils.hasText(namespace.getOwnerType())
                || !"USER".equalsIgnoreCase(namespace.getOwnerType().trim())) {
            throw new IllegalArgumentException("Namespace ownerType must be USER for runtime user candidate approve");
        }
        if (!StringUtils.hasText(namespace.getOwnerId())) {
            throw new IllegalArgumentException("Namespace ownerId is required for runtime user candidate approve");
        }
        if (!namespace.getOwnerId().trim().equals(resolvedUserId)) {
            throw new IllegalArgumentException("Namespace owner mismatch for candidate approve");
        }
    }

    private void validateProjectDevCandidateTarget(ContextMemoryCandidateEntity candidate, MemoryLane memoryLane) {
        if (memoryLane != MemoryLane.PROJECT_DEV) {
            return;
        }
        ContextMemoryCandidateType type = parseCandidateType(candidate.getCandidateType());
        if (type == ContextMemoryCandidateType.WORKFLOW_CONTEXT
                && !StringUtils.hasText(firstText(candidate.getWorkflowId(), candidate.getWorkflowKey()))) {
            throw new IllegalArgumentException("WORKFLOW_CONTEXT candidate requires workflowId or workflowKey");
        }
        if (type == ContextMemoryCandidateType.PAGE_CONTEXT
                && !StringUtils.hasText(candidate.getPageInstanceId())) {
            throw new IllegalArgumentException("PAGE_CONTEXT candidate requires pageInstanceId");
        }
        if (type == ContextMemoryCandidateType.API_CONTEXT
                && !StringUtils.hasText(candidate.getSourceRef())) {
            throw new IllegalArgumentException("API_CONTEXT candidate requires sourceRef");
        }
    }

    private ContextItemCreateRequest buildItemCreateRequest(ContextMemoryCandidateEntity candidate,
                                                            Long namespaceId,
                                                            String resolvedUserId,
                                                            String reviewer,
                                                            ContextMemoryCandidateReviewRequest review) {
        MemoryLane memoryLane = parseMemoryLaneOrDefault(candidate.getMemoryLane(), MemoryLane.RUNTIME_USER);
        ContextItemCreateRequest request = new ContextItemCreateRequest();
        request.setNamespaceId(namespaceId);
        request.setItemType(mapCandidateTypeToItemType(candidate.getCandidateType()).name());
        request.setMemoryLane(memoryLane.name());
        request.setVisibility(memoryLane == MemoryLane.PROJECT_DEV
                ? defaultVisibility(candidate.getVisibility(), memoryLane)
                : ContextVisibility.PRIVATE.name());
        request.setTitle(candidate.getTitle());
        request.setContent(candidate.getContent());
        request.setSummary(candidate.getSummary());
        request.setSourceType(memoryLane == MemoryLane.PROJECT_DEV
                ? parseSourceType(candidate.getSourceType()).name()
                : ContextSourceType.USER_CONFIRMED.name());
        request.setSourceRef(StringUtils.hasText(candidate.getSourceRef())
                ? candidate.getSourceRef()
                : candidate.getCandidateKey());
        request.setConfidence(review.getConfidence() != null ? review.getConfidence() : candidate.getConfidence());
        request.setTrustLevel(StringUtils.hasText(review.getTrustLevel())
                ? review.getTrustLevel()
                : "MEDIUM");
        request.setTenantId(candidate.getTenantId());
        request.setProjectId(candidate.getProjectId());
        request.setProjectCode(candidate.getProjectCode());
        request.setUserId(resolvedUserId);
        request.setSessionId(candidate.getSessionId());
        request.setAgentId(firstText(candidate.getAgentId(), candidate.getAgentKey()));
        request.setWorkflowId(firstText(candidate.getWorkflowId(), candidate.getWorkflowKey()));
        request.setPageInstanceId(candidate.getPageInstanceId());
        request.setCreatedBy(reviewer);
        if (memoryLane == MemoryLane.PROJECT_DEV) {
            request.setBindings(buildProjectDevBindings(candidate));
        }

        ContextEvidenceRequest evidence = new ContextEvidenceRequest();
        evidence.setEvidenceType(memoryLane == MemoryLane.PROJECT_DEV
                ? evidenceTypeForProjectDevCandidate(candidate)
                : "USER_CONFIRMATION");
        evidence.setEvidenceRef(StringUtils.hasText(candidate.getSourceRef())
                ? candidate.getSourceRef()
                : candidate.getCandidateKey());
        evidence.setTraceId(candidate.getTraceId());
        evidence.setEvidenceExcerpt(truncate(candidate.getContent(), 500));
        request.setEvidence(List.of(evidence));
        return request;
    }

    private ContextItemType mapCandidateTypeToItemType(String candidateType) {
        ContextMemoryCandidateType type = parseCandidateType(candidateType);
        return switch (type) {
            case PREFERENCE -> ContextItemType.PREFERENCE;
            case PAGE_CONTEXT -> ContextItemType.PAGE_CONTEXT;
            case WORKFLOW_CONTEXT -> ContextItemType.WORKFLOW_CONTEXT;
            case API_CONTEXT -> ContextItemType.API_CONTRACT;
            case RULE -> ContextItemType.RULE;
            case FACT -> ContextItemType.FACT;
            case NOTE -> ContextItemType.NOTE;
        };
    }

    private ContextNamespaceTarget resolveProjectDevNamespaceTarget(ContextMemoryCandidateEntity candidate) {
        ContextMemoryCandidateType type = parseCandidateType(candidate.getCandidateType());
        String workflowTarget = firstText(candidate.getWorkflowId(), candidate.getWorkflowKey());
        if (type == ContextMemoryCandidateType.WORKFLOW_CONTEXT && StringUtils.hasText(workflowTarget)) {
            return new ContextNamespaceTarget(
                    ContextNamespaceType.WORKFLOW,
                    ContextBindType.WORKFLOW.name(),
                    workflowTarget,
                    "Workflow Context: " + workflowTarget);
        }
        if (type == ContextMemoryCandidateType.PAGE_CONTEXT && StringUtils.hasText(candidate.getPageInstanceId())) {
            return new ContextNamespaceTarget(
                    ContextNamespaceType.PAGE,
                    ContextBindType.PAGE.name(),
                    candidate.getPageInstanceId(),
                    "Page Context: " + candidate.getPageInstanceId());
        }
        if (type == ContextMemoryCandidateType.API_CONTEXT && StringUtils.hasText(candidate.getSourceRef())) {
            return new ContextNamespaceTarget(
                    ContextNamespaceType.API,
                    ContextBindType.API.name(),
                    candidate.getSourceRef(),
                    "API Context: " + candidate.getSourceRef());
        }
        String projectTarget = firstText(candidate.getProjectCode(),
                candidate.getProjectId() == null ? null : String.valueOf(candidate.getProjectId()));
        return new ContextNamespaceTarget(
                ContextNamespaceType.PROJECT,
                ContextBindType.PROJECT.name(),
                projectTarget,
                "Project Context: " + projectTarget);
    }

    private List<ContextBindingRequest> buildProjectDevBindings(ContextMemoryCandidateEntity candidate) {
        List<ContextBindingRequest> bindings = new ArrayList<>();
        ContextMemoryCandidateType type = parseCandidateType(candidate.getCandidateType());
        if (type == ContextMemoryCandidateType.WORKFLOW_CONTEXT) {
            addBinding(bindings, candidate, ContextBindType.WORKFLOW,
                    firstText(candidate.getWorkflowId(), candidate.getWorkflowKey()), candidate.getWorkflowKey());
        } else if (type == ContextMemoryCandidateType.PAGE_CONTEXT) {
            addBinding(bindings, candidate, ContextBindType.PAGE, candidate.getPageInstanceId(), null);
        } else if (type == ContextMemoryCandidateType.API_CONTEXT) {
            addBinding(bindings, candidate, ContextBindType.API, candidate.getSourceRef(), candidate.getSourceRef());
        } else {
            addBinding(bindings, candidate, ContextBindType.PROJECT,
                    firstText(candidate.getProjectCode(),
                            candidate.getProjectId() == null ? null : String.valueOf(candidate.getProjectId())),
                    candidate.getProjectCode());
        }
        return bindings.isEmpty() ? null : bindings;
    }

    private void addBinding(List<ContextBindingRequest> bindings,
                            ContextMemoryCandidateEntity candidate,
                            ContextBindType bindType,
                            String bindId,
                            String bindKey) {
        if (!StringUtils.hasText(bindId)) {
            return;
        }
        ContextBindingRequest binding = new ContextBindingRequest();
        binding.setBindType(bindType.name());
        binding.setBindId(bindId);
        binding.setBindKey(bindKey);
        binding.setTenantId(candidate.getTenantId());
        binding.setProjectId(candidate.getProjectId());
        binding.setProjectCode(candidate.getProjectCode());
        bindings.add(binding);
    }

    private String evidenceTypeForProjectDevCandidate(ContextMemoryCandidateEntity candidate) {
        return switch (parseSourceType(candidate.getSourceType())) {
            case CODE -> "SOURCE_FILE";
            case SQL -> "SQL_SCHEMA";
            case API -> "API_RESPONSE";
            case TRACE -> "TRACE_SPAN";
            case DOC -> "DOCUMENT";
            case AGENT_OUTPUT -> "TOOL_CALL";
            case MANUAL, USER_CONFIRMED -> "USER_CONFIRMATION";
            default -> "MANUAL_NOTE";
        };
    }

    private record ContextNamespaceTarget(
            ContextNamespaceType namespaceType,
            String ownerType,
            String ownerId,
            String displayName) {
    }

    private void validateCreateRequest(ContextMemoryCandidateCreateRequest request) {
        if (request == null || !StringUtils.hasText(request.getContent())) {
            throw new IllegalArgumentException("content is required");
        }
        if (!StringUtils.hasText(request.getTenantId())) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (!StringUtils.hasText(request.getSourceType())) {
            throw new IllegalArgumentException("sourceType is required");
        }
    }

    private void validateQueryScope(ContextMemoryCandidateQueryRequest query) {
        if (query == null || !StringUtils.hasText(query.getTenantId())) {
            throw new IllegalArgumentException("tenantId is required");
        }
        MemoryLane memoryLane = parseMemoryLaneOrDefault(query.getMemoryLane(), MemoryLane.RUNTIME_USER);
        if (memoryLane == MemoryLane.RUNTIME_USER && !StringUtils.hasText(query.getUserId())) {
            throw new IllegalArgumentException("userId is required");
        }
        if (memoryLane == MemoryLane.PROJECT_DEV) {
            requireProjectDevScope(query.getProjectCode(), query.getProjectId());
        }
    }

    private ContextMemoryCandidateType parseCandidateType(String candidateType) {
        if (!StringUtils.hasText(candidateType)) {
            return ContextMemoryCandidateType.PREFERENCE;
        }
        return ContextMemoryCandidateType.valueOf(candidateType.trim().toUpperCase());
    }

    private String normalizeCandidateTypeFilter(String candidateType) {
        return StringUtils.hasText(candidateType) ? parseCandidateType(candidateType).name() : null;
    }

    private ContextSourceType parseSourceType(String sourceType) {
        return ContextSourceType.valueOf(sourceType.trim().toUpperCase());
    }

    private String normalizeSourceTypeFilter(String sourceType) {
        return StringUtils.hasText(sourceType) ? parseSourceType(sourceType).name() : null;
    }

    private MemoryLane parseMemoryLaneOrDefault(String memoryLane, MemoryLane defaultLane) {
        if (!StringUtils.hasText(memoryLane)) {
            return defaultLane;
        }
        return MemoryLane.valueOf(memoryLane.trim().toUpperCase());
    }

    private void requireProjectDevScope(String projectCode, Long projectId) {
        if (!StringUtils.hasText(projectCode) && projectId == null) {
            throw new IllegalArgumentException("projectCode or projectId is required for PROJECT_DEV memory candidate");
        }
    }

    public String resolveUserId(String globalUserId, String externalUserId, String userId) {
        return firstText(globalUserId, externalUserId, userId);
    }

    private String normalizeCandidateContent(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        return content.trim().replaceAll("\\s+", " ");
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private BigDecimal defaultConfidence(BigDecimal confidence) {
        return confidence == null ? new BigDecimal("0.7000") : confidence;
    }

    private String defaultTrust(String trustLevel) {
        return StringUtils.hasText(trustLevel) ? trustLevel.trim().toUpperCase() : "LOW";
    }

    private String defaultVisibility(String visibility, MemoryLane memoryLane) {
        if (memoryLane == MemoryLane.PROJECT_DEV) {
            return ContextVisibility.PROJECT.name();
        }
        if (StringUtils.hasText(visibility)) {
            return visibility.trim().toUpperCase();
        }
        return ContextVisibility.PRIVATE.name();
    }

    private void auditCandidate(ContextAuditEventType eventType,
                                ContextAuditDecision decision,
                                String reason,
                                ContextMemoryCandidateEntity entity,
                                Long itemId,
                                Long namespaceId,
                                String actorId) {
        ContextQueryRequest scope = new ContextQueryRequest();
        scope.setTenantId(entity.getTenantId());
        scope.setProjectCode(entity.getProjectCode());
        scope.setProjectId(entity.getProjectId());
        scope.setUserId(entity.getUserId());
        scope.setSessionId(entity.getSessionId());
        scope.setTraceId(entity.getTraceId());
        scope.setAgentId(entity.getAgentId());
        scope.setWorkflowId(entity.getWorkflowId());
        scope.setPageInstanceId(entity.getPageInstanceId());
        MemoryLane memoryLane = parseMemoryLaneOrDefault(entity.getMemoryLane(), MemoryLane.RUNTIME_USER);
        scope.setMemoryLane(memoryLane.name());
        String effectiveActorId = firstText(actorId, entity.getProposedBy(), entity.getUserId(), "context-candidate");
        scope.setActorType(resolveCandidateAuditActorType(eventType, effectiveActorId));
        scope.setActorId(effectiveActorId);
        auditService.recordWithMetadata(eventType, decision, reason, scope, itemId, namespaceId,
                buildCandidateAuditMetadata(entity, itemId, reason));
    }

    private String resolveCandidateAuditActorType(ContextAuditEventType eventType, String actorId) {
        if (eventType == ContextAuditEventType.CANDIDATE_CREATE && isAiCodingActor(actorId)) {
            return "AI_TOOL";
        }
        return "USER";
    }

    private boolean isAiCodingActor(String actorId) {
        return StringUtils.hasText(actorId) && actorId.trim().startsWith("aiCodingKey:");
    }

    private String buildCandidateAuditMetadata(ContextMemoryCandidateEntity entity, Long itemId, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("candidateId", entity.getId());
        metadata.put("candidateKey", entity.getCandidateKey());
        metadata.put("ownerUserId", entity.getUserId());
        if (itemId != null) {
            metadata.put("approvedItemId", itemId);
        }
        metadata.put("sourceType", entity.getSourceType());
        metadata.put("traceId", entity.getTraceId());
        metadata.put("sessionId", entity.getSessionId());
        metadata.put("userId", entity.getUserId());
        metadata.put("reason", reason);
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            return "{\"candidateId\":" + entity.getId() + "}";
        }
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}

package com.enterprise.ai.agent.platform.control.context;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContextItemService {

    private final ContextItemMapper itemMapper;
    private final ContextNamespaceService namespaceService;
    private final ContextBindingService bindingService;
    private final ContextEvidenceService evidenceService;
    private final ContextAccessPolicyService accessPolicyService;
    private final ContextAuditService auditService;
    private final ContextRetrievalService retrievalService;

    @Transactional
    public ContextItemResponse createItem(ContextItemCreateRequest request) {
        validateCreateRequest(request);
        accessPolicyService.validateWriteScope(
                request.getTenantId(), request.getProjectCode(), request.getProjectId(), request.getMemoryLane());

        ContextNamespaceEntity namespace = namespaceService.resolveNamespace(request);
        String visibility = defaultVisibility(request.getVisibility());
        accessPolicyService.validateCreateItemPolicy(request, namespace, visibility);

        ContextItemEntity entity = new ContextItemEntity();
        entity.setItemKey(ContextKeyFactory.newItemKey());
        entity.setNamespaceId(namespace.getId());
        entity.setItemType(request.getItemType().trim().toUpperCase());
        entity.setMemoryLane(request.getMemoryLane().trim().toUpperCase());
        entity.setTitle(request.getTitle());
        entity.setContent(request.getContent().trim());
        entity.setSummary(request.getSummary());
        entity.setMetadataJson(request.getMetadataJson());
        entity.setSourceType(request.getSourceType().trim().toUpperCase());
        entity.setSourceRef(request.getSourceRef());
        entity.setConfidence(defaultConfidence(request.getConfidence()));
        entity.setTrustLevel(defaultTrust(request.getTrustLevel()));
        entity.setVisibility(visibility);
        entity.setStatus(ContextStatus.ACTIVE.name());
        entity.setEffectiveFrom(request.getEffectiveFrom());
        entity.setExpiresAt(request.getExpiresAt());
        entity.setStaleAfter(request.getStaleAfter());
        entity.setCreatedBy(request.getCreatedBy());
        entity.setUpdatedBy(request.getCreatedBy());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        itemMapper.insert(entity);

        ContextQueryRequest scope = accessPolicyService.toCreateScope(request);
        List<ContextBindingRequest> initialBindings = resolveInitialBindings(request, scope);
        if (MemoryLane.RUNTIME_USER.name().equalsIgnoreCase(entity.getMemoryLane())
                && ContextVisibility.PRIVATE.name().equals(entity.getVisibility())
                && initialBindings.isEmpty()) {
            throw new IllegalArgumentException(
                    "RUNTIME_USER PRIVATE requires at least one identity binding on create");
        }
        for (ContextBindingRequest binding : initialBindings) {
            bindingService.bindItemOnCreate(entity, namespace, binding, scope);
        }
        if (request.getEvidence() != null) {
            for (ContextEvidenceRequest evidence : request.getEvidence()) {
                evidenceService.addEvidence(entity.getId(), evidence, scope);
            }
        }

        auditService.record(ContextAuditEventType.CREATE, ContextAuditDecision.ALLOW,
                "item created", scope, entity.getId(), namespace.getId());
        return ContextViewMapper.toItemResponse(entity);
    }

    @Transactional
    public ContextItemResponse updateItem(Long id, ContextItemUpdateRequest request, ContextQueryRequest scope) {
        accessPolicyService.requireItemWriteAccess(id, scope);
        ContextItemEntity entity = requireMutableItem(id);
        if (request != null) {
            if (StringUtils.hasText(request.getTitle())) {
                entity.setTitle(request.getTitle());
            }
            if (StringUtils.hasText(request.getContent())) {
                entity.setContent(request.getContent().trim());
            }
            if (request.getSummary() != null) {
                entity.setSummary(request.getSummary());
            }
            if (request.getMetadataJson() != null) {
                entity.setMetadataJson(request.getMetadataJson());
            }
            if (StringUtils.hasText(request.getSourceType())) {
                entity.setSourceType(request.getSourceType().trim().toUpperCase());
            }
            if (request.getSourceRef() != null) {
                entity.setSourceRef(request.getSourceRef());
            }
            if (request.getConfidence() != null) {
                entity.setConfidence(request.getConfidence());
            }
            if (StringUtils.hasText(request.getTrustLevel())) {
                entity.setTrustLevel(request.getTrustLevel().trim().toUpperCase());
            }
            if (StringUtils.hasText(request.getVisibility())) {
                entity.setVisibility(request.getVisibility().trim().toUpperCase());
            }
            if (request.getEffectiveFrom() != null) {
                entity.setEffectiveFrom(request.getEffectiveFrom());
            }
            if (request.getExpiresAt() != null) {
                entity.setExpiresAt(request.getExpiresAt());
            }
            if (request.getStaleAfter() != null) {
                entity.setStaleAfter(request.getStaleAfter());
            }
            if (StringUtils.hasText(request.getUpdatedBy())) {
                entity.setUpdatedBy(request.getUpdatedBy());
            }
        }
        entity.setUpdatedAt(LocalDateTime.now());
        itemMapper.updateById(entity);

        auditService.record(ContextAuditEventType.UPDATE, ContextAuditDecision.ALLOW,
                "item updated", scope, entity.getId(), entity.getNamespaceId());
        return ContextViewMapper.toItemResponse(entity);
    }

    public ContextItemResponse getItem(Long id, ContextQueryRequest scope) {
        accessPolicyService.requireItemReadAccess(id, scope);
        ContextItemEntity entity = itemMapper.selectById(id);
        auditService.record(ContextAuditEventType.READ, ContextAuditDecision.ALLOW,
                "item read", scope, entity.getId(), entity.getNamespaceId());
        return ContextViewMapper.toItemResponse(entity);
    }

    public List<ContextSearchResult> searchItems(ContextQueryRequest query) {
        return retrievalService.search(query);
    }

    public List<ContextItemResponse> listItems(ContextItemListRequest request) {
        if (request == null || !StringUtils.hasText(request.getTenantId())) {
            throw new IllegalArgumentException("tenantId is required");
        }
        ContextQueryRequest scope = toListScope(request);
        accessPolicyService.validateQueryScope(scope);
        MemoryLane lane = accessPolicyService.requireMemoryLane(request.getMemoryLane());

        int limit = request.getLimit() == null || request.getLimit() <= 0 ? 100 : Math.min(request.getLimit(), 500);
        int offset = request.getOffset() == null || request.getOffset() < 0 ? 0 : request.getOffset();

        Map<Long, ContextNamespaceEntity> namespaces = loadNamespacesForList(request);
        if (namespaces.isEmpty()) {
            auditService.record(ContextAuditEventType.READ, ContextAuditDecision.ALLOW,
                    "item list hits=0", scope, null, null);
            return List.of();
        }

        String itemType = normalizeEnumFilter(request.getItemType());
        String status = normalizeEnumFilter(request.getStatus());
        String keyword = normalizeKeyword(request.getKeyword());

        List<ContextItemEntity> candidates = itemMapper.selectList(
                Wrappers.lambdaQuery(ContextItemEntity.class)
                        .eq(ContextItemEntity::getMemoryLane, lane.name())
                        .in(ContextItemEntity::getNamespaceId, namespaces.keySet())
                        .eq(StringUtils.hasText(itemType), ContextItemEntity::getItemType, itemType)
                        .eq(StringUtils.hasText(status), ContextItemEntity::getStatus, status)
                        .ne(!StringUtils.hasText(status), ContextItemEntity::getStatus, ContextStatus.DELETED.name()));

        List<ContextItemResponse> results = new ArrayList<>();
        for (ContextItemEntity item : candidates) {
            ContextNamespaceEntity namespace = namespaces.get(item.getNamespaceId());
            if (namespace == null) {
                continue;
            }
            // Defensive re-filter (also covers mapper stubs/tests that ignore the SQL predicate):
            if (StringUtils.hasText(itemType) && !itemType.equalsIgnoreCase(item.getItemType())) {
                continue;
            }
            if (StringUtils.hasText(status) && !status.equalsIgnoreCase(item.getStatus())) {
                continue;
            } else if (!StringUtils.hasText(status)
                    && ContextStatus.DELETED.name().equalsIgnoreCase(item.getStatus())) {
                continue;
            }
            if (!accessPolicyService.isItemAccessibleInScope(item, namespace, scope)) {
                continue;
            }
            if (keyword != null && !matchesListKeyword(item, keyword)) {
                continue;
            }
            results.add(ContextViewMapper.toItemResponse(item));
        }

        results.sort(Comparator.comparing(
                ContextItemResponse::getUpdatedAt,
                Comparator.nullsLast(LocalDateTime::compareTo)).reversed());
        if (offset >= results.size()) {
            auditService.record(ContextAuditEventType.READ, ContextAuditDecision.ALLOW,
                    "item list hits=0", scope, null, null);
            return List.of();
        }
        int end = Math.min(offset + limit, results.size());
        List<ContextItemResponse> page = new ArrayList<>(results.subList(offset, end));
        auditService.record(ContextAuditEventType.READ, ContextAuditDecision.ALLOW,
                "item list hits=" + page.size(), scope, null, null);
        return page;
    }

    @Transactional
    public ContextItemResponse revokeItem(Long id, ContextQueryRequest scope) {
        accessPolicyService.requireItemWriteAccess(id, scope);
        ContextItemEntity entity = requireMutableItem(id);
        entity.setStatus(ContextStatus.REVOKED.name());
        entity.setUpdatedAt(LocalDateTime.now());
        itemMapper.updateById(entity);
        auditService.record(ContextAuditEventType.REVOKE, ContextAuditDecision.ALLOW,
                "item revoked", scope, entity.getId(), entity.getNamespaceId());
        return ContextViewMapper.toItemResponse(entity);
    }

    @Transactional
    public ContextItemResponse markStale(Long id, ContextQueryRequest scope) {
        accessPolicyService.requireItemWriteAccess(id, scope);
        ContextItemEntity entity = requireMutableItem(id);
        entity.setStatus(ContextStatus.STALE.name());
        entity.setStaleAfter(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        itemMapper.updateById(entity);
        auditService.record(ContextAuditEventType.MARK_STALE, ContextAuditDecision.ALLOW,
                "item marked stale", scope, entity.getId(), entity.getNamespaceId());
        return ContextViewMapper.toItemResponse(entity);
    }

    @Transactional
    public ContextItemResponse deleteItem(Long id, ContextQueryRequest scope) {
        accessPolicyService.requireItemWriteAccess(id, scope);
        ContextItemEntity entity = itemMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Context item not found: " + id);
        }
        entity.setStatus(ContextStatus.DELETED.name());
        entity.setDeletedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        itemMapper.updateById(entity);
        auditService.record(ContextAuditEventType.DELETE, ContextAuditDecision.ALLOW,
                "item logically deleted", scope, entity.getId(), entity.getNamespaceId());
        return ContextViewMapper.toItemResponse(entity);
    }

    @Transactional
    public ContextItemResponse verifyItem(Long id,
                                          BigDecimal confidence,
                                          String trustLevel,
                                          ContextQueryRequest scope) {
        accessPolicyService.requireItemWriteAccess(id, scope);
        ContextItemEntity entity = requireMutableItem(id);
        if (confidence != null) {
            entity.setConfidence(confidence);
        }
        if (StringUtils.hasText(trustLevel)) {
            entity.setTrustLevel(trustLevel.trim().toUpperCase());
        }
        entity.setLastVerifiedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        itemMapper.updateById(entity);
        auditService.record(ContextAuditEventType.VERIFY, ContextAuditDecision.ALLOW,
                "item verified", scope, entity.getId(), entity.getNamespaceId());
        return ContextViewMapper.toItemResponse(entity);
    }

    ContextItemEntity requireMutableItem(Long id) {
        ContextItemEntity entity = itemMapper.selectById(id);
        if (entity == null || ContextStatus.DELETED.name().equals(entity.getStatus())) {
            throw new IllegalArgumentException("Context item not found: " + id);
        }
        return entity;
    }

    /**
     * Lifecycle scan: ACTIVE items with staleAfter passed within the given namespaces. No READ audit.
     */
    public List<ContextItemEntity> listStaleCandidatesForLifecycle(Collection<Long> namespaceIds, int limit) {
        if (namespaceIds == null || namespaceIds.isEmpty()) {
            return List.of();
        }
        int effectiveLimit = limit <= 0 ? 500 : Math.min(limit, 5000);
        return itemMapper.selectList(
                Wrappers.lambdaQuery(ContextItemEntity.class)
                        .in(ContextItemEntity::getNamespaceId, namespaceIds)
                        .eq(ContextItemEntity::getStatus, ContextStatus.ACTIVE.name())
                        .isNotNull(ContextItemEntity::getStaleAfter)
                        .le(ContextItemEntity::getStaleAfter, LocalDateTime.now())
                        .orderByAsc(ContextItemEntity::getStaleAfter)
                        .last("LIMIT " + effectiveLimit));
    }

    public Map<Long, ContextNamespaceEntity> loadNamespacesByTenant(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return loadNamespacesForList(tenantListRequest(tenantId));
    }

    /**
     * System lifecycle stale path — does not use requireItemWriteAccess or user identity scope.
     */
    @Transactional
    public void markStaleByLifecycle(ContextItemEntity item,
                                     ContextNamespaceEntity namespace,
                                     String tenantId,
                                     String projectCode,
                                     Long projectId) {
        if (item == null || namespace == null) {
            throw new IllegalArgumentException("item and namespace are required");
        }
        if (!tenantId.equals(namespace.getTenantId())) {
            throw new IllegalArgumentException("namespace tenant mismatch for lifecycle stale");
        }
        item.setStatus(ContextStatus.STALE.name());
        item.setUpdatedAt(LocalDateTime.now());
        itemMapper.updateById(item);

        ContextQueryRequest scope = new ContextQueryRequest();
        scope.setTenantId(tenantId);
        scope.setProjectCode(projectCode != null ? projectCode : namespace.getProjectCode());
        scope.setProjectId(projectId != null ? projectId : namespace.getProjectId());
        scope.setMemoryLane(item.getMemoryLane());
        scope.setActorType("SYSTEM");
        scope.setActorId("context-lifecycle");
        auditService.record(ContextAuditEventType.MARK_STALE, ContextAuditDecision.ALLOW,
                "item marked stale by lifecycle", scope, item.getId(), namespace.getId());
    }

    private ContextItemListRequest tenantListRequest(String tenantId) {
        ContextItemListRequest request = new ContextItemListRequest();
        request.setTenantId(tenantId);
        return request;
    }

    private void validateCreateRequest(ContextItemCreateRequest request) {
        if (request == null || !StringUtils.hasText(request.getContent())) {
            throw new IllegalArgumentException("content is required");
        }
        if (!StringUtils.hasText(request.getItemType())) {
            throw new IllegalArgumentException("itemType is required");
        }
        if (!StringUtils.hasText(request.getSourceType())) {
            throw new IllegalArgumentException("sourceType is required");
        }
        if (!StringUtils.hasText(request.getMemoryLane())) {
            throw new IllegalArgumentException("memoryLane is required");
        }
        ContextItemType.valueOf(request.getItemType().trim().toUpperCase());
        ContextSourceType.valueOf(request.getSourceType().trim().toUpperCase());
        accessPolicyService.requireMemoryLane(request.getMemoryLane());
    }

    private BigDecimal defaultConfidence(BigDecimal confidence) {
        return confidence == null ? new BigDecimal("0.7000") : confidence;
    }

    private String defaultTrust(String trustLevel) {
        return StringUtils.hasText(trustLevel) ? trustLevel.trim().toUpperCase() : ContextTrustLevel.MEDIUM.name();
    }

    private String defaultVisibility(String visibility) {
        return StringUtils.hasText(visibility) ? visibility.trim().toUpperCase() : ContextVisibility.PRIVATE.name();
    }

    private List<ContextBindingRequest> resolveInitialBindings(ContextItemCreateRequest request,
                                                                 ContextQueryRequest scope) {
        if (request.getBindings() != null && !request.getBindings().isEmpty()) {
            return request.getBindings();
        }
        List<ContextBindingRequest> autoBindings = new ArrayList<>();
        if (StringUtils.hasText(scope.getUserId())) {
            autoBindings.add(identityBinding(request, ContextBindType.USER, scope.getUserId()));
        } else if (StringUtils.hasText(scope.getSessionId())) {
            autoBindings.add(identityBinding(request, ContextBindType.SESSION, scope.getSessionId()));
        } else if (StringUtils.hasText(scope.getAgentId())) {
            autoBindings.add(identityBinding(request, ContextBindType.AGENT, scope.getAgentId()));
        } else if (StringUtils.hasText(scope.getPageInstanceId())) {
            autoBindings.add(identityBinding(request, ContextBindType.PAGE, scope.getPageInstanceId()));
        } else if (StringUtils.hasText(scope.getWorkflowId())) {
            autoBindings.add(identityBinding(request, ContextBindType.WORKFLOW, scope.getWorkflowId()));
        }
        return autoBindings;
    }

    private ContextBindingRequest identityBinding(ContextItemCreateRequest request,
                                                    ContextBindType bindType,
                                                    String bindId) {
        ContextBindingRequest binding = new ContextBindingRequest();
        binding.setBindType(bindType.name());
        binding.setBindId(bindId);
        binding.setTenantId(request.getTenantId());
        binding.setProjectId(request.getProjectId());
        binding.setProjectCode(request.getProjectCode());
        return binding;
    }

    private ContextQueryRequest toListScope(ContextItemListRequest request) {
        ContextQueryRequest scope = new ContextQueryRequest();
        scope.setTenantId(request.getTenantId());
        scope.setProjectCode(request.getProjectCode());
        scope.setProjectId(request.getProjectId());
        scope.setMemoryLane(request.getMemoryLane());
        return scope;
    }

    private Map<Long, ContextNamespaceEntity> loadNamespacesForList(ContextItemListRequest request) {
        return namespaceService.listActiveNamespaceEntities(
                        request.getTenantId(),
                        request.getNamespaceId(),
                        request.getProjectCode(),
                        request.getProjectId())
                .stream()
                .collect(Collectors.toMap(ContextNamespaceEntity::getId, ns -> ns, (a, b) -> a, HashMap::new));
    }

    private String normalizeEnumFilter(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeKeyword(String query) {
        if (!StringUtils.hasText(query)) {
            return null;
        }
        return query.trim().toLowerCase(Locale.ROOT);
    }

    private boolean matchesListKeyword(ContextItemEntity item, String keyword) {
        return safeLower(item.getTitle()).contains(keyword)
                || safeLower(item.getSummary()).contains(keyword)
                || safeLower(item.getContent()).contains(keyword);
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}

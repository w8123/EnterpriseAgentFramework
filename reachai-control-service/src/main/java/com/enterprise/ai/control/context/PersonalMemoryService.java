package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.ai.control.context.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Canonical Control-owned personal memory service for the RUNTIME_USER lane. */
@Service
public class PersonalMemoryService {

    private static final Set<String> ALLOWED_TYPES = Set.of("FACT", "PREFERENCE", "RULE", "NOTE");
    private static final Set<String> GLOBAL_RECALL_TYPES = Set.of("PREFERENCE", "RULE");
    private static final Set<String> STABLE_PROFILE_SOURCE_REFS = Set.of(
            semanticSourceRef("profile:name"),
            semanticSourceRef("profile:location"),
            semanticSourceRef("profile:timezone"),
            semanticSourceRef("profile:role"),
            semanticSourceRef("response-language"));
    private static final int MAX_TITLE_CHARS = 256;
    private static final int MAX_SUMMARY_CHARS = 2_000;
    private static final int DEFAULT_MAX_ACTIVE_ITEMS_PER_OWNER = 1_000;
    public static final String ERASE_ALL_CONFIRMATION = "ERASE_ALL_PERSONAL_MEMORIES";
    private static final List<String> ERASE_ALL_RETAINED_DOMAINS = List.of(
            "RUNTIME_SESSION",
            "RUNOPS_TRACE_INTERACTION",
            "BUSINESS_SOURCE_SYSTEM",
            "BACKUP_EXPIRY_AND_RESTORE");

    private final ContextNamespaceMapper namespaceMapper;
    private final ContextItemMapper itemMapper;
    private final ContextBindingMapper bindingMapper;
    private final ContextEvidenceMapper evidenceMapper;
    private final ContextAuditEventMapper auditMapper;
    private final ObjectMapper objectMapper;
    private final PersonalMemoryIndexOutboxService indexOutboxService;
    private final PersonalMemoryKnowledgeQueryClient knowledgeQueryClient;
    private final ContextMemoryCandidateMapper candidateMapper;
    private final PersonalMemoryRetrievalTelemetry retrievalTelemetry;
    private final int maxActiveItemsPerOwner;

    public PersonalMemoryService(ContextNamespaceMapper namespaceMapper,
                                 ContextItemMapper itemMapper,
                                 ContextBindingMapper bindingMapper,
                                 ContextEvidenceMapper evidenceMapper,
                                 ContextAuditEventMapper auditMapper,
                                 ObjectMapper objectMapper) {
        this(namespaceMapper, itemMapper, bindingMapper, evidenceMapper, auditMapper, objectMapper,
                null, null, null, null, DEFAULT_MAX_ACTIVE_ITEMS_PER_OWNER);
    }

    public PersonalMemoryService(ContextNamespaceMapper namespaceMapper,
                                 ContextItemMapper itemMapper,
                                 ContextBindingMapper bindingMapper,
                                 ContextEvidenceMapper evidenceMapper,
                                 ContextAuditEventMapper auditMapper,
                                 ObjectMapper objectMapper,
                                 PersonalMemoryIndexOutboxService indexOutboxService,
                                 PersonalMemoryKnowledgeQueryClient knowledgeQueryClient) {
        this(namespaceMapper, itemMapper, bindingMapper, evidenceMapper, auditMapper, objectMapper,
                indexOutboxService, knowledgeQueryClient, null, null, DEFAULT_MAX_ACTIVE_ITEMS_PER_OWNER);
    }

    public PersonalMemoryService(ContextNamespaceMapper namespaceMapper,
                                 ContextItemMapper itemMapper,
                                 ContextBindingMapper bindingMapper,
                                 ContextEvidenceMapper evidenceMapper,
                                 ContextAuditEventMapper auditMapper,
                                 ObjectMapper objectMapper,
                                 PersonalMemoryIndexOutboxService indexOutboxService,
                                 PersonalMemoryKnowledgeQueryClient knowledgeQueryClient,
                                 ContextMemoryCandidateMapper candidateMapper) {
        this(namespaceMapper, itemMapper, bindingMapper, evidenceMapper, auditMapper, objectMapper,
                indexOutboxService, knowledgeQueryClient, candidateMapper, null,
                DEFAULT_MAX_ACTIVE_ITEMS_PER_OWNER);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PersonalMemoryService(ContextNamespaceMapper namespaceMapper,
                                 ContextItemMapper itemMapper,
                                 ContextBindingMapper bindingMapper,
                                 ContextEvidenceMapper evidenceMapper,
                                 ContextAuditEventMapper auditMapper,
                                 ObjectMapper objectMapper,
                                 PersonalMemoryIndexOutboxService indexOutboxService,
                                 PersonalMemoryKnowledgeQueryClient knowledgeQueryClient,
                                 ContextMemoryCandidateMapper candidateMapper,
                                 PersonalMemoryRetrievalTelemetry retrievalTelemetry,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${reachai.context.personal-memory.max-active-items-per-owner:1000}")
                                 int maxActiveItemsPerOwner) {
        this.namespaceMapper = namespaceMapper;
        this.itemMapper = itemMapper;
        this.bindingMapper = bindingMapper;
        this.evidenceMapper = evidenceMapper;
        this.auditMapper = auditMapper;
        this.objectMapper = objectMapper;
        this.indexOutboxService = indexOutboxService;
        this.knowledgeQueryClient = knowledgeQueryClient;
        this.candidateMapper = candidateMapper;
        this.retrievalTelemetry = retrievalTelemetry;
        if (maxActiveItemsPerOwner < 1 || maxActiveItemsPerOwner > 100_000) {
            throw new IllegalArgumentException(
                    "personal memory max-active-items-per-owner must be between 1 and 100000");
        }
        this.maxActiveItemsPerOwner = maxActiveItemsPerOwner;
    }

    public PersonalMemoryService(ContextNamespaceMapper namespaceMapper,
                                 ContextItemMapper itemMapper,
                                 ContextBindingMapper bindingMapper,
                                 ContextEvidenceMapper evidenceMapper,
                                 ContextAuditEventMapper auditMapper,
                                 ObjectMapper objectMapper,
                                 PersonalMemoryIndexOutboxService indexOutboxService,
                                 PersonalMemoryKnowledgeQueryClient knowledgeQueryClient,
                                 ContextMemoryCandidateMapper candidateMapper,
                                 PersonalMemoryRetrievalTelemetry retrievalTelemetry) {
        this(namespaceMapper, itemMapper, bindingMapper, evidenceMapper, auditMapper, objectMapper,
                indexOutboxService, knowledgeQueryClient, candidateMapper, retrievalTelemetry,
                DEFAULT_MAX_ACTIVE_ITEMS_PER_OWNER);
    }

    @Transactional(readOnly = true)
    public MemoryPage list(PersonalMemoryPrincipal principal,
                           String type,
                           String status,
                           String keyword,
                           Integer limit,
                           Integer offset) {
        ContextNamespaceEntity namespace = findNamespace(principal);
        if (namespace == null) {
            return new MemoryPage(List.of(), 0, boundedLimit(limit), boundedOffset(offset));
        }
        String requestedStatus = defaultUpper(status, "ACTIVE");
        if (!Set.of("ACTIVE", "STALE", "REVOKED").contains(requestedStatus)) {
            throw badRequest("unsupported personal memory status");
        }
        int boundedLimit = boundedLimit(limit);
        int boundedOffset = boundedOffset(offset);
        var scope = Wrappers.<ContextItemEntity>lambdaQuery()
                .eq(ContextItemEntity::getNamespaceId, namespace.getId())
                .eq(ContextItemEntity::getMemoryLane, "RUNTIME_USER")
                .eq(ContextItemEntity::getStatus, requestedStatus)
                .eq(StringUtils.hasText(type), ContextItemEntity::getItemType, validatedType(type))
                .and(StringUtils.hasText(keyword), q -> q
                        .like(ContextItemEntity::getTitle, trim(keyword))
                        .or()
                        .like(ContextItemEntity::getContent, trim(keyword)))
                .orderByDesc(ContextItemEntity::getUpdatedAt)
                .orderByDesc(ContextItemEntity::getId);
        long total = itemMapper.selectCount(scope);
        List<MemoryView> items = itemMapper.selectList(scope.last("LIMIT " + boundedOffset + ", " + boundedLimit))
                .stream().map(this::view).toList();
        return new MemoryPage(items, total, boundedLimit, boundedOffset);
    }

    @Transactional
    public RememberResult remember(PersonalMemoryPrincipal principal, RememberCommand command) {
        if (command == null) {
            throw badRequest("personal memory command is required");
        }
        String content = validatedContent(command.content());
        String type = validatedType(command.type());
        String title = boundedText(command.title(), MAX_TITLE_CHARS, "title");
        String summary = boundedText(command.summary(), MAX_SUMMARY_CHARS, "summary");
        String semanticKey = boundedText(command.semanticKey(), 128, "semanticKey");
        String clientRequestId = boundedText(command.clientRequestId(), 128, "clientRequestId");
        ContextNamespaceEntity namespace = ensureNamespace(principal);
        lockNamespace(namespace);

        if (StringUtils.hasText(clientRequestId)) {
            String itemKey = deterministicItemKey(principal, clientRequestId);
            ContextItemEntity replay = itemMapper.selectOne(Wrappers.<ContextItemEntity>lambdaQuery()
                    .eq(ContextItemEntity::getItemKey, itemKey).last("LIMIT 1 FOR UPDATE"));
            if (replay != null) {
                requireOwnedActive(replay, namespace);
                return new RememberResult(view(replay), false, true, false);
            }
        }

        if (StringUtils.hasText(semanticKey)) {
            ContextItemEntity existing = itemMapper.selectOne(Wrappers.<ContextItemEntity>lambdaQuery()
                    .eq(ContextItemEntity::getNamespaceId, namespace.getId())
                    .eq(ContextItemEntity::getMemoryLane, "RUNTIME_USER")
                    .eq(ContextItemEntity::getSourceRef, semanticSourceRef(semanticKey))
                    .ne(ContextItemEntity::getStatus, "DELETED")
                    .last("LIMIT 1 FOR UPDATE"));
            if (existing != null) {
                String previousHash = sha256(existing.getContent());
                applyMutableFields(existing, type, title, content, summary, command.expiresAt(), principal,
                        semanticKey, command.tags());
                existing.setStatus("ACTIVE");
                existing.setDeletedAt(null);
                itemMapper.updateById(existing);
                enqueueIndexUpsert(principal, existing);
                audit("UPDATE", principal, namespace, existing, command.sessionId(), command.traceId(),
                        command.agentId(), "ALLOW", "EXPLICIT_SEMANTIC_KEY_UPSERT",
                        Map.of("previousContentSha256", previousHash,
                                "contentSha256", sha256(content)));
                return new RememberResult(view(existing), false, false, true);
            }
        }

        ContextItemEntity duplicate = itemMapper.selectOne(Wrappers.<ContextItemEntity>lambdaQuery()
                .eq(ContextItemEntity::getNamespaceId, namespace.getId())
                .eq(ContextItemEntity::getMemoryLane, "RUNTIME_USER")
                .eq(ContextItemEntity::getItemType, type)
                .eq(ContextItemEntity::getContent, content)
                .eq(ContextItemEntity::getStatus, "ACTIVE")
                .last("LIMIT 1 FOR UPDATE"));
        if (duplicate != null) {
            audit("CREATE", principal, namespace, duplicate, command.sessionId(), command.traceId(),
                    command.agentId(), "SKIP", "EXACT_DUPLICATE", Map.of("contentSha256", sha256(content)));
            return new RememberResult(view(duplicate), false, true, false);
        }

        enforceActiveItemQuota(namespace);

        ContextItemEntity entity = new ContextItemEntity();
        entity.setItemKey(StringUtils.hasText(clientRequestId)
                ? deterministicItemKey(principal, clientRequestId)
                : "ctx-item-pm-" + UUID.randomUUID().toString().replace("-", ""));
        entity.setNamespaceId(namespace.getId());
        applyMutableFields(entity, type, title, content, summary, command.expiresAt(), principal,
                semanticKey, command.tags());
        entity.setSourceType("USER_CONFIRMED");
        entity.setConfidence(BigDecimal.ONE);
        entity.setTrustLevel("VERIFIED");
        entity.setVisibility("PRIVATE");
        entity.setStatus("ACTIVE");
        entity.setEffectiveFrom(LocalDateTime.now());
        entity.setCreatedBy(principal.runtimeUserId());
        entity.setCreatedAt(LocalDateTime.now());
        try {
            itemMapper.insert(entity);
        } catch (DuplicateKeyException replayRace) {
            ContextItemEntity replay = itemMapper.selectOne(Wrappers.<ContextItemEntity>lambdaQuery()
                    .eq(ContextItemEntity::getItemKey, entity.getItemKey()).last("LIMIT 1 FOR UPDATE"));
            if (replay == null) {
                throw replayRace;
            }
            requireOwnedActive(replay, namespace);
            return new RememberResult(view(replay), false, true, false);
        }
        insertUserBinding(entity, principal);
        insertConfirmationEvidence(entity, command);
        enqueueIndexUpsert(principal, entity);
        audit("CREATE", principal, namespace, entity, command.sessionId(), command.traceId(), command.agentId(),
                "ALLOW", "EXPLICIT_REMEMBER", Map.of("contentSha256", sha256(content)));
        return new RememberResult(view(entity), true, false, false);
    }

    @Transactional
    public MemoryView get(PersonalMemoryPrincipal principal, Long id) {
        OwnedMemory owned = requireOwned(principal, id, false, false);
        audit("READ", principal, owned.namespace(), owned.item(), null, null, null,
                "ALLOW", "SELF_READ", Map.of());
        return view(owned.item());
    }

    @Transactional
    public MemoryView update(PersonalMemoryPrincipal principal, Long id, UpdateCommand command) {
        if (command == null) {
            throw badRequest("personal memory update is required");
        }
        OwnedMemory owned = requireOwned(principal, id, false, true);
        ContextItemEntity item = owned.item();
        String previousHash = sha256(item.getContent());
        if (command.type() != null) {
            item.setItemType(validatedType(command.type()));
        }
        if (command.title() != null) {
            item.setTitle(boundedText(command.title(), MAX_TITLE_CHARS, "title"));
        }
        if (command.content() != null) {
            item.setContent(validatedContent(command.content()));
        }
        if (command.summary() != null) {
            item.setSummary(boundedText(command.summary(), MAX_SUMMARY_CHARS, "summary"));
        }
        if (command.expiresAt() != null) {
            item.setExpiresAt(parseDate(command.expiresAt()));
        }
        if (command.tags() != null) {
            item.setMetadataJson(metadata(null, command.tags()));
        }
        item.setUpdatedBy(principal.runtimeUserId());
        item.setUpdatedAt(LocalDateTime.now());
        itemMapper.updateById(item);
        enqueueIndexUpsert(principal, item);
        audit("UPDATE", principal, owned.namespace(), item, command.sessionId(), command.traceId(),
                command.agentId(), "ALLOW", "SELF_UPDATE",
                Map.of("previousContentSha256", previousHash, "contentSha256", sha256(item.getContent())));
        return view(item);
    }

    @Transactional
    public ForgetView forget(PersonalMemoryPrincipal principal, Long id, ForgetCommand command) {
        OwnedMemory owned = requireOwned(principal, id, true, true);
        ContextItemEntity item = owned.item();
        if ("DELETED".equals(item.getStatus())) {
            return new ForgetView(id, "DELETED", item.getDeletedAt(), true);
        }
        LocalDateTime now = LocalDateTime.now();
        tombstone(principal, item, now);
        audit("DELETE", principal, owned.namespace(), item,
                command == null ? null : command.sessionId(), command == null ? null : command.traceId(),
                command == null ? null : command.agentId(), "ALLOW", "SELF_FORGET",
                Map.of("reasonProvided", command != null && StringUtils.hasText(command.reason())));
        return new ForgetView(id, "DELETED", now, false);
    }

    /**
     * Erases the current owner's complete Control personal-memory domain. This intentionally does
     * not claim to erase Runtime sessions, business source systems, RunOps, or infrastructure
     * backups; those domains require their own owner and completion evidence.
     */
    @Transactional
    public EraseAllView eraseAll(PersonalMemoryPrincipal principal, EraseAllCommand command) {
        return eraseAllInternal(principal, command, null);
    }

    /** Internal entry point used by the durable cross-domain erasure workflow. */
    @Transactional
    EraseAllView eraseAllForOrchestration(PersonalMemoryPrincipal principal,
                                          EraseAllCommand command,
                                          String correlationId) {
        return eraseAllInternal(principal, command, correlationId);
    }

    private EraseAllView eraseAllInternal(PersonalMemoryPrincipal principal,
                                          EraseAllCommand command,
                                          String correlationId) {
        if (command == null || !ERASE_ALL_CONFIRMATION.equals(command.confirmation())) {
            throw badRequest("explicit personal memory erase-all confirmation is required");
        }
        ContextNamespaceEntity namespace = ensureNamespace(principal);
        lockNamespace(namespace);
        LocalDateTime now = LocalDateTime.now();
        List<ContextItemEntity> items = itemMapper.selectPersonalByNamespaceForUpdate(namespace.getId());
        int candidatesErased = 0;
        for (ContextItemEntity item : items) {
            candidatesErased += tombstone(principal, item, now, correlationId);
        }
        if (candidateMapper != null) {
            candidatesErased += candidateMapper.deleteAllPrivateForOwner(
                    principal.tenantId(), principal.runtimeUserId());
        }
        scrubNamespaceAudit(namespace.getId());
        audit("ERASE_ALL", principal, namespace, null,
                command.sessionId(), command.traceId(), command.agentId(),
                "ALLOW", "SELF_ERASE_ALL", Map.of(
                        "memoriesErased", items.size(),
                        "candidatesErased", candidatesErased,
                        "reasonProvided", StringUtils.hasText(command.reason()),
                        "retainedDomains", ERASE_ALL_RETAINED_DOMAINS));
        return new EraseAllView(
                "CONTROL_PERSONAL_MEMORY_ERASED",
                items.size(),
                candidatesErased,
                now,
                items.isEmpty() && candidatesErased == 0,
                ERASE_ALL_RETAINED_DOMAINS);
    }

    /**
     * Physically scrubs expired private memories. Candidate discovery is lock-free,
     * but namespace then item locks preserve the same order as owner-initiated writes.
     */
    @Transactional
    public int expireDue(int requestedBatchSize) {
        int batchSize = Math.max(1, Math.min(requestedBatchSize, 500));
        LocalDateTime now = LocalDateTime.now();
        List<ContextItemEntity> due = itemMapper.selectList(Wrappers.<ContextItemEntity>lambdaQuery()
                .eq(ContextItemEntity::getMemoryLane, "RUNTIME_USER")
                .ne(ContextItemEntity::getStatus, "DELETED")
                .isNotNull(ContextItemEntity::getExpiresAt)
                .le(ContextItemEntity::getExpiresAt, now)
                .orderByAsc(ContextItemEntity::getExpiresAt)
                .orderByAsc(ContextItemEntity::getId)
                .last("LIMIT " + batchSize));
        int expired = 0;
        for (ContextItemEntity candidate : due) {
            if (candidate == null || candidate.getId() == null || candidate.getNamespaceId() == null) continue;
            namespaceMapper.lockById(candidate.getNamespaceId());
            ContextNamespaceEntity namespace = namespaceMapper.selectById(candidate.getNamespaceId());
            ContextItemEntity item = itemMapper.selectByIdForUpdate(candidate.getId());
            if (!PersonalMemoryRouteBoundary.isPersonalNamespace(namespace)
                    || !PersonalMemoryRouteBoundary.isPersonalItem(item)
                    || "DELETED".equals(item.getStatus())
                    || item.getExpiresAt() == null || item.getExpiresAt().isAfter(now)
                    || !StringUtils.hasText(namespace.getTenantId())
                    || !StringUtils.hasText(namespace.getOwnerId())) {
                continue;
            }
            PersonalMemoryPrincipal owner = new PersonalMemoryPrincipal(
                    namespace.getTenantId(), namespace.getOwnerId(), null,
                    "personal-memory-lifecycle", "personal-memory-lifecycle");
            LocalDateTime expiredAt = item.getExpiresAt();
            tombstone(owner, item, now);
            auditAs("EXPIRE", owner, namespace, item, null, null, null,
                    "ALLOW", "RETENTION_EXPIRED",
                    Map.of("expiredAt", expiredAt.toString()),
                    "SYSTEM", "personal-memory-lifecycle");
            expired++;
        }
        return expired;
    }

    private int tombstone(PersonalMemoryPrincipal principal,
                          ContextItemEntity item,
                          LocalDateTime now) {
        return tombstone(principal, item, now, null);
    }

    private int tombstone(PersonalMemoryPrincipal principal,
                          ContextItemEntity item,
                          LocalDateTime now,
                          String correlationId) {
        item.setTitle(null);
        item.setContent("[deleted]");
        item.setSummary(null);
        item.setMetadataJson("{\"deleted\":true}");
        item.setSourceRef(null);
        item.setExpiresAt(null);
        item.setLastVerifiedAt(null);
        item.setStaleAfter(null);
        item.setStatus("DELETED");
        item.setCreatedBy(null);
        item.setUpdatedBy(null);
        item.setUpdatedAt(now);
        item.setDeletedAt(now);
        // updateById follows MyBatis-Plus NOT_NULL field strategy and would silently
        // retain privacy-sensitive nullable fields such as title/summary/source_ref.
        // Tombstoning must issue explicit NULL assignments so the canonical row is scrubbed,
        // not merely hidden behind status=DELETED.
        itemMapper.update(null, Wrappers.<ContextItemEntity>lambdaUpdate()
                .eq(ContextItemEntity::getId, item.getId())
                .set(ContextItemEntity::getTitle, null)
                .set(ContextItemEntity::getContent, item.getContent())
                .set(ContextItemEntity::getSummary, null)
                .set(ContextItemEntity::getMetadataJson, item.getMetadataJson())
                .set(ContextItemEntity::getSourceRef, null)
                .set(ContextItemEntity::getExpiresAt, null)
                .set(ContextItemEntity::getLastVerifiedAt, null)
                .set(ContextItemEntity::getStaleAfter, null)
                .set(ContextItemEntity::getStatus, item.getStatus())
                .set(ContextItemEntity::getCreatedBy, null)
                .set(ContextItemEntity::getUpdatedBy, null)
                .set(ContextItemEntity::getUpdatedAt, item.getUpdatedAt())
                .set(ContextItemEntity::getDeletedAt, item.getDeletedAt()));
        bindingMapper.delete(Wrappers.<ContextBindingEntity>lambdaQuery()
                .eq(ContextBindingEntity::getItemId, item.getId()));
        evidenceMapper.delete(Wrappers.<ContextEvidenceEntity>lambdaQuery()
                .eq(ContextEvidenceEntity::getItemId, item.getId()));
        int candidatesErased = deleteRelatedCandidates(item.getId());
        scrubItemAudit(item.getId());
        if (indexOutboxService != null) {
            if (correlationId == null) {
                indexOutboxService.enqueueDelete(principal, item);
            } else {
                indexOutboxService.enqueueDelete(principal, item, correlationId);
            }
        }
        return candidatesErased;
    }

    private int deleteRelatedCandidates(Long itemId) {
        if (candidateMapper == null || itemId == null) return 0;
        return candidateMapper.deleteRelatedToItem(itemId);
    }

    private void scrubItemAudit(Long itemId) {
        if (itemId == null) return;
        auditMapper.update(null, Wrappers.<ContextAuditEventEntity>lambdaUpdate()
                .eq(ContextAuditEventEntity::getItemId, itemId)
                .set(ContextAuditEventEntity::getAgentId, null)
                .set(ContextAuditEventEntity::getWorkflowId, null)
                .set(ContextAuditEventEntity::getSessionId, null)
                .set(ContextAuditEventEntity::getTraceId, null)
                .set(ContextAuditEventEntity::getMetadataJson, "{\"erased\":true}"));
    }

    private void scrubNamespaceAudit(Long namespaceId) {
        if (namespaceId == null) return;
        auditMapper.update(null, Wrappers.<ContextAuditEventEntity>lambdaUpdate()
                .eq(ContextAuditEventEntity::getNamespaceId, namespaceId)
                .set(ContextAuditEventEntity::getItemId, null)
                .set(ContextAuditEventEntity::getAgentId, null)
                .set(ContextAuditEventEntity::getWorkflowId, null)
                .set(ContextAuditEventEntity::getSessionId, null)
                .set(ContextAuditEventEntity::getTraceId, null)
                .set(ContextAuditEventEntity::getMetadataJson, "{\"erased\":true}"));
    }

    @Transactional
    public QueryResult query(PersonalMemoryPrincipal principal, QueryCommand command) {
        if (command == null) {
            throw badRequest("personal memory query is required");
        }
        ContextNamespaceEntity namespace = findNamespace(principal);
        int topK = Math.max(1, Math.min(command.topK() == null ? 8 : command.topK(), 50));
        int maxChars = Math.max(256, Math.min(command.maxChars() == null ? 6_000 : command.maxChars(), 32_000));
        if (namespace == null) {
            return new QueryResult(List.of(), 0, 0, maxChars, false);
        }
        Set<String> types = validatedTypes(command.types());
        LocalDateTime now = LocalDateTime.now();
        PersonalMemoryKnowledgeQueryClient.QueryAttempt knowledgeAttempt = knowledgeQueryClient == null
                ? PersonalMemoryKnowledgeQueryClient.QueryAttempt.off()
                : knowledgeQueryClient.query(principal.tenantId(), principal.runtimeUserId(),
                        command.query(), topK * 3, maxChars);
        List<Long> projectedIds = knowledgeAttempt.successful()
                && knowledgeAttempt.mode().affectsRanking() ? knowledgeAttempt.ids() : List.of();
        // Projection hits are hints only. Every id is re-read from the canonical owner namespace,
        // current status and expiry below, so a delayed delete can never re-inject forgotten text.
        List<ContextItemEntity> projectedOrRecent = itemMapper.selectList(Wrappers.<ContextItemEntity>lambdaQuery()
                .eq(ContextItemEntity::getNamespaceId, namespace.getId())
                .eq(ContextItemEntity::getMemoryLane, "RUNTIME_USER")
                .eq(ContextItemEntity::getStatus, "ACTIVE")
                .in(!types.isEmpty(), ContextItemEntity::getItemType, types)
                .in(!projectedIds.isEmpty(), ContextItemEntity::getId, projectedIds)
                .and(q -> q.isNull(ContextItemEntity::getEffectiveFrom)
                        .or().le(ContextItemEntity::getEffectiveFrom, now))
                .and(q -> q.isNull(ContextItemEntity::getExpiresAt)
                        .or().gt(ContextItemEntity::getExpiresAt, now))
                .orderByDesc(ContextItemEntity::getLastVerifiedAt)
                .orderByDesc(ContextItemEntity::getUpdatedAt)
                .last("LIMIT 500"));
        // A successful lexical/vector projection must not suppress old global rules,
        // preferences, or stable profile facts. Fetch those bounded sticky candidates
        // separately, then revalidate and rank the union in the canonical owner service.
        List<ContextItemEntity> stickyCandidates = itemMapper.selectList(
                Wrappers.<ContextItemEntity>lambdaQuery()
                        .eq(ContextItemEntity::getNamespaceId, namespace.getId())
                        .eq(ContextItemEntity::getMemoryLane, "RUNTIME_USER")
                        .eq(ContextItemEntity::getStatus, "ACTIVE")
                        .in(!types.isEmpty(), ContextItemEntity::getItemType, types)
                        .and(q -> q.in(ContextItemEntity::getItemType, GLOBAL_RECALL_TYPES)
                                .or()
                                .in(ContextItemEntity::getSourceRef, STABLE_PROFILE_SOURCE_REFS))
                        .and(q -> q.isNull(ContextItemEntity::getEffectiveFrom)
                                .or().le(ContextItemEntity::getEffectiveFrom, now))
                        .and(q -> q.isNull(ContextItemEntity::getExpiresAt)
                                .or().gt(ContextItemEntity::getExpiresAt, now))
                        .orderByDesc(ContextItemEntity::getLastVerifiedAt)
                        .orderByDesc(ContextItemEntity::getUpdatedAt)
                        .last("LIMIT 200"));
        Map<Long, ContextItemEntity> uniqueCandidates = new LinkedHashMap<>();
        projectedOrRecent.forEach(item -> uniqueCandidates.put(item.getId(), item));
        stickyCandidates.forEach(item -> uniqueCandidates.putIfAbsent(item.getId(), item));
        List<ContextItemEntity> candidates = List.copyOf(uniqueCandidates.values());
        Set<Long> projectedIdSet = Set.copyOf(projectedIds);
        String normalizedQuery = normalize(command.query());
        List<ScoredMemory> ranked = candidates.stream()
                .map(item -> new ScoredMemory(item, lexicalScore(item, normalizedQuery)
                        + (projectedIdSet.contains(item.getId()) ? 4.0d : 0.0d)))
                .filter(scored -> normalizedQuery.isEmpty() || scored.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed()
                        .thenComparing(scored -> scored.item().getUpdatedAt(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        List<QueryHit> selected = new ArrayList<>();
        int usedChars = 0;
        boolean truncated = false;
        for (ScoredMemory scored : ranked) {
            if (selected.size() >= topK) {
                truncated = true;
                break;
            }
            int chars = text(scored.item().getContent()).length();
            if (!selected.isEmpty() && usedChars + chars > maxChars) {
                truncated = true;
                continue;
            }
            String content = scored.item().getContent();
            if (selected.isEmpty() && chars > maxChars) {
                content = content.substring(0, maxChars);
                chars = content.length();
                truncated = true;
            }
            selected.add(new QueryHit(scored.item().getId(), scored.item().getItemKey(),
                    scored.item().getItemType(), scored.item().getTitle(), content,
                    scored.item().getSummary(), scored.score(), scored.item().getTrustLevel(),
                    scored.item().getUpdatedAt()));
            usedChars += chars;
        }
        Set<Long> canonicalCandidateIds = candidates.stream()
                .map(ContextItemEntity::getId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (retrievalTelemetry != null) {
            retrievalTelemetry.record(knowledgeAttempt,
                    selected.stream().map(QueryHit::id).toList(), canonicalCandidateIds, topK);
        }
        audit("SEARCH", principal, namespace, null, command.sessionId(), command.traceId(), command.agentId(),
                "ALLOW", "SELF_QUERY", Map.of(
                        "querySha256", sha256(text(command.query())),
                        "candidateCount", candidates.size(),
                        "selectedCount", selected.size(),
                        "usedChars", usedChars,
                        "knowledgeQueryMode", knowledgeAttempt.mode().name(),
                        "knowledgeQueryOutcome", knowledgeAttempt.outcome(),
                        "knowledgeHitCount", knowledgeAttempt.ids().size()));
        return new QueryResult(selected, candidates.size(), usedChars, maxChars, truncated);
    }

    @Transactional(readOnly = true)
    public ExportView export(PersonalMemoryPrincipal principal) {
        ContextNamespaceEntity namespace = findNamespace(principal);
        List<MemoryView> items = namespace == null ? List.of() : itemMapper.selectList(
                        Wrappers.<ContextItemEntity>lambdaQuery()
                                .eq(ContextItemEntity::getNamespaceId, namespace.getId())
                                .eq(ContextItemEntity::getMemoryLane, "RUNTIME_USER")
                                .ne(ContextItemEntity::getStatus, "DELETED")
                                .orderByAsc(ContextItemEntity::getCreatedAt)
                                .orderByAsc(ContextItemEntity::getId))
                .stream().map(this::view).toList();
        return new ExportView("reachai-personal-memory-v1", LocalDateTime.now(), principal.tenantId(),
                principal.runtimeUserId(), items);
    }

    @Transactional(readOnly = true)
    public List<AuditView> audit(PersonalMemoryPrincipal principal, Integer limit) {
        ContextNamespaceEntity namespace = findNamespace(principal);
        if (namespace == null) {
            return List.of();
        }
        int boundedLimit = Math.max(1, Math.min(limit == null ? 100 : limit, 500));
        return auditMapper.selectList(Wrappers.<ContextAuditEventEntity>lambdaQuery()
                        .eq(ContextAuditEventEntity::getNamespaceId, namespace.getId())
                        .eq(ContextAuditEventEntity::getTenantId, principal.tenantId())
                        .orderByDesc(ContextAuditEventEntity::getCreatedAt)
                        .orderByDesc(ContextAuditEventEntity::getId)
                        .last("LIMIT " + boundedLimit))
                .stream()
                .map(event -> new AuditView(event.getId(), event.getEventType(), event.getItemId(),
                        event.getDecision(), event.getReason(), event.getSessionId(), event.getTraceId(),
                        event.getAgentId(), event.getCreatedAt()))
                .toList();
    }

    ContextNamespaceEntity ensureNamespace(PersonalMemoryPrincipal principal) {
        ContextNamespaceEntity existing = findNamespace(principal);
        if (existing != null) {
            return existing;
        }
        String namespaceKey = namespaceKey(principal);
        ContextNamespaceEntity anyStatus = namespaceMapper.selectOne(Wrappers.<ContextNamespaceEntity>lambdaQuery()
                .eq(ContextNamespaceEntity::getNamespaceKey, namespaceKey).last("LIMIT 1 FOR UPDATE"));
        if (anyStatus != null) {
            assertNamespaceOwner(anyStatus, principal);
            anyStatus.setStatus("ACTIVE");
            anyStatus.setDeletedAt(null);
            anyStatus.setUpdatedAt(LocalDateTime.now());
            namespaceMapper.updateById(anyStatus);
            return anyStatus;
        }
        ContextNamespaceEntity created = new ContextNamespaceEntity();
        created.setNamespaceKey(namespaceKey);
        created.setNamespaceType("USER");
        created.setTenantId(principal.tenantId());
        created.setOwnerType("RUNTIME_USER");
        created.setOwnerId(principal.runtimeUserId());
        created.setDisplayName("Personal memory");
        created.setDescription("Private runtime user memory managed through the self-service API");
        created.setStatus("ACTIVE");
        created.setCreatedBy(principal.runtimeUserId());
        created.setCreatedAt(LocalDateTime.now());
        created.setUpdatedAt(created.getCreatedAt());
        try {
            namespaceMapper.insert(created);
            return created;
        } catch (DuplicateKeyException race) {
            ContextNamespaceEntity raced = namespaceMapper.selectOne(Wrappers.<ContextNamespaceEntity>lambdaQuery()
                    .eq(ContextNamespaceEntity::getNamespaceKey, namespaceKey).last("LIMIT 1 FOR UPDATE"));
            if (raced == null) {
                throw race;
            }
            assertNamespaceOwner(raced, principal);
            return raced;
        }
    }

    private ContextNamespaceEntity findNamespace(PersonalMemoryPrincipal principal) {
        return namespaceMapper.selectOne(Wrappers.<ContextNamespaceEntity>lambdaQuery()
                .eq(ContextNamespaceEntity::getTenantId, principal.tenantId())
                .eq(ContextNamespaceEntity::getNamespaceType, "USER")
                .eq(ContextNamespaceEntity::getOwnerType, "RUNTIME_USER")
                .eq(ContextNamespaceEntity::getOwnerId, principal.runtimeUserId())
                .eq(ContextNamespaceEntity::getStatus, "ACTIVE")
                .last("LIMIT 1"));
    }

    private OwnedMemory requireOwned(PersonalMemoryPrincipal principal,
                                     Long id,
                                     boolean includeDeleted,
                                     boolean forUpdate) {
        if (id == null) {
            throw notFound();
        }
        ContextNamespaceEntity namespace = findNamespace(principal);
        if (namespace == null) {
            throw notFound();
        }
        if (forUpdate) {
            lockNamespace(namespace);
        }
        ContextItemEntity item = forUpdate ? itemMapper.selectByIdForUpdate(id) : itemMapper.selectById(id);
        if (item == null || !namespace.getId().equals(item.getNamespaceId())
                || !"RUNTIME_USER".equals(item.getMemoryLane())
                || (!includeDeleted && "DELETED".equals(item.getStatus()))) {
            throw notFound();
        }
        return new OwnedMemory(namespace, item);
    }

    void lockNamespace(ContextNamespaceEntity namespace) {
        if (namespace != null && namespace.getId() != null) {
            namespaceMapper.lockById(namespace.getId());
        }
    }

    private void enforceActiveItemQuota(ContextNamespaceEntity namespace) {
        LocalDateTime now = LocalDateTime.now();
        long active = itemMapper.selectCount(Wrappers.<ContextItemEntity>lambdaQuery()
                .eq(ContextItemEntity::getNamespaceId, namespace.getId())
                .eq(ContextItemEntity::getMemoryLane, "RUNTIME_USER")
                .eq(ContextItemEntity::getStatus, "ACTIVE")
                .and(q -> q.isNull(ContextItemEntity::getExpiresAt)
                        .or().gt(ContextItemEntity::getExpiresAt, now)));
        if (active >= maxActiveItemsPerOwner) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "personal memory active item quota exceeded");
        }
    }

    private void requireOwnedActive(ContextItemEntity item, ContextNamespaceEntity namespace) {
        if (!namespace.getId().equals(item.getNamespaceId())
                || !"RUNTIME_USER".equals(item.getMemoryLane())
                || "DELETED".equals(item.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "idempotency key belongs to another memory");
        }
    }

    private void assertNamespaceOwner(ContextNamespaceEntity namespace, PersonalMemoryPrincipal principal) {
        if (!principal.tenantId().equals(namespace.getTenantId())
                || !"RUNTIME_USER".equals(namespace.getOwnerType())
                || !principal.runtimeUserId().equals(namespace.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "personal memory namespace ownership conflict");
        }
    }

    private void applyMutableFields(ContextItemEntity entity,
                                    String type,
                                    String title,
                                    String content,
                                    String summary,
                                    String expiresAt,
                                    PersonalMemoryPrincipal principal,
                                    String semanticKey,
                                    List<String> tags) {
        entity.setItemType(type);
        entity.setMemoryLane("RUNTIME_USER");
        entity.setTitle(title);
        entity.setContent(content);
        entity.setSummary(summary);
        entity.setMetadataJson(metadata(semanticKey, tags));
        entity.setSourceRef(StringUtils.hasText(semanticKey) ? semanticSourceRef(semanticKey) : null);
        entity.setExpiresAt(parseDate(expiresAt));
        entity.setUpdatedBy(principal.runtimeUserId());
        entity.setUpdatedAt(LocalDateTime.now());
    }

    private void insertUserBinding(ContextItemEntity item, PersonalMemoryPrincipal principal) {
        ContextBindingEntity binding = new ContextBindingEntity();
        binding.setItemId(item.getId());
        binding.setBindType("USER");
        binding.setBindId(principal.runtimeUserId());
        binding.setTenantId(principal.tenantId());
        binding.setStatus("ACTIVE");
        binding.setCreatedAt(LocalDateTime.now());
        bindingMapper.insert(binding);
    }

    private void insertConfirmationEvidence(ContextItemEntity item, RememberCommand command) {
        ContextEvidenceEntity evidence = new ContextEvidenceEntity();
        evidence.setItemId(item.getId());
        evidence.setEvidenceType("USER_CONFIRMATION");
        evidence.setEvidenceRef("explicit-remember");
        evidence.setEvidenceExcerpt("User explicitly requested this memory to be retained.");
        evidence.setTraceId(trim(command.traceId()));
        evidence.setConfidence(BigDecimal.ONE);
        evidence.setMetadataJson("{}");
        evidence.setCreatedAt(LocalDateTime.now());
        evidenceMapper.insert(evidence);
    }

    private void audit(String eventType,
                       PersonalMemoryPrincipal principal,
                       ContextNamespaceEntity namespace,
                       ContextItemEntity item,
                       String sessionId,
                       String traceId,
                       String agentId,
                       String decision,
                       String reason,
                       Map<String, Object> metadata) {
        auditAs(eventType, principal, namespace, item, sessionId, traceId, agentId,
                decision, reason, metadata, "RUNTIME_USER", principal.runtimeUserId());
    }

    private void auditAs(String eventType,
                         PersonalMemoryPrincipal principal,
                         ContextNamespaceEntity namespace,
                         ContextItemEntity item,
                         String sessionId,
                         String traceId,
                         String agentId,
                         String decision,
                         String reason,
                         Map<String, Object> metadata,
                         String actorType,
                         String actorId) {
        ContextAuditEventEntity event = new ContextAuditEventEntity();
        event.setEventType(eventType);
        event.setItemId(item == null ? null : item.getId());
        event.setNamespaceId(namespace == null ? null : namespace.getId());
        event.setActorType(actorType);
        event.setActorId(actorId);
        event.setTenantId(principal.tenantId());
        event.setAgentId(trim(agentId));
        event.setSessionId(trim(sessionId));
        event.setTraceId(trim(traceId));
        event.setDecision(decision);
        event.setReason(reason);
        event.setMetadataJson(json(metadata));
        event.setCreatedAt(LocalDateTime.now());
        auditMapper.insert(event);
    }

    private MemoryView view(ContextItemEntity entity) {
        return new MemoryView(entity.getId(), entity.getItemKey(), entity.getItemType(), entity.getTitle(),
                entity.getContent(), entity.getSummary(), entity.getTrustLevel(), entity.getStatus(),
                entity.getEffectiveFrom(), entity.getExpiresAt(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private void enqueueIndexUpsert(PersonalMemoryPrincipal principal, ContextItemEntity item) {
        if (indexOutboxService != null) {
            indexOutboxService.enqueueUpsert(principal, item);
        }
    }

    private String validatedContent(String value) {
        return PersonalMemoryContentPolicy.validate(value);
    }

    private String validatedType(String value) {
        String type = defaultUpper(value, "NOTE");
        if (!ALLOWED_TYPES.contains(type)) {
            throw badRequest("personal memory type must be FACT, PREFERENCE, RULE, or NOTE");
        }
        return type;
    }

    private Set<String> validatedTypes(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> types = new LinkedHashSet<>();
        for (String value : values) {
            types.add(validatedType(value));
        }
        return Set.copyOf(types);
    }

    private String boundedText(String value, int max, String field) {
        String result = trim(value);
        if (result != null && result.length() > max) {
            throw badRequest("personal memory " + field + " exceeds " + max + " characters");
        }
        return result;
    }

    private String metadata(String semanticKey, List<String> rawTags) {
        List<String> tags = rawTags == null ? List.of() : rawTags.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(tag -> tag.length() <= 64)
                .distinct()
                .limit(20)
                .toList();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schema", "reachai-personal-memory-v1");
        if (StringUtils.hasText(semanticKey)) {
            metadata.put("semanticKey", semanticKey);
        }
        metadata.put("tags", tags);
        return json(metadata);
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize personal memory metadata", e);
        }
    }

    private static String deterministicItemKey(PersonalMemoryPrincipal principal, String clientRequestId) {
        return "ctx-item-pm-" + sha256(principal.tenantId() + "\n" + principal.runtimeUserId()
                + "\n" + clientRequestId);
    }

    private static String namespaceKey(PersonalMemoryPrincipal principal) {
        return "ctx:user:" + sha256(principal.tenantId() + "\n" + principal.runtimeUserId());
    }

    static String semanticSourceRef(String key) {
        return "personal-key:" + sha256(key.toLowerCase(Locale.ROOT));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte valueByte : digest) {
                result.append(String.format("%02x", valueByte));
            }
            return result.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static double lexicalScore(ContextItemEntity item, String normalizedQuery) {
        if (normalizedQuery.isEmpty()) {
            return 1.0d;
        }
        String title = normalize(item.getTitle());
        String summary = normalize(item.getSummary());
        String content = normalize(item.getContent());
        // Global rules and preferences remain available even when the current
        // wording has no lexical overlap. Facts and notes still need lexical or
        // bounded profile-intent relevance, avoiding broad injection of private data.
        double score = switch (text(item.getItemType()).toUpperCase(Locale.ROOT)) {
            case "RULE" -> 3.0d;
            case "PREFERENCE" -> 1.5d;
            default -> 0.0d;
        };
        score += profileIntentScore(item.getSourceRef(), normalizedQuery);
        if (title.equals(normalizedQuery)) {
            score += 8;
        } else if (title.contains(normalizedQuery)) {
            score += 4;
        }
        if (summary.contains(normalizedQuery)) {
            score += 2;
        }
        if (content.contains(normalizedQuery)) {
            score += 3;
        }
        for (String token : queryTokens(normalizedQuery)) {
            if (title.contains(token)) {
                score += 1.5;
            }
            if (summary.contains(token)) {
                score += 0.75;
            }
            if (content.contains(token)) {
                score += 1;
            }
        }
        if (score > 0 && "VERIFIED".equals(item.getTrustLevel())) {
            score += 0.25;
        }
        return score;
    }

    private static double profileIntentScore(String sourceRef, String query) {
        if (!StringUtils.hasText(sourceRef) || !StringUtils.hasText(query)) {
            return 0.0d;
        }
        if (sourceRef.equals(semanticSourceRef("profile:name"))
                && containsAny(query, "名字", "姓名", "称呼", "我是谁", "name", "who am i", "call me")) {
            return 6.0d;
        }
        if (sourceRef.equals(semanticSourceRef("profile:location"))
                && containsAny(query, "住哪", "住在", "哪里", "哪儿", "城市", "地点", "当地", "本地", "附近",
                "天气", "location", "where", "city", "live", "local", "nearby", "weather")) {
            return 6.0d;
        }
        if (sourceRef.equals(semanticSourceRef("profile:timezone"))
                && containsAny(query, "时区", "几点", "当地时间", "timezone", "time zone", "local time")) {
            return 6.0d;
        }
        if (sourceRef.equals(semanticSourceRef("profile:role"))
                && containsAny(query, "岗位", "职位", "角色", "职业", "工作", "job", "role", "position", "career")) {
            return 6.0d;
        }
        if (sourceRef.equals(semanticSourceRef("response-language"))
                && containsAny(query, "语言", "中文", "英文", "英语", "回答", "回复",
                "language", "chinese", "english", "respond", "reply")) {
            return 6.0d;
        }
        return 0.0d;
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> queryTokens(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        return Pattern.compile("[\\s,，。！？、;；:：]+")
                .splitAsStream(query)
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .distinct()
                .limit(16)
                .toList();
    }

    private static LocalDateTime parseDate(String value) {
        String date = trim(value);
        if (!StringUtils.hasText(date)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(date).toLocalDateTime();
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(date);
            } catch (Exception invalid) {
                throw badRequest("invalid personal memory expiresAt");
            }
        }
    }

    private static int boundedLimit(Integer limit) {
        return Math.max(1, Math.min(limit == null ? 50 : limit, 200));
    }

    private static int boundedOffset(Integer offset) {
        return Math.max(0, offset == null ? 0 : offset);
    }

    private static String defaultUpper(String value, String fallback) {
        String result = trim(value);
        return StringUtils.hasText(result) ? result.toUpperCase(Locale.ROOT) : fallback;
    }

    private static String normalize(String value) {
        return text(value).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "personal memory not found");
    }

    private record OwnedMemory(ContextNamespaceEntity namespace, ContextItemEntity item) {
    }

    private record ScoredMemory(ContextItemEntity item, double score) {
    }

    public record RememberCommand(String type, String semanticKey, String title, String content, String summary,
                                  List<String> tags, String expiresAt, String clientRequestId,
                                  String sessionId, String traceId, String agentId) {
    }

    public record UpdateCommand(String type, String title, String content, String summary, List<String> tags,
                                String expiresAt, String sessionId, String traceId, String agentId) {
    }

    public record ForgetCommand(String reason, String sessionId, String traceId, String agentId) {
    }

    public record EraseAllCommand(String confirmation, String reason,
                                  String sessionId, String traceId, String agentId) {
    }

    public record EraseAllView(String status, int memoriesErased, int candidatesErased,
                               LocalDateTime erasedAt, boolean idempotent,
                               List<String> retainedDomains) {
    }

    public record QueryCommand(String query, List<String> types, Integer topK, Integer maxChars,
                               String sessionId, String traceId, String agentId) {
    }

    public record MemoryView(Long id, String itemKey, String type, String title, String content, String summary,
                             String trustLevel, String status, LocalDateTime effectiveFrom,
                             LocalDateTime expiresAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record MemoryPage(List<MemoryView> items, long total, int limit, int offset) {
    }

    public record RememberResult(MemoryView memory, boolean created, boolean idempotentReplay, boolean updated) {
    }

    public record ForgetView(Long id, String status, LocalDateTime deletedAt, boolean idempotentReplay) {
    }

    public record QueryHit(Long id, String itemKey, String type, String title, String content, String summary,
                           double score, String trustLevel, LocalDateTime updatedAt) {
    }

    public record QueryResult(List<QueryHit> hits, int candidateCount, int usedChars, int maxChars,
                              boolean truncated) {
    }

    public record ExportView(String schema, LocalDateTime exportedAt, String tenantId, String userId,
                             List<MemoryView> memories) {
    }

    public record AuditView(Long id, String eventType, Long itemId, String decision, String reason,
                            String sessionId, String traceId, String agentId, LocalDateTime createdAt) {
    }
}

package com.enterprise.ai.control.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

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

/**
 * Control-owned long-term memory. A memory belongs to one runtime user and is shared by every
 * Agent that reads personal memory; each row records the Agent and session it came from.
 */
@Service
public class PersonalMemoryService {

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_CONVERSATION = "CONVERSATION";
    public static final String SOURCE_CANDIDATE = "CANDIDATE";
    public static final String ERASE_ALL_CONFIRMATION = "ERASE_ALL_PERSONAL_MEMORIES";

    static final Set<String> ALLOWED_TYPES = Set.of("FACT", "PREFERENCE", "RULE", "NOTE");
    private static final Set<String> SOURCES = Set.of(SOURCE_MANUAL, SOURCE_CONVERSATION, SOURCE_CANDIDATE);
    private static final Set<String> GLOBAL_RECALL_TYPES = Set.of("PREFERENCE", "RULE");
    private static final Set<String> STABLE_PROFILE_KEYS = Set.of(
            semanticSourceRef("profile:name"),
            semanticSourceRef("profile:location"),
            semanticSourceRef("profile:timezone"),
            semanticSourceRef("profile:role"),
            semanticSourceRef("response-language"));
    private static final int MAX_TITLE_CHARS = 256;
    private static final int DEFAULT_MAX_ACTIVE_PER_OWNER = 1_000;
    private static final List<String> ERASE_ALL_RETAINED_DOMAINS = List.of(
            "RUNTIME_SESSION",
            "RUNOPS_TRACE_INTERACTION",
            "BUSINESS_SOURCE_SYSTEM",
            "BACKUP_EXPIRY_AND_RESTORE");

    private final PersonalMemoryMapper memoryMapper;
    private final PersonalMemoryCandidateMapper candidateMapper;
    private final PersonalMemoryAuditMapper auditMapper;
    private final ObjectMapper objectMapper;
    private final PersonalMemoryIndexOutboxService indexOutboxService;
    private final PersonalMemoryKnowledgeQueryClient knowledgeQueryClient;
    private final PersonalMemoryRetrievalTelemetry retrievalTelemetry;
    private final int maxActivePerOwner;

    public PersonalMemoryService(PersonalMemoryMapper memoryMapper,
                                 PersonalMemoryCandidateMapper candidateMapper,
                                 PersonalMemoryAuditMapper auditMapper,
                                 ObjectMapper objectMapper) {
        this(memoryMapper, candidateMapper, auditMapper, objectMapper, null, null, null,
                DEFAULT_MAX_ACTIVE_PER_OWNER);
    }

    @Autowired
    public PersonalMemoryService(PersonalMemoryMapper memoryMapper,
                                 PersonalMemoryCandidateMapper candidateMapper,
                                 PersonalMemoryAuditMapper auditMapper,
                                 ObjectMapper objectMapper,
                                 PersonalMemoryIndexOutboxService indexOutboxService,
                                 PersonalMemoryKnowledgeQueryClient knowledgeQueryClient,
                                 PersonalMemoryRetrievalTelemetry retrievalTelemetry,
                                 @Value("${reachai.context.personal-memory.max-active-items-per-owner:1000}")
                                 int maxActivePerOwner) {
        if (maxActivePerOwner < 1 || maxActivePerOwner > 100_000) {
            throw new IllegalArgumentException(
                    "personal memory max-active-items-per-owner must be between 1 and 100000");
        }
        this.memoryMapper = memoryMapper;
        this.candidateMapper = candidateMapper;
        this.auditMapper = auditMapper;
        this.objectMapper = objectMapper;
        this.indexOutboxService = indexOutboxService;
        this.knowledgeQueryClient = knowledgeQueryClient;
        this.retrievalTelemetry = retrievalTelemetry;
        this.maxActivePerOwner = maxActivePerOwner;
    }

    @Transactional(readOnly = true)
    public MemoryPage list(PersonalMemoryPrincipal principal, String type, String agentId, String keyword,
                           Integer limit, Integer offset) {
        int boundedLimit = Math.max(1, Math.min(limit == null ? 50 : limit, 200));
        int boundedOffset = Math.max(0, offset == null ? 0 : offset);
        String word = trim(keyword);
        var scope = owned(principal)
                .eq(StringUtils.hasText(type), PersonalMemoryEntity::getMemoryType, validatedType(type))
                .eq(StringUtils.hasText(agentId), PersonalMemoryEntity::getSourceAgentId, trim(agentId))
                .and(word != null, q -> q.like(PersonalMemoryEntity::getTitle, word)
                        .or().like(PersonalMemoryEntity::getContent, word))
                .orderByDesc(PersonalMemoryEntity::getUpdatedAt)
                .orderByDesc(PersonalMemoryEntity::getId);
        long total = memoryMapper.selectCount(scope);
        List<MemoryView> items = memoryMapper.selectList(scope.last("LIMIT " + boundedOffset + ", " + boundedLimit))
                .stream().map(this::view).toList();
        return new MemoryPage(items, total, boundedLimit, boundedOffset);
    }

    @Transactional
    public RememberResult remember(PersonalMemoryPrincipal principal, RememberCommand command) {
        if (command == null) {
            throw badRequest("personal memory command is required");
        }
        String content = PersonalMemoryContentPolicy.validate(command.content());
        String type = validatedType(command.type());
        String title = bounded(command.title(), MAX_TITLE_CHARS, "title");
        String semanticKey = bounded(command.semanticKey(), 128, "semanticKey");
        String clientRequestId = bounded(command.clientRequestId(), 128, "clientRequestId");
        String source = command.source() == null ? SOURCE_MANUAL : command.source();
        if (!SOURCES.contains(source)) {
            throw badRequest("unsupported personal memory source");
        }
        String memoryKey = clientRequestId == null
                ? "pm-" + UUID.randomUUID().toString().replace("-", "")
                : "pm-" + sha256(principal.tenantId() + "\n" + principal.runtimeUserId() + "\n" + clientRequestId);
        String contentHash = contentHash(content);

        if (clientRequestId != null) {
            PersonalMemoryEntity replay = byKey(memoryKey);
            if (replay != null) {
                requireOwner(replay, principal, HttpStatus.CONFLICT);
                return new RememberResult(view(replay), false, true, false);
            }
        }
        if (semanticKey != null) {
            PersonalMemoryEntity existing = memoryMapper.selectOne(owned(principal)
                    .eq(PersonalMemoryEntity::getSemanticKey, semanticSourceRef(semanticKey))
                    .last("LIMIT 1 FOR UPDATE"));
            if (existing != null) {
                String previousHash = existing.getContentSha256();
                existing.setMemoryType(type);
                existing.setTitle(title);
                existing.setContent(content);
                existing.setContentSha256(contentHash);
                existing.setTagsJson(tags(command.tags()));
                existing.setExpiresAt(parseDate(command.expiresAt()));
                existing.setSourceType(source);
                existing.setSourceAgentId(trim(command.agentId()));
                existing.setSourceSessionId(trim(command.sessionId()));
                existing.setUpdatedAt(LocalDateTime.now());
                updateOrConflict(existing);
                enqueueUpsert(principal, existing);
                audit("UPDATE", principal, existing.getId(), command.agentId(), command.sessionId(),
                        command.traceId(), "ALLOW", "SEMANTIC_KEY_REPLACE",
                        Map.of("previousContentSha256", previousHash, "contentSha256", contentHash));
                return new RememberResult(view(existing), false, false, true);
            }
        }
        PersonalMemoryEntity duplicate = byContent(principal, contentHash);
        if (duplicate != null) {
            audit("CREATE", principal, duplicate.getId(), command.agentId(), command.sessionId(),
                    command.traceId(), "SKIP", "EXACT_DUPLICATE", Map.of("contentSha256", contentHash));
            return new RememberResult(view(duplicate), false, true, false);
        }
        if (activeCount(principal) >= maxActivePerOwner) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "personal memory active item quota exceeded");
        }

        LocalDateTime now = LocalDateTime.now();
        PersonalMemoryEntity entity = new PersonalMemoryEntity();
        entity.setMemoryKey(memoryKey);
        entity.setTenantId(principal.tenantId());
        entity.setOwnerId(principal.runtimeUserId());
        entity.setMemoryType(type);
        entity.setTitle(title);
        entity.setContent(content);
        entity.setContentSha256(contentHash);
        entity.setSemanticKey(semanticKey == null ? null : semanticSourceRef(semanticKey));
        entity.setTagsJson(tags(command.tags()));
        entity.setSourceType(source);
        entity.setSourceAgentId(trim(command.agentId()));
        entity.setSourceSessionId(trim(command.sessionId()));
        entity.setExpiresAt(parseDate(command.expiresAt()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            memoryMapper.insert(entity);
        } catch (DuplicateKeyException race) {
            // A concurrent write for the same key, content or semantic slot won; return it.
            PersonalMemoryEntity winner = byKey(memoryKey);
            if (winner == null) winner = byContent(principal, contentHash);
            if (winner == null || !owns(winner, principal)) {
                throw race;
            }
            return new RememberResult(view(winner), false, true, false);
        }
        enqueueUpsert(principal, entity);
        audit("CREATE", principal, entity.getId(), command.agentId(), command.sessionId(), command.traceId(),
                "ALLOW", source, Map.of("contentSha256", contentHash));
        return new RememberResult(view(entity), true, false, false);
    }

    @Transactional(readOnly = true)
    public MemoryView get(PersonalMemoryPrincipal principal, Long id) {
        return view(requireOwned(principal, id, false));
    }

    @Transactional
    public MemoryView update(PersonalMemoryPrincipal principal, Long id, UpdateCommand command) {
        if (command == null) {
            throw badRequest("personal memory update is required");
        }
        PersonalMemoryEntity memory = requireOwned(principal, id, true);
        String previousHash = memory.getContentSha256();
        if (command.type() != null) memory.setMemoryType(validatedType(command.type()));
        if (command.title() != null) memory.setTitle(bounded(command.title(), MAX_TITLE_CHARS, "title"));
        if (command.content() != null) {
            memory.setContent(PersonalMemoryContentPolicy.validate(command.content()));
            memory.setContentSha256(contentHash(memory.getContent()));
        }
        if (command.tags() != null) memory.setTagsJson(tags(command.tags()));
        if (command.expiresAt() != null) memory.setExpiresAt(parseDate(command.expiresAt()));
        memory.setUpdatedAt(LocalDateTime.now());
        updateOrConflict(memory);
        enqueueUpsert(principal, memory);
        audit("UPDATE", principal, memory.getId(), null, null, null, "ALLOW", "SELF_UPDATE",
                Map.of("previousContentSha256", previousHash, "contentSha256", memory.getContentSha256()));
        return view(memory);
    }

    @Transactional
    public ForgetView forget(PersonalMemoryPrincipal principal, Long id) {
        PersonalMemoryEntity memory = requireOwned(principal, id, true);
        LocalDateTime now = LocalDateTime.now();
        delete(principal, memory, null);
        audit("DELETE", principal, null, null, null, null, "ALLOW", "SELF_FORGET", Map.of());
        return new ForgetView(id, now);
    }

    /**
     * Erases the owner's whole Control personal-memory domain. Runtime sessions, business source
     * systems, RunOps and backups are separate domains with their own owners and evidence.
     */
    @Transactional
    public EraseAllView eraseAll(PersonalMemoryPrincipal principal, EraseAllCommand command) {
        return eraseAll(principal, command, null);
    }

    /** Entry point for the durable cross-domain erasure workflow. */
    @Transactional
    public EraseAllView eraseAll(PersonalMemoryPrincipal principal, EraseAllCommand command, String correlationId) {
        if (command == null || !ERASE_ALL_CONFIRMATION.equals(command.confirmation())) {
            throw badRequest("explicit personal memory erase-all confirmation is required");
        }
        LocalDateTime now = LocalDateTime.now();
        List<PersonalMemoryEntity> memories =
                memoryMapper.selectOwnedForUpdate(principal.tenantId(), principal.runtimeUserId());
        for (PersonalMemoryEntity memory : memories) {
            delete(principal, memory, correlationId);
        }
        int candidatesErased = candidateMapper.deleteAllForOwner(principal.tenantId(), principal.runtimeUserId());
        auditMapper.delete(Wrappers.<PersonalMemoryAuditEntity>lambdaQuery()
                .eq(PersonalMemoryAuditEntity::getTenantId, principal.tenantId())
                .eq(PersonalMemoryAuditEntity::getOwnerId, principal.runtimeUserId()));
        audit("ERASE_ALL", principal, null, null, null, null, "ALLOW", "SELF_ERASE_ALL", Map.of(
                "memoriesErased", memories.size(),
                "candidatesErased", candidatesErased,
                "retainedDomains", ERASE_ALL_RETAINED_DOMAINS));
        return new EraseAllView("CONTROL_PERSONAL_MEMORY_ERASED", memories.size(), candidatesErased, now,
                memories.isEmpty() && candidatesErased == 0, ERASE_ALL_RETAINED_DOMAINS);
    }

    /** Deletes memories whose expires_at has passed. */
    @Transactional
    public int expireDue(int requestedBatchSize) {
        int batchSize = Math.max(1, Math.min(requestedBatchSize, 500));
        LocalDateTime now = LocalDateTime.now();
        List<PersonalMemoryEntity> due = memoryMapper.selectList(Wrappers.<PersonalMemoryEntity>lambdaQuery()
                .isNotNull(PersonalMemoryEntity::getExpiresAt)
                .le(PersonalMemoryEntity::getExpiresAt, now)
                .orderByAsc(PersonalMemoryEntity::getExpiresAt)
                .orderByAsc(PersonalMemoryEntity::getId)
                .last("LIMIT " + batchSize));
        int expired = 0;
        for (PersonalMemoryEntity candidate : due) {
            PersonalMemoryEntity memory = memoryMapper.selectByIdForUpdate(candidate.getId());
            if (memory == null || memory.getExpiresAt() == null || memory.getExpiresAt().isAfter(now)) continue;
            PersonalMemoryPrincipal owner = new PersonalMemoryPrincipal(
                    memory.getTenantId(), memory.getOwnerId(), null, null, null);
            delete(owner, memory, null);
            auditAs("EXPIRE", owner, null, "SYSTEM", null, null, null, "ALLOW", "RETENTION_EXPIRED", Map.of());
            expired++;
        }
        return expired;
    }

    @Transactional
    public QueryResult query(PersonalMemoryPrincipal principal, QueryCommand command) {
        if (command == null) {
            throw badRequest("personal memory query is required");
        }
        int topK = Math.max(1, Math.min(command.topK() == null ? 8 : command.topK(), 50));
        int maxChars = Math.max(256, Math.min(command.maxChars() == null ? 6_000 : command.maxChars(), 32_000));
        Set<String> types = validatedTypes(command.types());
        LocalDateTime now = LocalDateTime.now();
        PersonalMemoryKnowledgeQueryClient.QueryAttempt knowledgeAttempt = knowledgeQueryClient == null
                ? PersonalMemoryKnowledgeQueryClient.QueryAttempt.off()
                : knowledgeQueryClient.query(principal.tenantId(), principal.runtimeUserId(),
                        command.query(), topK * 3, maxChars);
        List<Long> projectedIds = knowledgeAttempt.successful()
                && knowledgeAttempt.mode().affectsRanking() ? knowledgeAttempt.ids() : List.of();
        // Projection hits are hints only: every id is re-read from the owner's rows here, so a
        // delayed projection delete can never re-inject a forgotten memory.
        List<PersonalMemoryEntity> projectedOrRecent = memoryMapper.selectList(recallable(principal, types, now)
                .in(!projectedIds.isEmpty(), PersonalMemoryEntity::getId, projectedIds)
                .orderByDesc(PersonalMemoryEntity::getUpdatedAt)
                .last("LIMIT 500"));
        // Global rules, preferences and stable profile facts stay recallable even when the
        // projection ranks other rows first.
        List<PersonalMemoryEntity> sticky = memoryMapper.selectList(recallable(principal, types, now)
                .and(q -> q.in(PersonalMemoryEntity::getMemoryType, GLOBAL_RECALL_TYPES)
                        .or().in(PersonalMemoryEntity::getSemanticKey, STABLE_PROFILE_KEYS))
                .orderByDesc(PersonalMemoryEntity::getUpdatedAt)
                .last("LIMIT 200"));
        Map<Long, PersonalMemoryEntity> unique = new LinkedHashMap<>();
        projectedOrRecent.forEach(memory -> unique.put(memory.getId(), memory));
        sticky.forEach(memory -> unique.putIfAbsent(memory.getId(), memory));
        List<PersonalMemoryEntity> candidates = List.copyOf(unique.values());
        Set<Long> projectedIdSet = Set.copyOf(projectedIds);
        String normalizedQuery = normalize(command.query());
        List<ScoredMemory> ranked = candidates.stream()
                .map(memory -> new ScoredMemory(memory, lexicalScore(memory, normalizedQuery)
                        + (projectedIdSet.contains(memory.getId()) ? 4.0d : 0.0d)))
                .filter(scored -> normalizedQuery.isEmpty() || scored.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed()
                        .thenComparing(scored -> scored.memory().getUpdatedAt(),
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
            String content = scored.memory().getContent();
            int chars = content.length();
            if (!selected.isEmpty() && usedChars + chars > maxChars) {
                truncated = true;
                continue;
            }
            if (selected.isEmpty() && chars > maxChars) {
                content = content.substring(0, maxChars);
                chars = content.length();
                truncated = true;
            }
            selected.add(new QueryHit(scored.memory().getId(), scored.memory().getMemoryType(),
                    scored.memory().getTitle(), content, scored.score(), scored.memory().getUpdatedAt()));
            usedChars += chars;
        }
        if (retrievalTelemetry != null) {
            retrievalTelemetry.record(knowledgeAttempt, selected.stream().map(QueryHit::id).toList(),
                    unique.keySet(), topK);
        }
        audit("SEARCH", principal, null, command.agentId(), command.sessionId(), command.traceId(),
                "ALLOW", "RECALL", Map.of(
                        "candidateCount", candidates.size(),
                        "selectedCount", selected.size(),
                        "knowledgeQueryMode", knowledgeAttempt.mode().name(),
                        "knowledgeQueryOutcome", knowledgeAttempt.outcome()));
        return new QueryResult(selected, candidates.size(), usedChars, maxChars, truncated);
    }

    @Transactional(readOnly = true)
    public ExportView export(PersonalMemoryPrincipal principal) {
        List<MemoryView> memories = memoryMapper.selectList(owned(principal)
                        .orderByAsc(PersonalMemoryEntity::getCreatedAt)
                        .orderByAsc(PersonalMemoryEntity::getId))
                .stream().map(this::view).toList();
        return new ExportView("reachai-personal-memory-v2", LocalDateTime.now(), principal.tenantId(),
                principal.runtimeUserId(), memories);
    }

    @Transactional(readOnly = true)
    public List<AuditView> audit(PersonalMemoryPrincipal principal, Integer limit) {
        int boundedLimit = Math.max(1, Math.min(limit == null ? 100 : limit, 500));
        return auditMapper.selectList(Wrappers.<PersonalMemoryAuditEntity>lambdaQuery()
                        .eq(PersonalMemoryAuditEntity::getTenantId, principal.tenantId())
                        .eq(PersonalMemoryAuditEntity::getOwnerId, principal.runtimeUserId())
                        .orderByDesc(PersonalMemoryAuditEntity::getCreatedAt)
                        .orderByDesc(PersonalMemoryAuditEntity::getId)
                        .last("LIMIT " + boundedLimit))
                .stream()
                .map(event -> new AuditView(event.getId(), event.getEventType(), event.getMemoryId(),
                        event.getDecision(), event.getReason(), event.getAgentId(), event.getCreatedAt()))
                .toList();
    }

    /** The owner's memory with exactly this normalized content, if any. */
    PersonalMemoryEntity byContent(PersonalMemoryPrincipal principal, String contentHash) {
        return memoryMapper.selectOne(owned(principal)
                .eq(PersonalMemoryEntity::getContentSha256, contentHash).last("LIMIT 1"));
    }

    /** The owner's memory in this semantic slot, if any. */
    PersonalMemoryEntity bySemanticKey(PersonalMemoryPrincipal principal, String semanticKey) {
        if (!StringUtils.hasText(semanticKey)) return null;
        return memoryMapper.selectOne(owned(principal)
                .eq(PersonalMemoryEntity::getSemanticKey, semanticSourceRef(semanticKey)).last("LIMIT 1"));
    }

    void audit(String eventType, PersonalMemoryPrincipal principal, Long memoryId, String agentId,
               String sessionId, String traceId, String decision, String reason, Map<String, Object> metadata) {
        auditAs(eventType, principal, memoryId, "USER", agentId, sessionId, traceId, decision, reason, metadata);
    }

    private void auditAs(String eventType, PersonalMemoryPrincipal principal, Long memoryId, String actorType,
                         String agentId, String sessionId, String traceId, String decision, String reason,
                         Map<String, Object> metadata) {
        PersonalMemoryAuditEntity event = new PersonalMemoryAuditEntity();
        event.setTenantId(principal.tenantId());
        event.setOwnerId(principal.runtimeUserId());
        event.setMemoryId(memoryId);
        event.setEventType(eventType);
        event.setActorType(actorType);
        event.setAgentId(trim(agentId));
        event.setSessionId(trim(sessionId));
        event.setTraceId(trim(traceId));
        event.setDecision(decision);
        event.setReason(reason);
        event.setMetadataJson(json(metadata));
        event.setCreatedAt(LocalDateTime.now());
        auditMapper.insert(event);
    }

    private void delete(PersonalMemoryPrincipal principal, PersonalMemoryEntity memory, String correlationId) {
        memoryMapper.deleteById(memory.getId());
        candidateMapper.deleteRelatedToMemory(memory.getId());
        // Older audit rows keep the event but lose every link back to the deleted memory.
        auditMapper.update(null, Wrappers.<PersonalMemoryAuditEntity>lambdaUpdate()
                .eq(PersonalMemoryAuditEntity::getMemoryId, memory.getId())
                .set(PersonalMemoryAuditEntity::getMemoryId, null)
                .set(PersonalMemoryAuditEntity::getSessionId, null)
                .set(PersonalMemoryAuditEntity::getTraceId, null)
                .set(PersonalMemoryAuditEntity::getMetadataJson, "{\"erased\":true}"));
        if (indexOutboxService != null) {
            indexOutboxService.enqueueDelete(principal, memory.getId(), correlationId);
        }
    }

    private void updateOrConflict(PersonalMemoryEntity memory) {
        try {
            memoryMapper.updateById(memory);
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "an identical personal memory already exists");
        }
    }

    private PersonalMemoryEntity requireOwned(PersonalMemoryPrincipal principal, Long id, boolean forUpdate) {
        PersonalMemoryEntity memory = id == null ? null
                : forUpdate ? memoryMapper.selectByIdForUpdate(id) : memoryMapper.selectById(id);
        if (memory == null || !owns(memory, principal)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "personal memory not found");
        }
        return memory;
    }

    private static void requireOwner(PersonalMemoryEntity memory, PersonalMemoryPrincipal principal,
                                     HttpStatus status) {
        if (!owns(memory, principal)) {
            throw new ResponseStatusException(status, "idempotency key belongs to another memory");
        }
    }

    private static boolean owns(PersonalMemoryEntity memory, PersonalMemoryPrincipal principal) {
        return principal.tenantId().equals(memory.getTenantId())
                && principal.runtimeUserId().equals(memory.getOwnerId());
    }

    private PersonalMemoryEntity byKey(String memoryKey) {
        return memoryMapper.selectOne(Wrappers.<PersonalMemoryEntity>lambdaQuery()
                .eq(PersonalMemoryEntity::getMemoryKey, memoryKey).last("LIMIT 1"));
    }

    private long activeCount(PersonalMemoryPrincipal principal) {
        return memoryMapper.selectCount(owned(principal));
    }

    private static com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PersonalMemoryEntity> owned(
            PersonalMemoryPrincipal principal) {
        return Wrappers.<PersonalMemoryEntity>lambdaQuery()
                .eq(PersonalMemoryEntity::getTenantId, principal.tenantId())
                .eq(PersonalMemoryEntity::getOwnerId, principal.runtimeUserId());
    }

    private static com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PersonalMemoryEntity> recallable(
            PersonalMemoryPrincipal principal, Set<String> types, LocalDateTime now) {
        return owned(principal)
                .in(!types.isEmpty(), PersonalMemoryEntity::getMemoryType, types)
                .and(q -> q.isNull(PersonalMemoryEntity::getExpiresAt)
                        .or().gt(PersonalMemoryEntity::getExpiresAt, now));
    }

    private void enqueueUpsert(PersonalMemoryPrincipal principal, PersonalMemoryEntity memory) {
        if (indexOutboxService != null) {
            indexOutboxService.enqueueUpsert(principal, memory);
        }
    }

    private MemoryView view(PersonalMemoryEntity entity) {
        return new MemoryView(entity.getId(), entity.getMemoryType(), entity.getTitle(), entity.getContent(),
                readTags(entity.getTagsJson()), entity.getSourceType(), entity.getSourceAgentId(),
                entity.getExpiresAt(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private String tags(List<String> rawTags) {
        if (rawTags == null) return null;
        List<String> tags = rawTags.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(tag -> tag.length() <= 64)
                .distinct()
                .limit(20)
                .toList();
        if (tags.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize personal memory tags", e);
        }
    }

    private List<String> readTags(String tagsJson) {
        if (!StringUtils.hasText(tagsJson)) return List.of();
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize personal memory audit metadata", e);
        }
    }

    static String validatedType(String value) {
        String type = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "NOTE";
        if (!ALLOWED_TYPES.contains(type)) {
            throw badRequest("personal memory type must be FACT, PREFERENCE, RULE, or NOTE");
        }
        return type;
    }

    private static Set<String> validatedTypes(List<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        Set<String> types = new LinkedHashSet<>();
        values.forEach(value -> types.add(validatedType(value)));
        return Set.copyOf(types);
    }

    private static String bounded(String value, int max, String field) {
        String result = trim(value);
        if (result != null && result.length() > max) {
            throw badRequest("personal memory " + field + " exceeds " + max + " characters");
        }
        return result;
    }

    static String contentHash(String content) {
        return sha256(normalize(content));
    }

    static String semanticSourceRef(String key) {
        return "personal-key:" + sha256(key.trim().toLowerCase(Locale.ROOT));
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte valueByte : digest) {
                result.append(String.format("%02x", valueByte));
            }
            return result.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static double lexicalScore(PersonalMemoryEntity memory, String normalizedQuery) {
        if (normalizedQuery.isEmpty()) {
            return 1.0d;
        }
        String title = normalize(memory.getTitle());
        String content = normalize(memory.getContent());
        // Global rules and preferences stay available without lexical overlap. Facts and notes
        // need lexical or profile-intent relevance, so unrelated private data is not injected.
        double score = switch (memory.getMemoryType() == null ? "" : memory.getMemoryType()) {
            case "RULE" -> 3.0d;
            case "PREFERENCE" -> 1.5d;
            default -> 0.0d;
        };
        score += profileIntentScore(memory.getSemanticKey(), normalizedQuery);
        if (title.equals(normalizedQuery)) {
            score += 8;
        } else if (title.contains(normalizedQuery)) {
            score += 4;
        }
        if (content.contains(normalizedQuery)) {
            score += 3;
        }
        for (String token : queryTokens(normalizedQuery)) {
            if (title.contains(token)) score += 1.5;
            if (content.contains(token)) score += 1;
        }
        return score;
    }

    private static double profileIntentScore(String semanticKey, String query) {
        if (!StringUtils.hasText(semanticKey) || !StringUtils.hasText(query)) {
            return 0.0d;
        }
        if (semanticKey.equals(semanticSourceRef("profile:name"))
                && containsAny(query, "名字", "姓名", "称呼", "我是谁", "name", "who am i", "call me")) {
            return 6.0d;
        }
        if (semanticKey.equals(semanticSourceRef("profile:location"))
                && containsAny(query, "住哪", "住在", "哪里", "哪儿", "城市", "地点", "当地", "本地", "附近",
                "天气", "location", "where", "city", "live", "local", "nearby", "weather")) {
            return 6.0d;
        }
        if (semanticKey.equals(semanticSourceRef("profile:timezone"))
                && containsAny(query, "时区", "几点", "当地时间", "timezone", "time zone", "local time")) {
            return 6.0d;
        }
        if (semanticKey.equals(semanticSourceRef("profile:role"))
                && containsAny(query, "岗位", "职位", "角色", "职业", "工作", "job", "role", "position", "career")) {
            return 6.0d;
        }
        if (semanticKey.equals(semanticSourceRef("response-language"))
                && containsAny(query, "语言", "中文", "英文", "英语", "回答", "回复",
                "language", "chinese", "english", "respond", "reply")) {
            return 6.0d;
        }
        return 0.0d;
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) return true;
        }
        return false;
    }

    private static List<String> queryTokens(String query) {
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
        if (date == null) return null;
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

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    static String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private record ScoredMemory(PersonalMemoryEntity memory, double score) {
    }

    /** {@code source} is MANUAL (default), CONVERSATION or CANDIDATE. */
    public record RememberCommand(String type, String semanticKey, String title, String content,
                                  List<String> tags, String expiresAt, String clientRequestId,
                                  String source, String sessionId, String traceId, String agentId) {
    }

    public record UpdateCommand(String type, String title, String content, List<String> tags, String expiresAt) {
    }

    public record EraseAllCommand(String confirmation) {
    }

    public record EraseAllView(String status, int memoriesErased, int candidatesErased,
                               LocalDateTime erasedAt, boolean idempotent, List<String> retainedDomains) {
    }

    public record QueryCommand(String query, List<String> types, Integer topK, Integer maxChars,
                               String sessionId, String traceId, String agentId) {
    }

    public record MemoryView(Long id, String type, String title, String content, List<String> tags,
                             String sourceType, String sourceAgentId, LocalDateTime expiresAt,
                             LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record MemoryPage(List<MemoryView> items, long total, int limit, int offset) {
    }

    public record RememberResult(MemoryView memory, boolean created, boolean idempotentReplay, boolean updated) {
    }

    public record ForgetView(Long id, LocalDateTime deletedAt) {
    }

    public record QueryHit(Long id, String type, String title, String content, double score,
                           LocalDateTime updatedAt) {
    }

    public record QueryResult(List<QueryHit> hits, int candidateCount, int usedChars, int maxChars,
                              boolean truncated) {
    }

    public record ExportView(String schema, LocalDateTime exportedAt, String tenantId, String userId,
                             List<MemoryView> memories) {
    }

    public record AuditView(Long id, String eventType, Long memoryId, String decision, String reason,
                            String agentId, LocalDateTime createdAt) {
    }
}

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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Control-owned long-term memory. A memory belongs to one person and is shared by every Agent and AI
 * tool that reads personal memory. It lives in the personal layer or one project's layer, inside one of
 * the owner's groups; each row records where it came from. The owner manages it by adding, editing and
 * deleting; nothing else is logged about it.
 */
@Service
public class PersonalMemoryService {

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_CONVERSATION = "CONVERSATION";
    public static final String SOURCE_AI_TOOL = "AI_TOOL";
    public static final String ERASE_ALL_CONFIRMATION = "ERASE_ALL_PERSONAL_MEMORIES";

    static final Set<String> ALLOWED_TYPES = Set.of("FACT", "PREFERENCE", "RULE", "NOTE");
    private static final Set<String> SOURCES = Set.of(SOURCE_MANUAL, SOURCE_CONVERSATION, SOURCE_AI_TOOL);
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
    private final PersonalMemoryGroupService groupService;
    private final ObjectMapper objectMapper;
    private final PersonalMemoryIndexOutboxService indexOutboxService;
    private final PersonalMemoryKnowledgeQueryClient knowledgeQueryClient;
    private final PersonalMemoryRetrievalTelemetry retrievalTelemetry;
    private final int maxActivePerOwner;

    public PersonalMemoryService(PersonalMemoryMapper memoryMapper,
                                 PersonalMemoryGroupService groupService,
                                 ObjectMapper objectMapper) {
        this(memoryMapper, groupService, objectMapper, null, null, null, DEFAULT_MAX_ACTIVE_PER_OWNER);
    }

    @Autowired
    public PersonalMemoryService(PersonalMemoryMapper memoryMapper,
                                 PersonalMemoryGroupService groupService,
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
        this.groupService = groupService;
        this.objectMapper = objectMapper;
        this.indexOutboxService = indexOutboxService;
        this.knowledgeQueryClient = knowledgeQueryClient;
        this.retrievalTelemetry = retrievalTelemetry;
        this.maxActivePerOwner = maxActivePerOwner;
    }

    /** {@code scope} is blank (every layer), {@code personal} or {@code project:<code>}. */
    @Transactional(readOnly = true)
    public MemoryPage list(PersonalMemoryPrincipal principal, String scope, Long groupId, String agentId,
                           String keyword, Integer limit, Integer offset) {
        int boundedLimit = Math.max(1, Math.min(limit == null ? 50 : limit, 200));
        int boundedOffset = Math.max(0, offset == null ? 0 : offset);
        PersonalMemoryScope layer = PersonalMemoryScope.parseFilter(scope);
        String word = trim(keyword);
        var query = owned(principal)
                .eq(layer != null, PersonalMemoryEntity::getScopeKey, layer == null ? null : layer.key())
                .eq(groupId != null, PersonalMemoryEntity::getGroupId, groupId)
                .eq(StringUtils.hasText(agentId), PersonalMemoryEntity::getSourceAgentId, trim(agentId))
                .and(word != null, q -> q.like(PersonalMemoryEntity::getTitle, word)
                        .or().like(PersonalMemoryEntity::getContent, word));
        long total = memoryMapper.selectCount(query);
        query.orderByDesc(PersonalMemoryEntity::getUpdatedAt).orderByDesc(PersonalMemoryEntity::getId);
        Map<Long, PersonalMemoryGroupEntity> groups = groupService.groupsById(principal);
        List<MemoryView> items = memoryMapper.selectList(query.last("LIMIT " + boundedOffset + ", " + boundedLimit))
                .stream().map(memory -> view(memory, groups)).toList();
        return new MemoryPage(items, total, boundedLimit, boundedOffset);
    }

    @Transactional
    public RememberResult remember(PersonalMemoryPrincipal principal, RememberCommand command) {
        if (command == null) {
            throw badRequest("personal memory command is required");
        }
        String content = PersonalMemoryContentPolicy.validate(command.content());
        if (StringUtils.hasText(command.type())) validatedType(command.type());
        String title = bounded(command.title(), MAX_TITLE_CHARS, "title");
        String semanticKey = bounded(command.semanticKey(), 128, "semanticKey");
        String clientRequestId = bounded(command.clientRequestId(), 128, "clientRequestId");
        String source = command.source() == null ? SOURCE_MANUAL : command.source();
        if (!SOURCES.contains(source)) {
            throw badRequest("unsupported personal memory source");
        }
        String memoryKey = clientRequestId == null
                ? "pm-" + UUID.randomUUID().toString().replace("-", "")
                : "pm-" + sha256(PersonalMemoryPrincipal.LEGACY_DOMAIN.equals(principal.ownerDomain())
                        ? principal.tenantId() + "\n" + principal.runtimeUserId() + "\n" + clientRequestId
                        : scopedRequestKey(principal, clientRequestId));
        String contentHash = contentHash(content);

        if (clientRequestId != null) {
            PersonalMemoryEntity replay = byKey(memoryKey);
            if (replay != null) {
                requireOwner(replay, principal, HttpStatus.CONFLICT);
                return new RememberResult(view(replay), false, true, false);
            }
        }
        PersonalMemoryGroupEntity group = groupService.target(principal,
                PersonalMemoryScope.of(command.projectCode()), command.groupId(), command.type());
        PersonalMemoryScope scope = PersonalMemoryScope.of(group.getProjectCode());
        String type = PersonalMemoryGroupService.typeFor(group.getKind(), command.type());
        if (semanticKey != null) {
            PersonalMemoryEntity existing = memoryMapper.selectOne(inLayer(principal, scope)
                    .eq(PersonalMemoryEntity::getSemanticKey, semanticSourceRef(semanticKey))
                    .last("LIMIT 1 FOR UPDATE"));
            if (existing != null) {
                existing.setMemoryType(type);
                existing.setGroupId(group.getId());
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
                return new RememberResult(view(existing), false, false, true);
            }
        }
        PersonalMemoryEntity duplicate = byContent(principal, scope, contentHash);
        if (duplicate != null) {
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
        entity.setOwnerDomain(principal.ownerDomain());
        entity.setScopeKey(scope.key());
        entity.setProjectCode(scope.projectCode());
        entity.setGroupId(group.getId());
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
            if (winner == null) winner = byContent(principal, scope, contentHash);
            if (winner == null || !owns(winner, principal)) {
                throw race;
            }
            return new RememberResult(view(winner), false, true, false);
        }
        enqueueUpsert(principal, entity);
        return new RememberResult(view(entity), true, false, false);
    }

    @Transactional(readOnly = true)
    public MemoryView get(PersonalMemoryPrincipal principal, Long id) {
        return view(requireOwned(principal, id, false));
    }

    /** Edits a memory; a new {@code groupId} moves it, possibly into another layer. */
    @Transactional
    public MemoryView update(PersonalMemoryPrincipal principal, Long id, UpdateCommand command) {
        if (command == null) {
            throw badRequest("personal memory update is required");
        }
        PersonalMemoryEntity memory = requireOwned(principal, id, true);
        if (command.type() != null) memory.setMemoryType(validatedType(command.type()));
        if (command.groupId() != null && !command.groupId().equals(memory.getGroupId())) {
            PersonalMemoryGroupEntity group = groupService.target(principal, PersonalMemoryScope.PERSONAL,
                    command.groupId(), null);
            PersonalMemoryScope scope = PersonalMemoryScope.of(group.getProjectCode());
            memory.setGroupId(group.getId());
            memory.setScopeKey(scope.key());
            memory.setProjectCode(scope.projectCode());
            memory.setMemoryType(PersonalMemoryGroupService.typeFor(group.getKind(), memory.getMemoryType()));
        }
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
        return view(memory);
    }

    @Transactional
    public ForgetView forget(PersonalMemoryPrincipal principal, Long id) {
        PersonalMemoryEntity memory = requireOwned(principal, id, true);
        LocalDateTime now = LocalDateTime.now();
        delete(principal, memory, null);
        return new ForgetView(id, now);
    }

    /**
     * Erases the owner's whole Control personal-memory domain, groups included. Runtime sessions,
     * business source systems, RunOps and backups are separate domains with their own owners.
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
                memoryMapper.selectOwnedForUpdate(principal.tenantId(), principal.runtimeUserId(), principal.ownerDomain());
        for (PersonalMemoryEntity memory : memories) {
            delete(principal, memory, correlationId);
        }
        int groupsErased = groupService.deleteAll(principal);
        return new EraseAllView("CONTROL_PERSONAL_MEMORY_ERASED", memories.size(), groupsErased, now,
                memories.isEmpty(), ERASE_ALL_RETAINED_DOMAINS);
    }

    /** Prevent legacy erasure from silently omitting business-scoped records or groups. */
    @Transactional(readOnly = true)
    public boolean hasScopedOwnerData(String tenantId, String userId) {
        return memoryMapper.selectCount(Wrappers.<PersonalMemoryEntity>lambdaQuery()
                .eq(PersonalMemoryEntity::getTenantId, tenantId)
                .eq(PersonalMemoryEntity::getOwnerId, userId)
                .ne(PersonalMemoryEntity::getOwnerDomain, PersonalMemoryPrincipal.LEGACY_DOMAIN)) > 0
                || groupService.hasScopedOwnerGroups(tenantId, userId);
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
                    memory.getTenantId(), memory.getOwnerId(), null, null, null, memory.getOwnerDomain());
            delete(owner, memory, null);
            expired++;
        }
        return expired;
    }

    /**
     * Recall for one AI turn: the personal layer always, plus the project layer when the turn works on
     * that project. Preferences and stable profile facts stay in even without lexical overlap.
     */
    @Transactional(readOnly = true)
    public QueryResult query(PersonalMemoryPrincipal principal, QueryCommand command) {
        if (command == null) {
            throw badRequest("personal memory query is required");
        }
        int topK = Math.max(1, Math.min(command.topK() == null ? 8 : command.topK(), 50));
        int maxChars = Math.max(256, Math.min(command.maxChars() == null ? 6_000 : command.maxChars(), 32_000));
        Set<String> types = validatedTypes(command.types());
        List<String> layers = recallLayers(command.projectCode());
        LocalDateTime now = LocalDateTime.now();
        PersonalMemoryKnowledgeQueryClient.QueryAttempt knowledgeAttempt = knowledgeQueryClient == null
                ? PersonalMemoryKnowledgeQueryClient.QueryAttempt.off()
                : PersonalMemoryPrincipal.LEGACY_DOMAIN.equals(principal.ownerDomain())
                    ? knowledgeQueryClient.query(principal.tenantId(), principal.runtimeUserId(),
                            command.query(), topK * 3, maxChars)
                    : knowledgeQueryClient.query(principal.tenantId(), principal.runtimeUserId(),
                            command.query(), topK * 3, maxChars, principal.ownerDomain());
        List<Long> projectedIds = knowledgeAttempt.successful()
                && knowledgeAttempt.mode().affectsRanking() ? knowledgeAttempt.ids() : List.of();
        // Projection hits are hints only: every id is re-read from the owner's rows here, so a
        // delayed projection delete can never re-inject a forgotten memory.
        List<PersonalMemoryEntity> projectedOrRecent = memoryMapper.selectList(
                recallable(principal, types, layers, now)
                        .in(!projectedIds.isEmpty(), PersonalMemoryEntity::getId, projectedIds)
                        .orderByDesc(PersonalMemoryEntity::getUpdatedAt)
                        .last("LIMIT 500"));
        List<PersonalMemoryEntity> sticky = memoryMapper.selectList(recallable(principal, types, layers, now)
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
                    scored.memory().getTitle(), content, scored.score(), scored.memory().getUpdatedAt(),
                    scored.memory().getProjectCode()));
            usedChars += chars;
        }
        if (retrievalTelemetry != null) {
            retrievalTelemetry.record(knowledgeAttempt, selected.stream().map(QueryHit::id).toList(),
                    unique.keySet(), topK);
        }
        return new QueryResult(selected, candidates.size(), usedChars, maxChars, truncated);
    }

    @Transactional(readOnly = true)
    public ExportView export(PersonalMemoryPrincipal principal) {
        Map<Long, PersonalMemoryGroupEntity> groups = groupService.groupsById(principal);
        List<MemoryView> memories = memoryMapper.selectList(owned(principal)
                        .orderByAsc(PersonalMemoryEntity::getCreatedAt)
                        .orderByAsc(PersonalMemoryEntity::getId))
                .stream().map(memory -> view(memory, groups)).toList();
        return new ExportView("reachai-personal-memory-v4", LocalDateTime.now(), principal.tenantId(),
                principal.runtimeUserId(), principal.ownerDomain(), memories);
    }

    /** The owner's memory in this layer with exactly this normalized content, if any. */
    PersonalMemoryEntity byContent(PersonalMemoryPrincipal principal, PersonalMemoryScope scope, String contentHash) {
        return memoryMapper.selectOne(inLayer(principal, scope)
                .eq(PersonalMemoryEntity::getContentSha256, contentHash).last("LIMIT 1"));
    }

    private void delete(PersonalMemoryPrincipal principal, PersonalMemoryEntity memory, String correlationId) {
        memoryMapper.deleteById(memory.getId());
        if (indexOutboxService != null) {
            indexOutboxService.enqueueDelete(principal, memory.getId(), correlationId);
        }
    }

    private void updateOrConflict(PersonalMemoryEntity memory) {
        try {
            memoryMapper.updateById(memory);
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这一层里已经有一条相同的记忆");
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
                && principal.runtimeUserId().equals(memory.getOwnerId())
                && principal.ownerDomain().equals(memory.getOwnerDomain());
    }

    private PersonalMemoryEntity byKey(String memoryKey) {
        return memoryMapper.selectOne(Wrappers.<PersonalMemoryEntity>lambdaQuery()
                .eq(PersonalMemoryEntity::getMemoryKey, memoryKey).last("LIMIT 1"));
    }

    private long activeCount(PersonalMemoryPrincipal principal) {
        return memoryMapper.selectCount(owned(principal));
    }

    private static List<String> recallLayers(String projectCode) {
        PersonalMemoryScope project = PersonalMemoryScope.of(projectCode);
        return project.personal() ? List.of(PersonalMemoryScope.PERSONAL_KEY)
                : List.of(PersonalMemoryScope.PERSONAL_KEY, project.key());
    }

    private static com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PersonalMemoryEntity> owned(
            PersonalMemoryPrincipal principal) {
        return Wrappers.<PersonalMemoryEntity>lambdaQuery()
                .eq(PersonalMemoryEntity::getTenantId, principal.tenantId())
                .eq(PersonalMemoryEntity::getOwnerId, principal.runtimeUserId())
                .eq(PersonalMemoryEntity::getOwnerDomain, principal.ownerDomain());
    }

    private static com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PersonalMemoryEntity> inLayer(
            PersonalMemoryPrincipal principal, PersonalMemoryScope scope) {
        return owned(principal).eq(PersonalMemoryEntity::getScopeKey, scope.key());
    }

    private static com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PersonalMemoryEntity> recallable(
            PersonalMemoryPrincipal principal, Set<String> types, List<String> layers, LocalDateTime now) {
        return owned(principal)
                .in(PersonalMemoryEntity::getScopeKey, layers)
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
        PersonalMemoryGroupEntity group = entity.getGroupId() == null ? null
                : groupService.groupsById(new PersonalMemoryPrincipal(entity.getTenantId(), entity.getOwnerId(),
                        null, null, null, entity.getOwnerDomain())).get(entity.getGroupId());
        return view(entity, group == null ? Map.of() : Map.of(group.getId(), group));
    }

    private MemoryView view(PersonalMemoryEntity entity, Map<Long, PersonalMemoryGroupEntity> groups) {
        PersonalMemoryGroupEntity group = entity.getGroupId() == null ? null : groups.get(entity.getGroupId());
        String projectCode = entity.getProjectCode();
        String layerName = projectCode == null ? "个人记忆"
                : firstText(group == null ? null : group.getProjectName(), projectCode);
        return new MemoryView(entity.getId(), entity.getMemoryType(), entity.getTitle(), entity.getContent(),
                readTags(entity.getTagsJson()), projectCode == null ? "PERSONAL" : "PROJECT",
                Objects.requireNonNullElse(entity.getScopeKey(), PersonalMemoryScope.PERSONAL_KEY), projectCode,
                layerName, entity.getGroupId(), group == null ? null : group.getName(),
                entity.getSourceType(), entity.getSourceAgentId(),
                entity.getExpiresAt(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static String firstText(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
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

    private static String scopedRequestKey(PersonalMemoryPrincipal principal, String requestId) {
        // Length framing prevents delimiter-bearing external identifiers from aliasing each other.
        return framed(principal.tenantId()) + framed(principal.ownerDomain())
                + framed(principal.runtimeUserId()) + framed(requestId);
    }

    private static String framed(String value) {
        return value.length() + ":" + value;
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

    /**
     * {@code source} is MANUAL (default), CONVERSATION or AI_TOOL. {@code groupId} files it into that
     * group; otherwise {@code projectCode} picks the layer (empty means personal) and the type picks the
     * group.
     */
    public record RememberCommand(String type, String semanticKey, String title, String content,
                                  List<String> tags, String expiresAt, String clientRequestId,
                                  String source, String sessionId, String traceId, String agentId,
                                  String projectCode, Long groupId) {
    }

    public record UpdateCommand(String type, String title, String content, List<String> tags, String expiresAt,
                                Long groupId) {
    }

    public record EraseAllCommand(String confirmation) {
    }

    public record EraseAllView(String status, int memoriesErased, int groupsErased,
                               LocalDateTime erasedAt, boolean idempotent, List<String> retainedDomains) {
    }

    /** {@code projectCode} adds that project's layer to the personal layer. */
    public record QueryCommand(String query, List<String> types, Integer topK, Integer maxChars,
                               String sessionId, String traceId, String agentId, String projectCode) {
    }

    /** {@code layer} is PERSONAL or PROJECT; {@code scope} is {@code personal} or {@code project:<code>}. */
    public record MemoryView(Long id, String type, String title, String content, List<String> tags,
                             String layer, String scope, String projectCode, String layerName,
                             Long groupId, String groupName,
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
                           LocalDateTime updatedAt, String projectCode) {
    }

    public record QueryResult(List<QueryHit> hits, int candidateCount, int usedChars, int maxChars,
                              boolean truncated) {
    }

    public record ExportView(String schema, LocalDateTime exportedAt, String tenantId, String userId,
                             String ownerDomain, List<MemoryView> memories) {
    }
}

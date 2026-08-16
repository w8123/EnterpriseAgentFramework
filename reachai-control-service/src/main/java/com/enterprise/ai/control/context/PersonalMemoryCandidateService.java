package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.ai.control.context.PersonalMemoryCandidateExtractor.ExtractedCandidate;
import com.enterprise.ai.control.context.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.context.PersonalMemoryService.RememberCommand;
import com.enterprise.ai.control.context.PersonalMemoryService.RememberResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Self-service candidate buffer. Passive extraction never writes canonical memory directly. */
@Service
public class PersonalMemoryCandidateService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int DEFAULT_MAX_PENDING_CANDIDATES_PER_OWNER = 500;
    private final ContextMemoryCandidateMapper candidateMapper;
    private final ContextItemMapper itemMapper;
    private final ContextAuditEventMapper auditMapper;
    private final PersonalMemoryService memoryService;
    private final PersonalMemoryCandidateExtractor extractor;
    private final ObjectMapper objectMapper;
    private final int maxPendingCandidatesPerOwner;

    @Autowired
    public PersonalMemoryCandidateService(ContextMemoryCandidateMapper candidateMapper,
                                          ContextItemMapper itemMapper,
                                          ContextAuditEventMapper auditMapper,
                                          PersonalMemoryService memoryService,
                                          PersonalMemoryCandidateExtractor extractor,
                                          ObjectMapper objectMapper,
                                          @Value("${reachai.context.personal-memory.max-pending-candidates-per-owner:500}")
                                          int maxPendingCandidatesPerOwner) {
        this.candidateMapper = candidateMapper;
        this.itemMapper = itemMapper;
        this.auditMapper = auditMapper;
        this.memoryService = memoryService;
        this.extractor = extractor;
        this.objectMapper = objectMapper;
        if (maxPendingCandidatesPerOwner < 1 || maxPendingCandidatesPerOwner > 100_000) {
            throw new IllegalArgumentException(
                    "personal memory max-pending-candidates-per-owner must be between 1 and 100000");
        }
        this.maxPendingCandidatesPerOwner = maxPendingCandidatesPerOwner;
    }

    public PersonalMemoryCandidateService(ContextMemoryCandidateMapper candidateMapper,
                                          ContextItemMapper itemMapper,
                                          ContextAuditEventMapper auditMapper,
                                          PersonalMemoryService memoryService,
                                          PersonalMemoryCandidateExtractor extractor,
                                          ObjectMapper objectMapper) {
        this(candidateMapper, itemMapper, auditMapper, memoryService, extractor, objectMapper,
                DEFAULT_MAX_PENDING_CANDIDATES_PER_OWNER);
    }

    @Transactional(readOnly = true)
    public CandidatePage list(PersonalMemoryPrincipal principal, String status, Integer limit, Integer offset) {
        String requestedStatus = defaultUpper(status, "PENDING");
        if (!List.of("PENDING", "APPROVED", "REJECTED", "EXPIRED").contains(requestedStatus)) {
            throw badRequest("unsupported personal memory candidate status");
        }
        int boundedLimit = Math.max(1, Math.min(limit == null ? DEFAULT_LIMIT : limit, 200));
        int boundedOffset = Math.max(0, Math.min(offset == null ? 0 : offset, 100_000));
        var scope = Wrappers.<ContextMemoryCandidateEntity>lambdaQuery()
                .eq(ContextMemoryCandidateEntity::getTenantId, principal.tenantId())
                .eq(ContextMemoryCandidateEntity::getUserId, principal.runtimeUserId())
                .eq(ContextMemoryCandidateEntity::getMemoryLane, "RUNTIME_USER")
                .eq(ContextMemoryCandidateEntity::getVisibility, "PRIVATE")
                .eq(ContextMemoryCandidateEntity::getStatus, requestedStatus)
                .orderByDesc(ContextMemoryCandidateEntity::getLastSeenAt)
                .orderByDesc(ContextMemoryCandidateEntity::getId);
        long total = candidateMapper.selectCount(scope);
        List<CandidateView> items = candidateMapper.selectList(scope.last("LIMIT " + boundedOffset + ", " + boundedLimit))
                .stream().map(this::view).toList();
        return new CandidatePage(items, total, boundedLimit, boundedOffset);
    }

    /** Called asynchronously after a successfully completed trusted conversation turn. */
    @Transactional
    public ObservationResult observe(PersonalMemoryPrincipal principal, ObservationCommand command) {
        if (command == null || explicitlyDisabled(command.contributeMemory())) {
            return new ObservationResult(0, 0, 0, 0, 0, 0);
        }
        String message = trim(command.userMessage());
        if (!StringUtils.hasText(message)) {
            return new ObservationResult(0, 0, 0, 0, 0, 0);
        }
        List<ExtractedCandidate> extracted = extractor.extract(message);
        int created = 0;
        int merged = 0;
        int exactExisting = 0;
        int conflicts = 0;
        int remembered = 0;
        int quotaSkipped = 0;
        for (ExtractedCandidate value : extracted) {
            CandidateWriteResult result = writeCandidate(principal, value, command);
            created += result.created() ? 1 : 0;
            merged += result.merged() ? 1 : 0;
            exactExisting += result.exactExisting() ? 1 : 0;
            conflicts += result.conflict() ? 1 : 0;
            remembered += result.remembered() ? 1 : 0;
            quotaSkipped += result.quotaSkipped() ? 1 : 0;
        }
        return new ObservationResult(created, merged, exactExisting, conflicts, remembered, quotaSkipped);
    }

    @Transactional
    public ReviewResult approve(PersonalMemoryPrincipal principal, Long id, ReviewCommand command) {
        ContextMemoryCandidateEntity candidate = requireOwnedPending(principal, id);
        String content = command != null && command.content() != null
                ? PersonalMemoryContentPolicy.validate(command.content())
                : PersonalMemoryContentPolicy.validate(candidate.getContent());
        String type = command != null && command.type() != null ? command.type() : candidate.getCandidateType();
        String title = command != null && command.title() != null ? command.title() : candidate.getTitle();
        String semanticKey = command != null && command.semanticKey() != null
                ? command.semanticKey() : candidate.getSemanticKey();
        RememberResult remembered = memoryService.remember(principal,
                new RememberCommand(type, semanticKey, title, content, candidate.getSummary(),
                        List.of("candidate-confirmed"), null, "candidate:" + candidate.getCandidateKey(),
                        candidate.getSessionId(), candidate.getTraceId(), candidate.getAgentId()));
        candidate.setStatus("APPROVED");
        candidate.setReviewedBy(principal.runtimeUserId());
        candidate.setReviewedAt(LocalDateTime.now());
        candidate.setReviewReason(bounded(command == null ? null : command.reason(), 512, "review reason"));
        candidate.setApprovedItemId(remembered.memory().id());
        candidate.setDedupeKey(null);
        candidate.setUpdatedAt(candidate.getReviewedAt());
        candidateMapper.updateById(candidate);
        audit("CANDIDATE_APPROVE", principal, candidate, remembered.memory().id(), "ALLOW",
                candidate.getConflictItemId() == null ? "SELF_CONFIRM" : "SELF_CONFIRM_REPLACEMENT");
        return new ReviewResult(view(candidate), remembered);
    }

    @Transactional
    public CandidateView reject(PersonalMemoryPrincipal principal, Long id, ReviewCommand command) {
        ContextMemoryCandidateEntity candidate = requireOwnedPending(principal, id);
        candidate.setStatus("REJECTED");
        candidate.setReviewedBy(principal.runtimeUserId());
        candidate.setReviewedAt(LocalDateTime.now());
        candidate.setReviewReason(bounded(command == null ? null : command.reason(), 512, "review reason"));
        candidate.setDedupeKey(null);
        candidate.setUpdatedAt(candidate.getReviewedAt());
        candidateMapper.updateById(candidate);
        audit("CANDIDATE_REJECT", principal, candidate, null, "ALLOW", "SELF_REJECT");
        return view(candidate);
    }

    private CandidateWriteResult writeCandidate(PersonalMemoryPrincipal principal,
                                                ExtractedCandidate value,
                                                ObservationCommand command) {
        String content = PersonalMemoryContentPolicy.validate(value.content());
        String contentHash = sha256(normalize(content));
        if (value.explicit()) {
            memoryService.remember(principal, new RememberCommand(
                    value.type(), value.semanticKey(), value.title(), content, null,
                    List.of("conversation-explicit"), null,
                    "turn-explicit:" + sha256(principal.tenantId() + "\n" + principal.runtimeUserId()
                            + "\n" + firstText(trim(command.traceId()), trim(command.sessionId()))
                            + "\n" + contentHash),
                    command.sessionId(), command.traceId(), command.agentId()));
            return new CandidateWriteResult(false, false, false, false, true, false);
        }
        ContextNamespaceEntity namespace = memoryService.ensureNamespace(principal);
        memoryService.lockNamespace(namespace);
        ContextItemEntity exact = itemMapper.selectOne(Wrappers.<ContextItemEntity>lambdaQuery()
                .eq(ContextItemEntity::getNamespaceId, namespace.getId())
                .eq(ContextItemEntity::getMemoryLane, "RUNTIME_USER")
                .eq(ContextItemEntity::getItemType, value.type())
                .eq(ContextItemEntity::getContent, content)
                .eq(ContextItemEntity::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (exact != null) {
            audit("CANDIDATE_CREATE", principal, null, exact.getId(), "SKIP", "EXACT_ACTIVE_MEMORY");
            return new CandidateWriteResult(false, false, true, false, false, false);
        }

        String dedupeKey = "pmc:" + sha256(principal.tenantId() + "\n" + principal.runtimeUserId()
                + "\n" + value.type() + "\n" + contentHash);
        ContextMemoryCandidateEntity pending = candidateMapper.selectOne(
                Wrappers.<ContextMemoryCandidateEntity>lambdaQuery()
                        .eq(ContextMemoryCandidateEntity::getDedupeKey, dedupeKey)
                        .eq(ContextMemoryCandidateEntity::getStatus, "PENDING")
                        .last("LIMIT 1"));
        if (pending != null) {
            pending.setOccurrenceCount(Math.max(1, pending.getOccurrenceCount() == null ? 1 : pending.getOccurrenceCount()) + 1);
            pending.setLastSeenAt(LocalDateTime.now());
            pending.setSessionId(firstText(trim(command.sessionId()), pending.getSessionId()));
            pending.setTraceId(firstText(trim(command.traceId()), pending.getTraceId()));
            pending.setUpdatedAt(pending.getLastSeenAt());
            candidateMapper.updateById(pending);
            audit("CANDIDATE_MERGE", principal, pending, null, "SKIP", "PENDING_DUPLICATE");
            return new CandidateWriteResult(
                    false, true, false, pending.getConflictItemId() != null, false, false);
        }

        if (pendingCandidateCount(principal) >= maxPendingCandidatesPerOwner) {
            ContextMemoryCandidateEntity auditContext = new ContextMemoryCandidateEntity();
            auditContext.setNamespaceId(namespace.getId());
            auditContext.setSessionId(trim(command.sessionId()));
            auditContext.setTraceId(trim(command.traceId()));
            auditContext.setAgentId(trim(command.agentId()));
            audit("CANDIDATE_CREATE", principal, auditContext, null,
                    "SKIP", "OWNER_PENDING_CANDIDATE_QUOTA");
            return new CandidateWriteResult(false, false, false, false, false, true);
        }

        ContextItemEntity conflict = semanticConflict(namespace.getId(), value.semanticKey(), content);
        ContextMemoryCandidateEntity entity = new ContextMemoryCandidateEntity();
        entity.setCandidateKey("ctx-candidate-pm-" + UUID.randomUUID().toString().replace("-", ""));
        entity.setTenantId(principal.tenantId());
        entity.setNamespaceId(namespace.getId());
        entity.setNamespaceKey(namespace.getNamespaceKey());
        entity.setMemoryLane("RUNTIME_USER");
        entity.setCandidateType(value.type());
        entity.setTitle(value.title());
        entity.setContent(content);
        entity.setContentSha256(contentHash);
        entity.setDedupeKey(dedupeKey);
        entity.setSemanticKey(trim(value.semanticKey()));
        entity.setReason(value.reason());
        entity.setSourceType("USER_MESSAGE");
        entity.setSourceRef("conversation-turn");
        entity.setTraceId(trim(command.traceId()));
        entity.setSessionId(trim(command.sessionId()));
        entity.setUserId(principal.runtimeUserId());
        entity.setAgentId(trim(command.agentId()));
        entity.setOrigin(trim(command.origin()));
        entity.setConfidence(value.confidence());
        entity.setTrustLevel("LOW");
        entity.setVisibility("PRIVATE");
        entity.setStatus("PENDING");
        entity.setProposedBy("MEMORY_EXTRACTOR");
        entity.setConflictItemId(conflict == null ? null : conflict.getId());
        entity.setConflictType(conflict == null ? null : "REPLACEMENT");
        entity.setOccurrenceCount(1);
        entity.setLastSeenAt(LocalDateTime.now());
        entity.setExtractionVersion(PersonalMemoryCandidateExtractor.VERSION);
        entity.setMetadataJson(metadata(value.explicit(), command.assistantAnswer()));
        entity.setExpiresAt(LocalDateTime.now().plusDays(30));
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(entity.getCreatedAt());
        try {
            candidateMapper.insert(entity);
        } catch (DuplicateKeyException race) {
            ContextMemoryCandidateEntity raced = candidateMapper.selectOne(
                    Wrappers.<ContextMemoryCandidateEntity>lambdaQuery()
                            .eq(ContextMemoryCandidateEntity::getDedupeKey, dedupeKey).last("LIMIT 1"));
            if (raced == null) {
                throw race;
            }
            raced.setOccurrenceCount(Math.max(1, raced.getOccurrenceCount() == null ? 1 : raced.getOccurrenceCount()) + 1);
            raced.setLastSeenAt(LocalDateTime.now());
            candidateMapper.updateById(raced);
            return new CandidateWriteResult(
                    false, true, false, raced.getConflictItemId() != null, false, false);
        }
        audit("CANDIDATE_CREATE", principal, entity, null, "ALLOW",
                conflict == null ? "PASSIVE_EXTRACTION" : "PASSIVE_EXTRACTION_CONFLICT");
        return new CandidateWriteResult(true, false, false, conflict != null, false, false);
    }

    private long pendingCandidateCount(PersonalMemoryPrincipal principal) {
        LocalDateTime now = LocalDateTime.now();
        return candidateMapper.selectCount(Wrappers.<ContextMemoryCandidateEntity>lambdaQuery()
                .eq(ContextMemoryCandidateEntity::getTenantId, principal.tenantId())
                .eq(ContextMemoryCandidateEntity::getUserId, principal.runtimeUserId())
                .eq(ContextMemoryCandidateEntity::getMemoryLane, "RUNTIME_USER")
                .eq(ContextMemoryCandidateEntity::getVisibility, "PRIVATE")
                .eq(ContextMemoryCandidateEntity::getStatus, "PENDING")
                .and(q -> q.isNull(ContextMemoryCandidateEntity::getExpiresAt)
                        .or().gt(ContextMemoryCandidateEntity::getExpiresAt, now)));
    }

    private ContextItemEntity semanticConflict(Long namespaceId, String semanticKey, String content) {
        if (!StringUtils.hasText(semanticKey)) {
            return null;
        }
        ContextItemEntity item = itemMapper.selectOne(Wrappers.<ContextItemEntity>lambdaQuery()
                .eq(ContextItemEntity::getNamespaceId, namespaceId)
                .eq(ContextItemEntity::getMemoryLane, "RUNTIME_USER")
                .eq(ContextItemEntity::getSourceRef, semanticSourceRef(semanticKey))
                .eq(ContextItemEntity::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        return item != null && !normalize(item.getContent()).equals(normalize(content)) ? item : null;
    }

    private ContextMemoryCandidateEntity requireOwnedPending(PersonalMemoryPrincipal principal, Long id) {
        ContextMemoryCandidateEntity entity = id == null ? null : candidateMapper.selectByIdForUpdate(id);
        if (entity == null || !principal.tenantId().equals(entity.getTenantId())
                || !principal.runtimeUserId().equals(entity.getUserId())
                || !"RUNTIME_USER".equals(entity.getMemoryLane())
                || !"PRIVATE".equals(entity.getVisibility())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "personal memory candidate not found");
        }
        if (!"PENDING".equals(entity.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "personal memory candidate is not pending");
        }
        return entity;
    }

    private void audit(String eventType, PersonalMemoryPrincipal principal,
                       ContextMemoryCandidateEntity candidate, Long itemId,
                       String decision, String reason) {
        ContextAuditEventEntity event = new ContextAuditEventEntity();
        event.setEventType(eventType);
        event.setItemId(itemId);
        event.setNamespaceId(candidate == null ? null : candidate.getNamespaceId());
        event.setActorType("RUNTIME_USER");
        event.setActorId(principal.runtimeUserId());
        event.setTenantId(principal.tenantId());
        event.setAgentId(candidate == null ? null : candidate.getAgentId());
        event.setSessionId(candidate == null ? null : candidate.getSessionId());
        event.setTraceId(candidate == null ? null : candidate.getTraceId());
        event.setDecision(decision);
        event.setReason(reason);
        event.setMetadataJson(candidateMetadata(candidate));
        event.setCreatedAt(LocalDateTime.now());
        auditMapper.insert(event);
    }

    private String candidateMetadata(ContextMemoryCandidateEntity candidate) {
        if (candidate == null) {
            return "{}";
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("candidateKey", candidate.getCandidateKey());
        value.put("contentSha256", candidate.getContentSha256());
        value.put("semanticKey", candidate.getSemanticKey());
        value.put("conflictItemId", candidate.getConflictItemId());
        value.values().removeIf(java.util.Objects::isNull);
        return json(value);
    }

    private String metadata(boolean explicit, String assistantAnswer) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema", "reachai-personal-memory-candidate-v1");
        value.put("explicitSignal", explicit);
        if (StringUtils.hasText(assistantAnswer)) {
            value.put("assistantAnswerSha256", sha256(assistantAnswer));
        }
        return json(value);
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize personal memory candidate metadata", ex);
        }
    }

    private CandidateView view(ContextMemoryCandidateEntity entity) {
        return new CandidateView(entity.getId(), entity.getCandidateKey(), entity.getCandidateType(),
                entity.getSemanticKey(), entity.getTitle(), entity.getContent(), entity.getReason(),
                entity.getConfidence(), entity.getStatus(), entity.getConflictItemId(), entity.getConflictType(),
                entity.getOccurrenceCount(), entity.getSessionId(), entity.getTraceId(), entity.getAgentId(),
                entity.getExpiresAt(), entity.getCreatedAt(), entity.getLastSeenAt(), entity.getReviewedAt(),
                entity.getReviewReason(), entity.getApprovedItemId());
    }

    private static boolean explicitlyDisabled(Boolean value) {
        return Boolean.FALSE.equals(value);
    }

    private static String semanticSourceRef(String key) {
        return "personal-key:" + sha256(key.toLowerCase(Locale.ROOT));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String bounded(String value, int max, String field) {
        String result = trim(value);
        if (result != null && result.length() > max) {
            throw badRequest("personal memory " + field + " exceeds " + max + " characters");
        }
        return result;
    }

    private static String firstText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    private static String defaultUpper(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : fallback;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record CandidateWriteResult(boolean created, boolean merged, boolean exactExisting,
                                        boolean conflict, boolean remembered, boolean quotaSkipped) {
    }

    public record ObservationCommand(String userMessage, String assistantAnswer, String sessionId,
                                     String traceId, String agentId, String origin,
                                     Boolean contributeMemory) {
    }

    public record ObservationResult(int created, int merged, int exactExisting, int conflicts,
                                    int remembered, int quotaSkipped) {
    }

    public record ReviewCommand(String type, String semanticKey, String title, String content, String reason) {
    }

    public record CandidateView(Long id, String candidateKey, String type, String semanticKey,
                                String title, String content, String reason, BigDecimal confidence,
                                String status, Long conflictItemId, String conflictType,
                                Integer occurrenceCount, String sessionId, String traceId, String agentId,
                                LocalDateTime expiresAt, LocalDateTime createdAt, LocalDateTime lastSeenAt,
                                LocalDateTime reviewedAt, String reviewReason, Long approvedItemId) {
    }

    public record CandidatePage(List<CandidateView> items, long total, int limit, int offset) {
    }

    public record ReviewResult(CandidateView candidate, RememberResult memoryResult) {
    }
}

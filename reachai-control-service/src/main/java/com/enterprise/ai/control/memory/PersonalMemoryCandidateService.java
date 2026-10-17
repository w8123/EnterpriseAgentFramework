package com.enterprise.ai.control.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.memory.PersonalMemoryCandidateExtractor.ExtractedCandidate;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryService.RememberCommand;
import com.enterprise.ai.control.memory.PersonalMemoryService.RememberResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.enterprise.ai.control.memory.PersonalMemoryService.contentHash;
import static com.enterprise.ai.control.memory.PersonalMemoryService.sha256;
import static com.enterprise.ai.control.memory.PersonalMemoryService.trim;

/** Candidates the extractor proposed. Passive extraction never writes a memory directly. */
@Service
public class PersonalMemoryCandidateService {

    private static final int DEFAULT_MAX_PENDING_PER_OWNER = 500;
    private static final List<String> STATUSES = List.of("PENDING", "APPROVED", "REJECTED");

    private final PersonalMemoryCandidateMapper candidateMapper;
    private final PersonalMemoryService memoryService;
    private final PersonalMemoryCandidateExtractor extractor;
    private final int maxPendingPerOwner;

    @Autowired
    public PersonalMemoryCandidateService(PersonalMemoryCandidateMapper candidateMapper,
                                          PersonalMemoryService memoryService,
                                          PersonalMemoryCandidateExtractor extractor,
                                          @Value("${reachai.context.personal-memory.max-pending-candidates-per-owner:500}")
                                          int maxPendingPerOwner) {
        if (maxPendingPerOwner < 1 || maxPendingPerOwner > 100_000) {
            throw new IllegalArgumentException(
                    "personal memory max-pending-candidates-per-owner must be between 1 and 100000");
        }
        this.candidateMapper = candidateMapper;
        this.memoryService = memoryService;
        this.extractor = extractor;
        this.maxPendingPerOwner = maxPendingPerOwner;
    }

    public PersonalMemoryCandidateService(PersonalMemoryCandidateMapper candidateMapper,
                                          PersonalMemoryService memoryService,
                                          PersonalMemoryCandidateExtractor extractor) {
        this(candidateMapper, memoryService, extractor, DEFAULT_MAX_PENDING_PER_OWNER);
    }

    @Transactional(readOnly = true)
    public CandidatePage list(PersonalMemoryPrincipal principal, String status, Integer limit, Integer offset) {
        String requestedStatus = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "PENDING";
        if (!STATUSES.contains(requestedStatus)) {
            throw badRequest("unsupported personal memory candidate status");
        }
        int boundedLimit = Math.max(1, Math.min(limit == null ? 50 : limit, 200));
        int boundedOffset = Math.max(0, Math.min(offset == null ? 0 : offset, 100_000));
        var scope = owned(principal)
                .eq(PersonalMemoryCandidateEntity::getStatus, requestedStatus)
                .orderByDesc(PersonalMemoryCandidateEntity::getLastSeenAt)
                .orderByDesc(PersonalMemoryCandidateEntity::getId);
        long total = candidateMapper.selectCount(scope);
        List<CandidateView> items = candidateMapper.selectList(scope.last("LIMIT " + boundedOffset + ", " + boundedLimit))
                .stream().map(this::view).toList();
        return new CandidatePage(items, total, boundedLimit, boundedOffset);
    }

    /** Runs after a successfully completed trusted conversation turn. */
    @Transactional
    public ObservationResult observe(PersonalMemoryPrincipal principal, ObservationCommand command) {
        String message = command == null ? null : trim(command.userMessage());
        if (message == null) {
            return new ObservationResult(0, 0, 0, 0, 0, 0);
        }
        int created = 0;
        int merged = 0;
        int exactExisting = 0;
        int conflicts = 0;
        int remembered = 0;
        int quotaSkipped = 0;
        for (ExtractedCandidate value : extractor.extract(message)) {
            Outcome outcome = write(principal, value, command);
            switch (outcome) {
                case CREATED -> created++;
                case CREATED_CONFLICT -> { created++; conflicts++; }
                case MERGED -> merged++;
                case MERGED_CONFLICT -> { merged++; conflicts++; }
                case EXACT_EXISTING -> exactExisting++;
                case REMEMBERED -> remembered++;
                case QUOTA_SKIPPED -> quotaSkipped++;
            }
        }
        return new ObservationResult(created, merged, exactExisting, conflicts, remembered, quotaSkipped);
    }

    @Transactional
    public ReviewResult approve(PersonalMemoryPrincipal principal, Long id, ReviewCommand command) {
        PersonalMemoryCandidateEntity candidate = requireOwnedPending(principal, id);
        String content = PersonalMemoryContentPolicy.validate(
                command != null && command.content() != null ? command.content() : candidate.getContent());
        String type = command != null && command.type() != null ? command.type() : candidate.getMemoryType();
        String title = command != null && command.title() != null ? command.title() : candidate.getTitle();
        RememberResult remembered = memoryService.remember(principal, new RememberCommand(
                type, candidate.getSemanticKey(), title, content, null, null,
                "candidate:" + candidate.getCandidateKey(), PersonalMemoryService.SOURCE_CANDIDATE,
                candidate.getSourceSessionId(), candidate.getSourceTraceId(), candidate.getSourceAgentId()));
        candidate.setStatus("APPROVED");
        candidate.setReviewedAt(LocalDateTime.now());
        candidate.setApprovedMemoryId(remembered.memory().id());
        candidate.setDedupeKey(null);
        candidate.setUpdatedAt(candidate.getReviewedAt());
        candidateMapper.updateById(candidate);
        memoryService.audit("CANDIDATE_APPROVE", principal, remembered.memory().id(), candidate.getSourceAgentId(),
                null, null, "ALLOW", candidate.getConflictMemoryId() == null ? "SELF_CONFIRM" : "SELF_CONFIRM_REPLACEMENT",
                Map.of());
        return new ReviewResult(view(candidate), remembered);
    }

    @Transactional
    public CandidateView reject(PersonalMemoryPrincipal principal, Long id) {
        PersonalMemoryCandidateEntity candidate = requireOwnedPending(principal, id);
        candidate.setStatus("REJECTED");
        candidate.setReviewedAt(LocalDateTime.now());
        candidate.setDedupeKey(null);
        candidate.setUpdatedAt(candidate.getReviewedAt());
        candidateMapper.updateById(candidate);
        memoryService.audit("CANDIDATE_REJECT", principal, null, candidate.getSourceAgentId(), null, null,
                "ALLOW", "SELF_REJECT", Map.of());
        return view(candidate);
    }

    private Outcome write(PersonalMemoryPrincipal principal, ExtractedCandidate value, ObservationCommand command) {
        String content = PersonalMemoryContentPolicy.validate(value.content());
        String hash = contentHash(content);
        if (value.explicit()) {
            // "记住……" is the user's own confirmation, so it becomes a memory at once.
            memoryService.remember(principal, new RememberCommand(
                    value.type(), value.semanticKey(), value.title(), content, null, null,
                    "turn:" + sha256(firstText(trim(command.traceId()), trim(command.sessionId())) + "\n" + hash),
                    PersonalMemoryService.SOURCE_CONVERSATION,
                    command.sessionId(), command.traceId(), command.agentId()));
            return Outcome.REMEMBERED;
        }
        if (memoryService.byContent(principal, hash) != null) {
            return Outcome.EXACT_EXISTING;
        }
        String dedupeKey = "pmc:" + sha256(principal.tenantId() + "\n" + principal.runtimeUserId() + "\n" + hash);
        PersonalMemoryCandidateEntity pending = candidateMapper.selectOne(Wrappers.<PersonalMemoryCandidateEntity>lambdaQuery()
                .eq(PersonalMemoryCandidateEntity::getDedupeKey, dedupeKey).last("LIMIT 1"));
        if (pending != null) {
            seenAgain(pending, command);
            return pending.getConflictMemoryId() == null ? Outcome.MERGED : Outcome.MERGED_CONFLICT;
        }
        LocalDateTime now = LocalDateTime.now();
        long pendingCount = candidateMapper.selectCount(owned(principal)
                .eq(PersonalMemoryCandidateEntity::getStatus, "PENDING")
                .and(q -> q.isNull(PersonalMemoryCandidateEntity::getExpiresAt)
                        .or().gt(PersonalMemoryCandidateEntity::getExpiresAt, now)));
        if (pendingCount >= maxPendingPerOwner) {
            memoryService.audit("CANDIDATE_CREATE", principal, null, command.agentId(), command.sessionId(),
                    command.traceId(), "SKIP", "OWNER_PENDING_CANDIDATE_QUOTA", Map.of());
            return Outcome.QUOTA_SKIPPED;
        }
        PersonalMemoryEntity existing = memoryService.bySemanticKey(principal, value.semanticKey());
        PersonalMemoryEntity conflict = existing != null && !hash.equals(existing.getContentSha256()) ? existing : null;

        PersonalMemoryCandidateEntity entity = new PersonalMemoryCandidateEntity();
        entity.setCandidateKey("pmc-" + UUID.randomUUID().toString().replace("-", ""));
        entity.setTenantId(principal.tenantId());
        entity.setOwnerId(principal.runtimeUserId());
        entity.setMemoryType(value.type());
        entity.setTitle(value.title());
        entity.setContent(content);
        entity.setContentSha256(hash);
        entity.setDedupeKey(dedupeKey);
        entity.setSemanticKey(trim(value.semanticKey()));
        entity.setReason(value.reason());
        entity.setConfidence(value.confidence() == null ? BigDecimal.valueOf(0.7) : value.confidence());
        entity.setStatus("PENDING");
        entity.setSourceAgentId(trim(command.agentId()));
        entity.setSourceSessionId(trim(command.sessionId()));
        entity.setSourceTraceId(trim(command.traceId()));
        entity.setConflictMemoryId(conflict == null ? null : conflict.getId());
        entity.setOccurrenceCount(1);
        entity.setLastSeenAt(now);
        entity.setExtractionVersion(PersonalMemoryCandidateExtractor.VERSION);
        entity.setExpiresAt(now.plusDays(30));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            candidateMapper.insert(entity);
        } catch (DuplicateKeyException race) {
            PersonalMemoryCandidateEntity raced = candidateMapper.selectOne(Wrappers.<PersonalMemoryCandidateEntity>lambdaQuery()
                    .eq(PersonalMemoryCandidateEntity::getDedupeKey, dedupeKey).last("LIMIT 1"));
            if (raced == null) {
                throw race;
            }
            seenAgain(raced, command);
            return raced.getConflictMemoryId() == null ? Outcome.MERGED : Outcome.MERGED_CONFLICT;
        }
        memoryService.audit("CANDIDATE_CREATE", principal, null, command.agentId(), command.sessionId(),
                command.traceId(), "ALLOW", conflict == null ? "PASSIVE_EXTRACTION" : "PASSIVE_EXTRACTION_CONFLICT",
                Map.of("contentSha256", hash));
        return conflict == null ? Outcome.CREATED : Outcome.CREATED_CONFLICT;
    }

    private void seenAgain(PersonalMemoryCandidateEntity candidate, ObservationCommand command) {
        candidate.setOccurrenceCount(Math.max(1, candidate.getOccurrenceCount() == null ? 1 : candidate.getOccurrenceCount()) + 1);
        candidate.setLastSeenAt(LocalDateTime.now());
        candidate.setSourceSessionId(firstText(trim(command.sessionId()), candidate.getSourceSessionId()));
        candidate.setSourceTraceId(firstText(trim(command.traceId()), candidate.getSourceTraceId()));
        candidate.setUpdatedAt(candidate.getLastSeenAt());
        candidateMapper.updateById(candidate);
    }

    private PersonalMemoryCandidateEntity requireOwnedPending(PersonalMemoryPrincipal principal, Long id) {
        PersonalMemoryCandidateEntity entity = id == null ? null : candidateMapper.selectByIdForUpdate(id);
        if (entity == null || !principal.tenantId().equals(entity.getTenantId())
                || !principal.runtimeUserId().equals(entity.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "personal memory candidate not found");
        }
        if (!"PENDING".equals(entity.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "personal memory candidate is not pending");
        }
        return entity;
    }

    private static LambdaQueryWrapper<PersonalMemoryCandidateEntity> owned(PersonalMemoryPrincipal principal) {
        return Wrappers.<PersonalMemoryCandidateEntity>lambdaQuery()
                .eq(PersonalMemoryCandidateEntity::getTenantId, principal.tenantId())
                .eq(PersonalMemoryCandidateEntity::getOwnerId, principal.runtimeUserId());
    }

    private CandidateView view(PersonalMemoryCandidateEntity entity) {
        return new CandidateView(entity.getId(), entity.getMemoryType(), entity.getTitle(), entity.getContent(),
                entity.getReason(), entity.getConfidence(), entity.getStatus(), entity.getConflictMemoryId(),
                entity.getOccurrenceCount(), entity.getSourceAgentId(), entity.getExpiresAt(),
                entity.getCreatedAt(), entity.getLastSeenAt(), entity.getReviewedAt(), entity.getApprovedMemoryId());
    }

    private static String firstText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private enum Outcome {
        CREATED, CREATED_CONFLICT, MERGED, MERGED_CONFLICT, EXACT_EXISTING, REMEMBERED, QUOTA_SKIPPED
    }

    public record ObservationCommand(String userMessage, String assistantAnswer, String sessionId,
                                     String traceId, String agentId) {
    }

    public record ObservationResult(int created, int merged, int exactExisting, int conflicts,
                                    int remembered, int quotaSkipped) {
    }

    public record ReviewCommand(String type, String title, String content) {
    }

    public record CandidateView(Long id, String type, String title, String content, String reason,
                                BigDecimal confidence, String status, Long conflictMemoryId,
                                Integer occurrenceCount, String sourceAgentId, LocalDateTime expiresAt,
                                LocalDateTime createdAt, LocalDateTime lastSeenAt, LocalDateTime reviewedAt,
                                Long approvedMemoryId) {
    }

    public record CandidatePage(List<CandidateView> items, long total, int limit, int offset) {
    }

    public record ReviewResult(CandidateView candidate, RememberResult memoryResult) {
    }
}

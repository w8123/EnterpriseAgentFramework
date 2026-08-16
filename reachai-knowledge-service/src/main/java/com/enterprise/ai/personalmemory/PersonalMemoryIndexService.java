package com.enterprise.ai.personalmemory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.embedding.EmbeddingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Applies idempotent Control events and serves only owner-scoped, bounded projection hits. */
@Service
public class PersonalMemoryIndexService {

    private static final Set<String> EVENT_TYPES = Set.of("PERSONAL_MEMORY_UPSERT", "PERSONAL_MEMORY_DELETE");
    private static final Set<String> ITEM_TYPES = Set.of("FACT", "PREFERENCE", "RULE", "NOTE");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\s,，。！？、;；:：]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private final KnowledgePersonalMemoryIndexMapper indexMapper;
    private final KnowledgePersonalMemoryIndexEventMapper eventMapper;
    private final PersonalMemoryIndexIdentity identity;
    private final ObjectMapper objectMapper;
    private final PersonalMemoryEmbeddingProperties embeddingProperties;
    private final EmbeddingService embeddingService;

    public PersonalMemoryIndexService(KnowledgePersonalMemoryIndexMapper indexMapper,
                                      KnowledgePersonalMemoryIndexEventMapper eventMapper,
                                      PersonalMemoryIndexIdentity identity,
                                      ObjectMapper objectMapper,
                                      PersonalMemoryEmbeddingProperties embeddingProperties,
                                      EmbeddingService embeddingService) {
        this.indexMapper = indexMapper;
        this.eventMapper = eventMapper;
        this.identity = identity;
        this.objectMapper = objectMapper;
        this.embeddingProperties = embeddingProperties;
        this.embeddingService = embeddingService;
    }

    @Transactional
    public ApplyResult apply(IndexEvent request) {
        validateEvent(request);
        KnowledgePersonalMemoryIndexEventEntity duplicate = eventMapper.selectOne(
                Wrappers.<KnowledgePersonalMemoryIndexEventEntity>lambdaQuery()
                        .eq(KnowledgePersonalMemoryIndexEventEntity::getEventId, request.eventId())
                        .last("LIMIT 1"));
        if (duplicate != null) {
            return new ApplyResult(request.eventId(), duplicate.getStatus(), true);
        }
        JsonNode payload = parsePayload(request.payloadJson());
        long memoryId = requiredLong(payload, "memoryId");
        String tenantId = requiredText(payload, "tenantId").toLowerCase(Locale.ROOT);
        String userHash = identity.hash(tenantId, requiredText(payload, "runtimeUserId"));
        long sourceVersion = requiredLong(payload, "sourceVersion");
        KnowledgePersonalMemoryIndexEntity projection = "PERSONAL_MEMORY_DELETE".equals(request.eventType())
                ? deleteProjection(memoryId, tenantId, userHash, sourceVersion)
                : activeProjection(memoryId, tenantId, userHash, sourceVersion, payload);
        boolean applied = persistIfNewer(projection);
        String status = applied ? "APPLIED" : "IGNORED_STALE";
        recordEvent(request, memoryId, status);
        return new ApplyResult(request.eventId(), status, false);
    }

    public QueryResult query(QueryRequest request) {
        if (request == null || !StringUtils.hasText(request.tenantId())
                || !StringUtils.hasText(request.runtimeUserId())) {
            throw new IllegalArgumentException("tenantId and runtimeUserId are required");
        }
        int topK = Math.max(1, Math.min(request.topK() == null ? 8 : request.topK(), 20));
        int maxChars = Math.max(256, Math.min(request.maxChars() == null ? 6_000 : request.maxChars(), 32_000));
        String tenant = request.tenantId().trim().toLowerCase(Locale.ROOT);
        String userHash = identity.hash(tenant, request.runtimeUserId().trim());
        LocalDateTime now = LocalDateTime.now();
        String boundedQuery = boundedQuery(request.query());
        String normalizedQuery = normalize(boundedQuery);
        List<String> queryTokens = TOKEN_SPLIT.splitAsStream(normalizedQuery)
                .filter(token -> token.length() >= 2)
                .toList();
        List<KnowledgePersonalMemoryIndexEntity> candidates = indexMapper.selectList(
                Wrappers.<KnowledgePersonalMemoryIndexEntity>lambdaQuery()
                        .eq(KnowledgePersonalMemoryIndexEntity::getTenantId, tenant)
                        .eq(KnowledgePersonalMemoryIndexEntity::getRuntimeUserHash, userHash)
                        .eq(KnowledgePersonalMemoryIndexEntity::getStatus, "ACTIVE")
                        .and(q -> q.isNull(KnowledgePersonalMemoryIndexEntity::getExpiresAt)
                                .or().gt(KnowledgePersonalMemoryIndexEntity::getExpiresAt, now))
                        .orderByDesc(KnowledgePersonalMemoryIndexEntity::getUpdatedAt)
                        .last("LIMIT " + embeddingProperties.getCandidateLimit()));
        List<Float> queryVector = null;
        if (StringUtils.hasText(normalizedQuery) && embeddingProperties.semanticEnabled()
                && candidates.stream().anyMatch(this::hasCurrentEmbeddingMetadata)) {
            queryVector = embeddingService.embed(
                    embeddingProperties.getModelInstanceId(), boundedQuery);
            PersonalMemoryEmbeddingCodec.validate(queryVector);
        }
        List<Float> finalQueryVector = queryVector;
        List<Scored> ranked = candidates
                .stream()
                .map(item -> new Scored(item,
                        rankScore(item, normalizedQuery, queryTokens, finalQueryVector)))
                .filter(item -> Double.isFinite(item.score())
                        && (!StringUtils.hasText(normalizedQuery) || item.score() > 0))
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(value -> value.item().getUpdatedAt(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        List<QueryHit> hits = new ArrayList<>();
        int usedChars = 0;
        boolean truncated = false;
        for (Scored value : ranked) {
            if (hits.size() >= topK) {
                truncated = true;
                break;
            }
            String content = value.item().getContent() == null ? "" : value.item().getContent();
            int remaining = maxChars - usedChars;
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            if (content.length() > remaining) {
                if (!hits.isEmpty()) {
                    truncated = true;
                    continue;
                }
                content = content.substring(0, remaining);
                truncated = true;
            }
            hits.add(new QueryHit(value.item().getMemoryId()));
            usedChars += content.length();
        }
        return new QueryResult(hits, truncated);
    }

    public OwnerStatus ownerStatus(OwnerStatusRequest request) {
        if (request == null || !StringUtils.hasText(request.tenantId())
                || !StringUtils.hasText(request.runtimeUserId())) {
            throw new IllegalArgumentException("tenantId and runtimeUserId are required");
        }
        String tenant = request.tenantId().trim().toLowerCase(Locale.ROOT);
        String runtimeUserId = request.runtimeUserId().trim();
        String ownerHash = identity.hash(tenant, runtimeUserId);
        PersonalMemoryOwnerProjectionStatusRow row =
                indexMapper.selectOwnerProjectionStatus(tenant, ownerHash);
        long total = count(row == null ? null : row.getTotalCount());
        long active = count(row == null ? null : row.getActiveCount());
        long deleted = count(row == null ? null : row.getDeletedCount());
        long unsafeDeletedVectors = count(
                row == null ? null : row.getUnsafeDeletedVectorCount());
        return new OwnerStatus(
                ownerHash,
                total,
                active,
                deleted,
                unsafeDeletedVectors,
                row == null ? null : row.getMaxSourceVersion(),
                row == null ? null : row.getLatestUpdatedAt(),
                active == 0 && unsafeDeletedVectors == 0);
    }

    private KnowledgePersonalMemoryIndexEntity activeProjection(long memoryId, String tenant,
                                                                 String userHash, long sourceVersion,
                                                                 JsonNode payload) {
        KnowledgePersonalMemoryIndexEntity entity = new KnowledgePersonalMemoryIndexEntity();
        entity.setMemoryId(memoryId);
        entity.setTenantId(tenant);
        entity.setRuntimeUserHash(userHash);
        String type = requiredText(payload, "type").toUpperCase(Locale.ROOT);
        if (!ITEM_TYPES.contains(type)) throw new IllegalArgumentException("unsupported personal memory type");
        entity.setItemType(type);
        entity.setTitle(optionalText(payload, "title"));
        entity.setContent(requiredText(payload, "content"));
        entity.setSummary(optionalText(payload, "summary"));
        entity.setTrustLevel(requiredText(payload, "trustLevel"));
        entity.setSourceVersion(sourceVersion);
        entity.setStatus("ACTIVE");
        entity.setExpiresAt(optionalDate(payload, "expiresAt"));
        resetEmbeddingProjection(entity, embeddingProperties.semanticEnabled() ? "PENDING" : "DISABLED");
        if (embeddingProperties.semanticEnabled()) {
            entity.setEmbeddingNextAttemptAt(LocalDateTime.now());
        }
        entity.setDeletedAt(null);
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setCreatedAt(entity.getUpdatedAt());
        return entity;
    }

    private KnowledgePersonalMemoryIndexEntity deleteProjection(long memoryId, String tenant,
                                                                 String userHash, long sourceVersion) {
        KnowledgePersonalMemoryIndexEntity entity = new KnowledgePersonalMemoryIndexEntity();
        entity.setMemoryId(memoryId);
        entity.setTenantId(tenant);
        entity.setRuntimeUserHash(userHash);
        entity.setItemType("NOTE");
        entity.setTitle(null);
        entity.setContent("[deleted]");
        entity.setSummary(null);
        entity.setTrustLevel("LOW");
        entity.setSourceVersion(sourceVersion);
        entity.setStatus("DELETED");
        entity.setExpiresAt(null);
        resetEmbeddingProjection(entity, "DELETED");
        entity.setDeletedAt(LocalDateTime.now());
        entity.setUpdatedAt(entity.getDeletedAt());
        entity.setCreatedAt(entity.getUpdatedAt());
        return entity;
    }

    private static void resetEmbeddingProjection(KnowledgePersonalMemoryIndexEntity entity, String status) {
        entity.setEmbeddingVector(null);
        entity.setEmbeddingFormat(null);
        entity.setEmbeddingDimension(null);
        entity.setEmbeddingModelInstanceId(null);
        entity.setEmbeddingSourceVersion(null);
        entity.setEmbeddingTextSha256(null);
        entity.setEmbeddingStatus(status);
        entity.setEmbeddingAttempts(0);
        entity.setEmbeddingErrorCode(null);
        entity.setEmbeddingNextAttemptAt(null);
        entity.setEmbeddingClaimToken(null);
        entity.setEmbeddingClaimUntil(null);
    }

    private boolean persistIfNewer(KnowledgePersonalMemoryIndexEntity projection) {
        if (indexMapper.updateIfNewer(projection) > 0) {
            return true;
        }
        try {
            indexMapper.insert(projection);
            return true;
        } catch (DuplicateKeyException concurrentOrStale) {
            // A competing delivery inserted the owner key after our first update.
            // Retrying the conditional update makes the highest source version win.
            projection.setId(null);
            return indexMapper.updateIfNewer(projection) > 0;
        }
    }

    private void recordEvent(IndexEvent request, long memoryId, String status) {
        KnowledgePersonalMemoryIndexEventEntity event = new KnowledgePersonalMemoryIndexEventEntity();
        event.setEventId(request.eventId());
        event.setEventType(request.eventType());
        event.setMemoryId(memoryId);
        event.setStatus(status);
        event.setPayloadSha256(sha256(request.payloadJson()));
        event.setProcessedAt(LocalDateTime.now());
        try {
            eventMapper.insert(event);
        } catch (DuplicateKeyException ignoredRace) {
            // The projection update is idempotent and the unique event id is authoritative.
        }
    }

    private JsonNode parsePayload(String value) {
        try {
            JsonNode payload = objectMapper.readTree(value);
            if (payload == null || !payload.isObject()) throw new IllegalArgumentException("payloadJson must be an object");
            if (!"reachai-personal-memory-index-event-v1".equals(optionalText(payload, "schema"))) {
                throw new IllegalArgumentException("unsupported personal memory index event schema");
            }
            return payload;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid personal memory index payload", ex);
        }
    }

    private static void validateEvent(IndexEvent request) {
        if (request == null || !StringUtils.hasText(request.eventId())
                || !EVENT_TYPES.contains(request.eventType()) || !StringUtils.hasText(request.payloadJson())) {
            throw new IllegalArgumentException("eventId, supported eventType, and payloadJson are required");
        }
    }

    private static String requiredText(JsonNode node, String key) {
        String value = optionalText(node, key);
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static String optionalText(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() ? null : value.asText().trim();
    }

    private static long requiredLong(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || !value.canConvertToLong()) throw new IllegalArgumentException(key + " is required");
        return value.asLong();
    }

    private static long count(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private static LocalDateTime optionalDate(JsonNode node, String key) {
        String value = optionalText(node, key);
        if (!StringUtils.hasText(value)) return null;
        try { return OffsetDateTime.parse(value).toLocalDateTime(); }
        catch (Exception ignored) { return LocalDateTime.parse(value); }
    }

    private double rankScore(KnowledgePersonalMemoryIndexEntity item,
                             String query,
                             List<String> queryTokens,
                             List<Float> queryVector) {
        if (!StringUtils.hasText(query)) return 1;
        double lexical = lexicalScore(item, query, queryTokens);
        if (embeddingProperties.getMode() == PersonalMemoryEmbeddingProperties.Mode.LEXICAL) {
            return lexical;
        }
        double semantic = semanticScore(item, queryVector);
        if (embeddingProperties.getMode() == PersonalMemoryEmbeddingProperties.Mode.VECTOR) {
            return Double.isFinite(semantic) && semantic >= embeddingProperties.getMinScore()
                    ? semantic : Double.NaN;
        }
        double lexicalNormalized = lexical <= 0 ? 0 : lexical / (lexical + 4d);
        if (!Double.isFinite(semantic) || semantic < embeddingProperties.getMinScore()) {
            return lexical > 0 ? lexicalNormalized : Double.NaN;
        }
        double semanticNormalized = Math.max(0, semantic);
        return embeddingProperties.getVectorWeight() * semanticNormalized
                + (1 - embeddingProperties.getVectorWeight()) * lexicalNormalized;
    }

    private double semanticScore(KnowledgePersonalMemoryIndexEntity item, List<Float> queryVector) {
        if (queryVector == null || !hasCurrentEmbeddingMetadata(item)) {
            return Double.NaN;
        }
        return PersonalMemoryEmbeddingCodec.cosine(queryVector, item.getEmbeddingVector(),
                item.getEmbeddingDimension(), item.getEmbeddingFormat());
    }

    private boolean hasCurrentEmbeddingMetadata(KnowledgePersonalMemoryIndexEntity item) {
        return item != null && "READY".equals(item.getEmbeddingStatus())
                && item.getEmbeddingVector() != null
                && item.getEmbeddingDimension() != null
                && item.getEmbeddingDimension() > 0
                && StringUtils.hasText(item.getEmbeddingTextSha256())
                && PersonalMemoryEmbeddingCodec.FORMAT.equals(item.getEmbeddingFormat())
                && item.getSourceVersion() != null
                && item.getSourceVersion().equals(item.getEmbeddingSourceVersion())
                && embeddingProperties.getModelInstanceId().equals(item.getEmbeddingModelInstanceId());
    }

    private static double lexicalScore(KnowledgePersonalMemoryIndexEntity item,
                                       String query,
                                       List<String> queryTokens) {
        String title = normalize(item.getTitle());
        String summary = normalize(item.getSummary());
        String content = normalize(item.getContent());
        double score = title.equals(query) ? 8 : title.contains(query) ? 4 : 0;
        if (summary.contains(query)) score += 2;
        if (content.contains(query)) score += 3;
        for (String token : queryTokens) {
            if (title.contains(token)) score += 1.5;
            if (summary.contains(token)) score += .75;
            if (content.contains(token)) score += 1;
        }
        if (score > 0 && "VERIFIED".equals(item.getTrustLevel())) score += .25;
        return score;
    }

    private static String normalize(String value) {
        return value == null ? "" : WHITESPACE.matcher(value.trim())
                .replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    private String boundedQuery(String value) {
        String query = value == null ? "" : value.trim();
        if (query.length() <= embeddingProperties.getMaxQueryChars()) {
            return query;
        }
        int end = embeddingProperties.getMaxQueryChars();
        if (Character.isHighSurrogate(query.charAt(end - 1))
                && Character.isLowSurrogate(query.charAt(end))) {
            end--;
        }
        return query.substring(0, end);
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private record Scored(KnowledgePersonalMemoryIndexEntity item, double score) {}

    public record IndexEvent(String eventId, String eventType, String payloadJson) {}
    public record ApplyResult(String eventId, String status, boolean idempotentReplay) {}
    public record QueryRequest(String tenantId, String runtimeUserId, String query, Integer topK, Integer maxChars) {}
    /** Candidate identity only; canonical content is re-read and authorized by Control. */
    public record QueryHit(Long memoryId) {}
    public record QueryResult(List<QueryHit> hits, boolean truncated) {}
    public record OwnerStatusRequest(String tenantId, String runtimeUserId) {}
    public record OwnerStatus(String runtimeUserHash,
                              long totalCount,
                              long activeCount,
                              long deletedCount,
                              long unsafeDeletedVectorCount,
                              Long maxSourceVersion,
                              LocalDateTime latestUpdatedAt,
                              boolean projectionErased) {}
}

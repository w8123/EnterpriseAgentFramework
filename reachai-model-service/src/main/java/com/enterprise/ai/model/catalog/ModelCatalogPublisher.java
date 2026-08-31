package com.enterprise.ai.model.catalog;

import com.enterprise.ai.model.template.ModelTemplateEntity;
import com.enterprise.ai.model.template.ModelTemplateMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ModelCatalogPublisher {

    private static final Pattern MODEL_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$");
    private static final Set<String> MODEL_TYPES = Set.of("LLM", "EMBEDDING", "RERANKER");
    private static final Set<String> LIFECYCLE = Set.of("ACTIVE", "PREVIEW", "DEPRECATED", "RETIRED", "UNKNOWN");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final ModelCatalogChangeMapper changeMapper;
    private final ModelTemplateMapper templateMapper;
    private final ModelCatalogTemplateDefaults templateDefaults;
    private final ModelCatalogSyncProperties properties;
    private final ObjectMapper objectMapper;

    @Transactional
    public PublishSummary publish(ModelCatalogSourceEntity source,
                                  ModelCatalogSyncRunEntity run,
                                  ModelCatalogSnapshotEntity snapshot,
                                  ModelCatalogAnalysis analysis) {
        Map<String, ModelCatalogCandidate> unique = analysis.candidates().stream()
                .filter(candidate -> candidate != null && StringUtils.hasText(candidate.modelName()))
                .sorted(Comparator.comparingDouble(ModelCatalogCandidate::confidence).reversed())
                .collect(Collectors.toMap(
                        candidate -> candidate.modelName().trim().toLowerCase(Locale.ROOT) + "|"
                                + normalized(candidate.modelType(), "OTHER"),
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));

        int published = 0;
        int review = 0;
        for (ModelCatalogCandidate candidate : unique.values()) {
            PublicationResult result = publishOne(source, run, snapshot, candidate);
            if (result.published()) published++;
            if (result.reviewRequired()) review++;
        }
        return new PublishSummary(unique.size(), published, review);
    }

    private PublicationResult publishOne(ModelCatalogSourceEntity source,
                                         ModelCatalogSyncRunEntity run,
                                         ModelCatalogSnapshotEntity snapshot,
                                         ModelCatalogCandidate candidate) {
        String modelName = candidate.modelName().trim();
        String modelType = normalized(candidate.modelType(), "OTHER");
        String lifecycle = normalized(candidate.lifecycleStatus(), "UNKNOWN");
        ModelTemplateEntity existing = MODEL_TYPES.contains(modelType)
                ? templateMapper.findByCatalogKey(source.getProvider(), modelType, modelName)
                : null;
        if (existing == null) {
            List<ModelTemplateEntity> sameName = templateMapper.findByProviderAndModelName(source.getProvider(), modelName);
            if (sameName.size() == 1) {
                existing = sameName.get(0);
                modelType = existing.getModelType();
            }
        }

        String validationError = validate(source, snapshot, candidate, modelName, modelType, lifecycle);
        String changeType = changeType(existing, candidate, lifecycle);
        ModelCatalogChangeEntity change = baseChange(source, run, snapshot, candidate, modelName, modelType, changeType);
        if (validationError != null) {
            change.setValidationStatus("REJECTED");
            change.setPublishStatus("REVIEW_REQUIRED");
            change.setReviewReason(validationError);
            changeMapper.insertIgnore(change);
            return new PublicationResult(false, true);
        }

        change.setValidationStatus("VALID");
        if (!properties.isAutoPublishSafeFacts()) {
            change.setPublishStatus("REVIEW_REQUIRED");
            change.setReviewReason("Automatic publication of safe factual fields is disabled");
            changeMapper.insertIgnore(change);
            return new PublicationResult(false, true);
        }
        if (candidate.confidence() < properties.getAutoPublishMinConfidence()) {
            change.setPublishStatus("REVIEW_REQUIRED");
            change.setReviewReason("Analyzer confidence is below the automatic publication threshold");
            changeMapper.insertIgnore(change);
            return new PublicationResult(false, true);
        }

        boolean materiallyChanged;
        if (existing == null) {
            if (!"ACTIVE".equals(lifecycle)) {
                change.setPublishStatus("REVIEW_REQUIRED");
                change.setReviewReason("Only explicitly active new models can be published automatically");
                changeMapper.insertIgnore(change);
                return new PublicationResult(false, true);
            }
            if (!hasRuntimeProtocolEvidence(candidate, modelType)) {
                change.setPublishStatus("REVIEW_REQUIRED");
                change.setReviewReason("Official source does not explicitly evidence the required runtime endpoint");
                changeMapper.insertIgnore(change);
                return new PublicationResult(false, true);
            }
            ModelCatalogTemplateDefaults.Defaults defaults = templateDefaults.resolve(source.getProvider(), modelType);
            if (defaults == null) {
                change.setPublishStatus("REVIEW_REQUIRED");
                change.setReviewReason("Provider has no reviewed OpenAI-compatible connection defaults");
                changeMapper.insertIgnore(change);
                return new PublicationResult(false, true);
            }
            ModelTemplateEntity created = newTemplate(source, snapshot, candidate, modelName, modelType, defaults);
            templateMapper.insertIgnore(created);
            existing = templateMapper.findByCatalogKey(source.getProvider(), modelType, modelName);
            if (existing == null) {
                change.setPublishStatus("REVIEW_REQUIRED");
                change.setReviewReason("Published template could not be resolved after insert");
                changeMapper.insertIgnore(change);
                return new PublicationResult(false, true);
            }
            materiallyChanged = true;
        } else {
            materiallyChanged = applyVerifiedFacts(existing, source, snapshot, candidate, lifecycle);
            if (templateMapper.updateById(existing) != 1) {
                throw new ModelCatalogSyncException(
                        "MODEL_CATALOG_TEMPLATE_UPDATE_FAILED",
                        "Verified catalog facts could not be persisted to the model template",
                        true);
            }
        }

        change.setPublishStatus(materiallyChanged ? "PUBLISHED" : "NO_CHANGE");
        change.setPublishedTemplateId(existing.getId());
        change.setPublishedAt(LocalDateTime.now());
        changeMapper.insertIgnore(change);
        return new PublicationResult(materiallyChanged, false);
    }

    private String validate(ModelCatalogSourceEntity source,
                            ModelCatalogSnapshotEntity snapshot,
                            ModelCatalogCandidate candidate,
                            String modelName,
                            String modelType,
                            String lifecycle) {
        try {
            ModelCatalogSourcePolicy.validate(source);
        } catch (ModelCatalogSyncException ex) {
            return ex.getMessage();
        }
        if (!StringUtils.hasText(source.getProvider())) return "Source provider key is missing";
        if (!"OFFICIAL".equalsIgnoreCase(source.getTrustLevel())) return "Source is not classified as official";
        if (!MODEL_NAME.matcher(modelName).matches()) return "Model name is not a valid exact API identifier";
        if (!MODEL_TYPES.contains(modelType)) return "Model type is not publishable";
        if (!LIFECYCLE.contains(lifecycle)) return "Lifecycle status is invalid";
        if (!containsIgnoreCase(snapshot.getNormalizedContent(), modelName)) {
            return "Exact model identifier is absent from the official source snapshot";
        }
        if (!StringUtils.hasText(candidate.evidenceExcerpt())) {
            return "Candidate does not include an official-source evidence excerpt";
        }
        String evidenceExcerpt = bounded(candidate.evidenceExcerpt(), 2_000);
        if (!containsNormalizedExcerpt(snapshot.getNormalizedContent(), evidenceExcerpt)) {
            return "Candidate evidence excerpt cannot be located in the official source snapshot";
        }
        if (!containsIgnoreCase(evidenceExcerpt, modelName)) {
            return "Candidate evidence excerpt does not contain the exact model identifier";
        }
        if (StringUtils.hasText(candidate.replacementModelName())) {
            String replacement = candidate.replacementModelName().trim();
            if (!MODEL_NAME.matcher(replacement).matches()
                    || !containsIgnoreCase(snapshot.getNormalizedContent(), replacement)) {
                return "Replacement model identifier is not evidenced by the official source snapshot";
            }
        }
        try {
            parseDate(candidate.releasedAt());
            parseDate(candidate.deprecatedAt());
            parseDate(candidate.retireAt());
        } catch (IllegalArgumentException ex) {
            return "Lifecycle date is not an ISO date";
        }
        return null;
    }

    private ModelCatalogChangeEntity baseChange(ModelCatalogSourceEntity source,
                                                 ModelCatalogSyncRunEntity run,
                                                 ModelCatalogSnapshotEntity snapshot,
                                                 ModelCatalogCandidate candidate,
                                                 String modelName,
                                                 String modelType,
                                                 String changeType) {
        ModelCatalogChangeEntity change = new ModelCatalogChangeEntity();
        change.setRunId(run.getId());
        change.setSnapshotId(snapshot.getId());
        change.setSourceId(source.getId());
        change.setProvider(source.getProvider());
        change.setModelName(modelName);
        change.setModelType(modelType);
        change.setChangeType(changeType);
        change.setProposedJson(toJson(candidate));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sourceUrl", source.getSourceUrl());
        evidence.put("contentSha256", snapshot.getContentSha256());
        evidence.put("trustLevel", source.getTrustLevel());
        evidence.put("evidenceExcerpt", bounded(candidate.evidenceExcerpt(), 1_000));
        change.setEvidenceJson(toJson(evidence));
        change.setConfidence(BigDecimal.valueOf(candidate.confidence()).setScale(4, RoundingMode.HALF_UP));
        change.setCreatedAt(LocalDateTime.now());
        change.setUpdatedAt(LocalDateTime.now());
        return change;
    }

    private ModelTemplateEntity newTemplate(ModelCatalogSourceEntity source,
                                             ModelCatalogSnapshotEntity snapshot,
                                             ModelCatalogCandidate candidate,
                                             String modelName,
                                             String modelType,
                                             ModelCatalogTemplateDefaults.Defaults defaults) {
        LocalDateTime now = LocalDateTime.now();
        ModelTemplateEntity entity = new ModelTemplateEntity();
        entity.setId(stableTemplateId(source.getProvider(), modelType, modelName));
        entity.setName(bounded(firstText(candidate.displayName(), modelName), 128));
        entity.setProvider(source.getProvider());
        entity.setModelType(modelType);
        entity.setModelName(modelName);
        entity.setProtocol("OPENAI_COMPATIBLE");
        entity.setConnectionDefaultsJson(toJson(defaults.connectionDefaults()));
        entity.setCredentialSchemaJson(toJson(defaults.credentialSchema()));
        entity.setDefaultOptionsJson("{}");
        entity.setParamsSchemaJson("[]");
        entity.setCapabilitiesJson(toJson(candidate.capabilities() == null ? Map.of() : candidate.capabilities()));
        entity.setSourceKey(source.getSourceKey());
        entity.setLifecycleStatus("ACTIVE");
        entity.setRecommendationStatus("UNASSESSED");
        entity.setOfficialPositioning(bounded(candidate.officialPositioning(), 512));
        entity.setReleasedAt(parseDate(candidate.releasedAt()));
        entity.setDeprecatedAt(parseDate(candidate.deprecatedAt()));
        entity.setRetireAt(parseDate(candidate.retireAt()));
        entity.setReplacementModelName(bounded(candidate.replacementModelName(), 128));
        entity.setLastSeenAt(now);
        entity.setLastVerifiedAt(now);
        entity.setSourceUrl(source.getSourceUrl());
        entity.setSourceRevision(snapshot.getContentSha256());
        entity.setSyncManaged(true);
        entity.setIconKey(defaults.iconKey());
        entity.setEnabled(true);
        entity.setSortOrder(9_000 + Math.floorMod(modelName.hashCode(), 900));
        entity.setRemark("官方来源自动同步；推荐状态需经 EvalOps 或人工评审。");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private boolean applyVerifiedFacts(ModelTemplateEntity entity,
                                       ModelCatalogSourceEntity source,
                                       ModelCatalogSnapshotEntity snapshot,
                                       ModelCatalogCandidate candidate,
                                       String incomingLifecycle) {
        boolean changed = false;
        String lifecycle = monotonicLifecycle(entity.getLifecycleStatus(), incomingLifecycle);
        changed |= !same(entity.getLifecycleStatus(), lifecycle);
        entity.setLifecycleStatus(lifecycle);
        if (Boolean.TRUE.equals(entity.getSyncManaged()) && StringUtils.hasText(candidate.displayName())) {
            String displayName = bounded(candidate.displayName(), 128);
            changed |= !same(entity.getName(), displayName);
            entity.setName(displayName);
        }
        Map<String, Object> capabilities = readMap(entity.getCapabilitiesJson());
        Map<String, Object> incoming = candidate.capabilities() == null ? Map.of() : candidate.capabilities();
        if (!incoming.isEmpty()) {
            Map<String, Object> merged = new LinkedHashMap<>(capabilities);
            merged.putAll(incoming);
            String mergedJson = toJson(merged);
            changed |= !same(entity.getCapabilitiesJson(), mergedJson);
            entity.setCapabilitiesJson(mergedJson);
        }
        LocalDate releasedAt = firstNonNull(parseDate(candidate.releasedAt()), entity.getReleasedAt());
        LocalDate deprecatedAt = firstNonNull(parseDate(candidate.deprecatedAt()), entity.getDeprecatedAt());
        LocalDate retireAt = firstNonNull(parseDate(candidate.retireAt()), entity.getRetireAt());
        String replacement = firstText(candidate.replacementModelName(), entity.getReplacementModelName());
        String positioning = firstText(candidate.officialPositioning(), entity.getOfficialPositioning());
        changed |= !same(entity.getReleasedAt(), releasedAt)
                || !same(entity.getDeprecatedAt(), deprecatedAt)
                || !same(entity.getRetireAt(), retireAt)
                || !same(entity.getReplacementModelName(), replacement)
                || !same(entity.getOfficialPositioning(), positioning);
        entity.setReleasedAt(releasedAt);
        entity.setDeprecatedAt(deprecatedAt);
        entity.setRetireAt(retireAt);
        entity.setReplacementModelName(bounded(replacement, 128));
        entity.setOfficialPositioning(bounded(positioning, 512));
        entity.setLastSeenAt(LocalDateTime.now());
        entity.setLastVerifiedAt(LocalDateTime.now());
        entity.setSourceKey(source.getSourceKey());
        entity.setSourceUrl(source.getSourceUrl());
        entity.setSourceRevision(snapshot.getContentSha256());
        if (!StringUtils.hasText(entity.getRecommendationStatus())) entity.setRecommendationStatus("UNASSESSED");
        if ("RETIRED".equals(lifecycle) && Boolean.TRUE.equals(entity.getEnabled())) {
            entity.setEnabled(false);
            changed = true;
        }
        entity.setUpdatedAt(LocalDateTime.now());
        return changed;
    }

    private String changeType(ModelTemplateEntity existing,
                              ModelCatalogCandidate candidate,
                              String lifecycle) {
        if (existing == null) return "DISCOVERED";
        if (!same(monotonicLifecycle(existing.getLifecycleStatus(), lifecycle), existing.getLifecycleStatus())) {
            return "LIFECYCLE_CHANGED";
        }
        if ((StringUtils.hasText(candidate.displayName()) && !same(existing.getName(), candidate.displayName()))
                || (StringUtils.hasText(candidate.officialPositioning())
                && !same(existing.getOfficialPositioning(), candidate.officialPositioning()))) {
            return "METADATA_UPDATED";
        }
        return "OBSERVED";
    }

    private boolean hasRuntimeProtocolEvidence(ModelCatalogCandidate candidate, String modelType) {
        Map<String, Object> capabilities = candidate.capabilities() == null ? Map.of() : candidate.capabilities();
        String key = switch (modelType) {
            case "LLM" -> "supportsChatCompletions";
            case "EMBEDDING" -> "supportsEmbeddings";
            case "RERANKER" -> "supportsRerank";
            default -> "";
        };
        Object value = capabilities.get(key);
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String monotonicLifecycle(String current, String incoming) {
        String existing = normalized(current, "UNKNOWN");
        String proposed = normalized(incoming, "UNKNOWN");
        return lifecycleRank(proposed) >= lifecycleRank(existing) ? proposed : existing;
    }

    private int lifecycleRank(String value) {
        return switch (value) {
            case "PREVIEW" -> 1;
            case "ACTIVE" -> 2;
            case "DEPRECATED" -> 3;
            case "RETIRED" -> 4;
            default -> 0;
        };
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) return Map.of();
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim();
        if (normalized.length() >= 10) normalized = normalized.substring(0, 10);
        return LocalDate.parse(normalized);
    }

    private String stableTemplateId(String provider, String modelType, String modelName) {
        String slug = provider.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        if (slug.length() > 16) slug = slug.substring(0, 16);
        String key = provider + "|" + modelType + "|" + modelName;
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
            return "tpl-sync-" + slug + "-" + digest.substring(0, 20);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new ModelCatalogSyncException(
                    "MODEL_CATALOG_SERIALIZATION_FAILED", "Catalog metadata could not be serialized", false, ex);
        }
    }

    private boolean containsIgnoreCase(String content, String value) {
        return content != null && value != null
                && content.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
    }

    private boolean containsNormalizedExcerpt(String content, String excerpt) {
        if (!StringUtils.hasText(content) || !StringUtils.hasText(excerpt)) return false;
        String normalizedContent = normalizeEvidenceText(content);
        String normalizedExcerpt = normalizeEvidenceText(excerpt);
        return normalizedExcerpt.length() >= 4 && normalizedContent.contains(normalizedExcerpt);
    }

    private String normalizeEvidenceText(String value) {
        return value.replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private String normalized(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : fallback;
    }

    private String bounded(String value, int max) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first.trim() : second;
    }

    private <T> T firstNonNull(T first, T second) {
        return first == null ? second : first;
    }

    private boolean same(Object first, Object second) {
        return first == null ? second == null : first.equals(second);
    }

    public record PublishSummary(int candidateCount, int publishedCount, int reviewCount) {
    }

    private record PublicationResult(boolean published, boolean reviewRequired) {
    }
}

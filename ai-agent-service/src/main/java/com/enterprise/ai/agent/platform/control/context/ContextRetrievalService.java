package com.enterprise.ai.agent.platform.control.context;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContextRetrievalService {

    private static final double KEYWORD_TITLE_WEIGHT = 3.0;
    private static final double KEYWORD_SUMMARY_WEIGHT = 2.0;
    private static final double KEYWORD_CONTENT_WEIGHT = 1.0;
    private static final double TOKEN_TITLE_WEIGHT = 1.5;
    private static final double TOKEN_SUMMARY_WEIGHT = 1.0;
    private static final double TOKEN_CONTENT_WEIGHT = 0.6;
    private static final double TYPE_MATCH_WEIGHT = 0.5;
    private static final double BINDING_WEIGHT = 0.2;

    private final ContextItemMapper itemMapper;
    private final ContextNamespaceMapper namespaceMapper;
    private final ContextBindingService bindingService;
    private final ContextAccessPolicyService accessPolicyService;
    private final ContextAuditService auditService;

    public List<ContextSearchResult> search(ContextQueryRequest query) {
        accessPolicyService.validateQueryScope(query);
        MemoryLane lane = accessPolicyService.requireMemoryLane(query.getMemoryLane());
        int topK = query.getTopK() == null || query.getTopK() <= 0 ? 20 : Math.min(query.getTopK(), 200);

        Map<Long, ContextNamespaceEntity> namespaces = loadNamespaces(query);
        Set<Long> bindingMatches = bindingService.resolveBindingMatches(query);

        List<String> itemTypes = normalizeTypes(query.getItemTypes());
        List<ContextItemEntity> candidates = itemMapper.selectList(
                Wrappers.lambdaQuery(ContextItemEntity.class)
                        .eq(ContextItemEntity::getMemoryLane, lane.name())
                        .eq(ContextItemEntity::getStatus, ContextStatus.ACTIVE.name())
                        .in(!namespaces.isEmpty(), ContextItemEntity::getNamespaceId, namespaces.keySet())
                        .in(!itemTypes.isEmpty(), ContextItemEntity::getItemType, itemTypes)
                        .and(wrapper -> wrapper.isNull(ContextItemEntity::getExpiresAt)
                                .or()
                                .gt(ContextItemEntity::getExpiresAt, LocalDateTime.now())));

        LocalDateTime now = LocalDateTime.now();
        candidates = candidates.stream()
                .filter(item -> item.getExpiresAt() == null || item.getExpiresAt().isAfter(now))
                .filter(item -> item.getEffectiveFrom() == null || !item.getEffectiveFrom().isAfter(now))
                .toList();

        List<ContextSearchResult> results = new ArrayList<>();
        String keyword = normalizeKeyword(query.getQuery());
        List<String> keywordTokens = isHybridRetrieval(query.getRetrievalMode())
                ? tokenizeKeyword(keyword)
                : List.of();
        for (ContextItemEntity item : candidates) {
            ContextNamespaceEntity namespace = namespaces.get(item.getNamespaceId());
            if (namespace == null) {
                continue;
            }
            if (!accessPolicyService.isItemReadable(item, namespace, query)) {
                continue;
            }
            KeywordSignals keywordSignals = analyzeKeyword(item, keyword, keywordTokens);
            if (keyword != null && !keywordSignals.anyMatch()) {
                continue;
            }
            boolean bindingMatched = bindingMatches.contains(item.getId());
            boolean typeMatched = !itemTypes.isEmpty()
                    && itemTypes.contains(item.getItemType().trim().toUpperCase(Locale.ROOT));
            RankedHit ranked = rankItem(item, keywordSignals, typeMatched, bindingMatched);
            results.add(ContextSearchResult.builder()
                    .item(ContextViewMapper.toItemResponse(item))
                    .rankScore(ranked.rankScore())
                    .hitReason(ranked.hitReason())
                    .scoreBreakdown(ranked.scoreBreakdown())
                    .build());
        }

        Comparator<ContextSearchResult> comparator = sortComparator(query.getSortBy());
        results.sort(comparator);
        if (results.size() > topK) {
            results = new ArrayList<>(results.subList(0, topK));
        }

        auditService.record(ContextAuditEventType.SEARCH, ContextAuditDecision.ALLOW,
                "search hits=" + results.size(), query, null, null);
        return results;
    }

    private Map<Long, ContextNamespaceEntity> loadNamespaces(ContextQueryRequest query) {
        var wrapper = Wrappers.lambdaQuery(ContextNamespaceEntity.class)
                .eq(ContextNamespaceEntity::getTenantId, query.getTenantId())
                .ne(ContextNamespaceEntity::getStatus, ContextStatus.DELETED.name());
        return namespaceMapper.selectList(wrapper).stream()
                .collect(Collectors.toMap(ContextNamespaceEntity::getId, ns -> ns, (a, b) -> a, HashMap::new));
    }

    private List<String> normalizeTypes(List<String> itemTypes) {
        if (itemTypes == null || itemTypes.isEmpty()) {
            return List.of();
        }
        return itemTypes.stream()
                .filter(StringUtils::hasText)
                .map(type -> type.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toList());
    }

    private String normalizeKeyword(String query) {
        if (!StringUtils.hasText(query)) {
            return null;
        }
        return query.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isHybridRetrieval(String retrievalMode) {
        return "HYBRID".equalsIgnoreCase(retrievalMode);
    }

    private List<String> tokenizeKeyword(String keyword) {
        if (keyword == null) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < keyword.length(); i++) {
            char ch = keyword.charAt(i);
            if (isTokenSeparator(ch)) {
                addKeywordTokens(tokens, current);
            } else {
                current.append(ch);
            }
        }
        addKeywordTokens(tokens, current);
        return tokens.stream()
                .distinct()
                .limit(16)
                .toList();
    }

    private boolean isTokenSeparator(char ch) {
        return Character.isWhitespace(ch)
                || Character.getType(ch) == Character.CONNECTOR_PUNCTUATION
                || Character.getType(ch) == Character.DASH_PUNCTUATION
                || Character.getType(ch) == Character.START_PUNCTUATION
                || Character.getType(ch) == Character.END_PUNCTUATION
                || Character.getType(ch) == Character.OTHER_PUNCTUATION
                || "|/\\,.;:!?，。；：、（）()[]{}<>《》".indexOf(ch) >= 0;
    }

    private void addKeywordTokens(List<String> tokens, StringBuilder current) {
        String segment = current.toString();
        if (segment.length() >= 2) {
            if (!addCjkBigrams(tokens, segment)) {
                tokens.add(segment);
            }
        }
        current.setLength(0);
    }

    private boolean addCjkBigrams(List<String> tokens, String segment) {
        boolean added = false;
        StringBuilder cjkRun = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char ch = segment.charAt(i);
            if (isCjk(ch)) {
                cjkRun.append(ch);
            } else {
                added |= addCjkRunBigrams(tokens, cjkRun);
            }
        }
        return added | addCjkRunBigrams(tokens, cjkRun);
    }

    private boolean addCjkRunBigrams(List<String> tokens, StringBuilder cjkRun) {
        if (cjkRun.length() < 2) {
            cjkRun.setLength(0);
            return false;
        }
        for (int i = 0; i < cjkRun.length() - 1; i++) {
            tokens.add(cjkRun.substring(i, i + 2));
        }
        cjkRun.setLength(0);
        return true;
    }

    private boolean isCjk(char ch) {
        Character.UnicodeScript script = Character.UnicodeScript.of(ch);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private KeywordSignals analyzeKeyword(ContextItemEntity item, String keyword, List<String> keywordTokens) {
        if (keyword == null) {
            return KeywordSignals.none();
        }
        String title = safeLower(item.getTitle());
        String summary = safeLower(item.getSummary());
        String content = safeLower(item.getContent());
        boolean titleMatch = title.contains(keyword);
        boolean summaryMatch = summary.contains(keyword);
        boolean contentMatch = content.contains(keyword);
        if (titleMatch || summaryMatch || contentMatch || keywordTokens.isEmpty()) {
            return new KeywordSignals(titleMatch, summaryMatch, contentMatch,
                    false, false, false, List.of(), 0);
        }

        List<String> matchedTokens = keywordTokens.stream()
                .filter(token -> title.contains(token) || summary.contains(token) || content.contains(token))
                .toList();
        if (!passesTokenThreshold(matchedTokens.size(), keywordTokens.size())) {
            return KeywordSignals.none();
        }
        return new KeywordSignals(false, false, false,
                matchedTokens.stream().anyMatch(title::contains),
                matchedTokens.stream().anyMatch(summary::contains),
                matchedTokens.stream().anyMatch(content::contains),
                matchedTokens,
                keywordTokens.size());
    }

    private boolean passesTokenThreshold(int matchedTokenCount, int keywordTokenCount) {
        if (matchedTokenCount <= 0 || keywordTokenCount <= 0) {
            return false;
        }
        return matchedTokenCount >= 1;
    }

    private RankedHit rankItem(ContextItemEntity item,
                                 KeywordSignals keywordSignals,
                                 boolean typeMatched,
                                 boolean bindingMatched) {
        double titleScore = keywordSignals.titleMatch() ? KEYWORD_TITLE_WEIGHT : 0.0;
        double summaryScore = keywordSignals.summaryMatch() ? KEYWORD_SUMMARY_WEIGHT : 0.0;
        double contentScore = keywordSignals.contentMatch() ? KEYWORD_CONTENT_WEIGHT : 0.0;
        double tokenScore = tokenScore(keywordSignals);
        double typeScore = typeMatched && StringUtils.hasText(item.getItemType()) ? TYPE_MATCH_WEIGHT : 0.0;
        double trustScore = trustBoost(item.getTrustLevel());
        double confidenceScore = confidenceBoost(item.getConfidence());
        double verifiedScore = recentVerificationBoost(item.getLastVerifiedAt());
        double updatedScore = recentUpdateBoost(item.getUpdatedAt());
        double sourceScore = sourceBoost(item.getSourceType());
        double bindingScore = bindingMatched ? BINDING_WEIGHT : 0.0;

        double rankScore = roundScore(titleScore + summaryScore + contentScore + tokenScore + typeScore
                + trustScore + confidenceScore + verifiedScore + updatedScore + sourceScore + bindingScore);

        String hitReason = buildHitReason(item, keywordSignals, typeMatched, bindingMatched, item.getTrustLevel());
        String scoreBreakdown = buildScoreBreakdown(
                titleScore, summaryScore, contentScore, tokenScore, typeScore, trustScore, confidenceScore,
                verifiedScore, updatedScore, sourceScore, bindingScore);

        return new RankedHit(rankScore, hitReason, scoreBreakdown);
    }

    private double tokenScore(KeywordSignals keywordSignals) {
        if (!keywordSignals.tokenMatch()) {
            return 0.0;
        }
        double fieldScore = 0.0;
        if (keywordSignals.titleTokenMatch()) {
            fieldScore += TOKEN_TITLE_WEIGHT;
        }
        if (keywordSignals.summaryTokenMatch()) {
            fieldScore += TOKEN_SUMMARY_WEIGHT;
        }
        if (keywordSignals.contentTokenMatch()) {
            fieldScore += TOKEN_CONTENT_WEIGHT;
        }
        double ratio = (double) keywordSignals.matchedTokens().size() / keywordSignals.keywordTokenCount();
        return roundScore(fieldScore * ratio);
    }

    private String buildHitReason(ContextItemEntity item,
                                  KeywordSignals keywordSignals,
                                  boolean typeMatched,
                                  boolean bindingMatched,
                                  String trustLevel) {
        if (keywordSignals.titleMatch()) {
            return bindingMatched ? "title keyword match+binding" : "title keyword match";
        }
        if (keywordSignals.summaryMatch()) {
            return bindingMatched ? "summary keyword match+binding" : "summary keyword match";
        }
        if (keywordSignals.contentMatch()) {
            return bindingMatched ? "content keyword match+binding" : "content keyword match";
        }
        if (keywordSignals.titleTokenMatch()) {
            return bindingMatched ? "title token keyword match+binding" : "title token keyword match";
        }
        if (keywordSignals.summaryTokenMatch()) {
            return bindingMatched ? "summary token keyword match+binding" : "summary token keyword match";
        }
        if (keywordSignals.contentTokenMatch()) {
            return bindingMatched ? "content token keyword match+binding" : "content token keyword match";
        }
        if (typeMatched && StringUtils.hasText(item.getItemType())) {
            return "type match: " + item.getItemType().trim().toUpperCase(Locale.ROOT);
        }
        if (bindingMatched) {
            return "binding match";
        }
        if (StringUtils.hasText(trustLevel)
                && ContextTrustLevel.VERIFIED.name().equalsIgnoreCase(trustLevel.trim())) {
            return "trust boost: VERIFIED";
        }
        return "namespace scope";
    }

    private String buildScoreBreakdown(double titleScore,
                                       double summaryScore,
                                       double contentScore,
                                       double tokenScore,
                                       double typeScore,
                                       double trustScore,
                                       double confidenceScore,
                                       double verifiedScore,
                                       double updatedScore,
                                       double sourceScore,
                                       double bindingScore) {
        List<String> parts = new ArrayList<>();
        appendIfPositive(parts, "title", titleScore);
        appendIfPositive(parts, "summary", summaryScore);
        appendIfPositive(parts, "content", contentScore);
        appendIfPositive(parts, "tokens", tokenScore);
        appendIfPositive(parts, "type", typeScore);
        appendIfPositive(parts, "trust", trustScore);
        appendIfPositive(parts, "confidence", confidenceScore);
        appendIfPositive(parts, "verified", verifiedScore);
        appendIfPositive(parts, "updated", updatedScore);
        appendIfPositive(parts, "source", sourceScore);
        appendIfPositive(parts, "binding", bindingScore);
        return String.join(", ", parts);
    }

    private void appendIfPositive(List<String> parts, String key, double value) {
        if (value > 0.0) {
            parts.add(String.format(Locale.ROOT, "%s=%.1f", key, value));
        }
    }

    private double trustBoost(String trustLevel) {
        if (!StringUtils.hasText(trustLevel)) {
            return 0.4;
        }
        return switch (ContextTrustLevel.valueOf(trustLevel.trim().toUpperCase(Locale.ROOT))) {
            case VERIFIED -> 1.0;
            case HIGH -> 0.7;
            case MEDIUM -> 0.4;
            case LOW -> 0.2;
        };
    }

    private double confidenceBoost(BigDecimal confidence) {
        double value = confidence == null ? 0.7 : confidence.doubleValue();
        return roundScore(value * 0.8);
    }

    private double recentVerificationBoost(LocalDateTime lastVerifiedAt) {
        if (lastVerifiedAt == null) {
            return 0.0;
        }
        long days = ChronoUnit.DAYS.between(lastVerifiedAt, LocalDateTime.now());
        if (days <= 7) {
            return 0.2;
        }
        if (days <= 30) {
            return 0.1;
        }
        return 0.0;
    }

    private double recentUpdateBoost(LocalDateTime updatedAt) {
        if (updatedAt == null) {
            return 0.0;
        }
        long days = ChronoUnit.DAYS.between(updatedAt, LocalDateTime.now());
        if (days <= 7) {
            return 0.15;
        }
        if (days <= 30) {
            return 0.05;
        }
        return 0.0;
    }

    private double sourceBoost(String sourceType) {
        if (!StringUtils.hasText(sourceType)) {
            return 0.0;
        }
        return switch (ContextSourceType.valueOf(sourceType.trim().toUpperCase(Locale.ROOT))) {
            case USER_CONFIRMED -> 0.15;
            case MANUAL -> 0.12;
            case DOC -> 0.10;
            case CODE -> 0.08;
            case API -> 0.08;
            default -> 0.0;
        };
    }

    private double roundScore(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private Comparator<ContextSearchResult> sortComparator(String sortBy) {
        if ("confidence".equalsIgnoreCase(sortBy)) {
            return Comparator.<ContextSearchResult, BigDecimal>comparing(
                    result -> result.getItem().getConfidence(),
                    Comparator.nullsLast(BigDecimal::compareTo)).reversed();
        }
        if ("trust_level".equalsIgnoreCase(sortBy)) {
            return Comparator.<ContextSearchResult, Double>comparing(
                    result -> trustWeight(result.getItem().getTrustLevel())).reversed();
        }
        if ("updated_at".equalsIgnoreCase(sortBy)) {
            return Comparator.<ContextSearchResult, LocalDateTime>comparing(
                    result -> result.getItem().getUpdatedAt(),
                    Comparator.nullsLast(LocalDateTime::compareTo)).reversed();
        }
        return Comparator.<ContextSearchResult, Double>comparing(ContextSearchResult::getRankScore, Comparator.reverseOrder())
                .thenComparing(result -> result.getItem().getUpdatedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private double trustWeight(String trustLevel) {
        if (!StringUtils.hasText(trustLevel)) {
            return ContextTrustLevel.MEDIUM.weight();
        }
        return ContextTrustLevel.valueOf(trustLevel.trim().toUpperCase(Locale.ROOT)).weight();
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record KeywordSignals(boolean titleMatch,
                                  boolean summaryMatch,
                                  boolean contentMatch,
                                  boolean titleTokenMatch,
                                  boolean summaryTokenMatch,
                                  boolean contentTokenMatch,
                                  List<String> matchedTokens,
                                  int keywordTokenCount) {
        static KeywordSignals none() {
            return new KeywordSignals(false, false, false, false, false, false, List.of(), 0);
        }

        boolean anyMatch() {
            return titleMatch || summaryMatch || contentMatch || tokenMatch();
        }

        boolean tokenMatch() {
            return titleTokenMatch || summaryTokenMatch || contentTokenMatch;
        }
    }

    private record RankedHit(double rankScore, String hitReason, String scoreBreakdown) {
    }
}

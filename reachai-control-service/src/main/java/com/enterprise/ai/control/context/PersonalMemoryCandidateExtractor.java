package com.enterprise.ai.control.context;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-precision deterministic extraction before an optional model extractor is enabled.
 * It intentionally misses ambiguous memories rather than silently over-collecting user text.
 */
@Component
public class PersonalMemoryCandidateExtractor {

    static final String VERSION = "rules-v1";
    private static final int MAX_CANDIDATES = 3;
    private static final Pattern EXPLICIT_REMEMBER = Pattern.compile(
            "(?is)^(?:请)?(?:帮我)?记住(?:一下)?[\\s，,：:]*([^\\s].*)$");
    private static final Pattern EXPLICIT_FUTURE = Pattern.compile(
            "(?is)^(?:以后|今后|从现在开始|from now on)\\s*[,，:]?\\s*(?:请)?\\s*([^\\s].*)$");
    private static final List<Classifier> CLASSIFIERS = List.of(
            classifier("FACT", "姓名", "profile:name", "我(?:的名字)?叫\\s*(.+)", "我的名字是\\s*(.+)"),
            classifier("FACT", "常住地", "profile:location", "我(?:现在)?(?:住在|居住在|常住)\\s*(.+)", "i (?:currently )?live in\\s+(.+)"),
            classifier("FACT", "时区", "profile:timezone", "我的时区是\\s*(.+)", "my time ?zone is\\s+(.+)"),
            classifier("FACT", "岗位", "profile:role", "我的(?:岗位|职位|角色)是\\s*(.+)", "my (?:role|job title) is\\s+(.+)"),
            classifier("PREFERENCE", "个人偏好", null, "我(?:更)?(?:喜欢|偏好|希望)\\s*(.+)", "i (?:prefer|like|would like)\\s+(.+)")
    );

    public List<ExtractedCandidate> extract(String userMessage) {
        String message = trim(userMessage);
        if (!StringUtils.hasText(message) || message.length() > PersonalMemoryContentPolicy.MAX_CONTENT_CHARS) {
            return List.of();
        }
        Matcher remember = EXPLICIT_REMEMBER.matcher(message);
        if (remember.matches()) {
            ExtractedCandidate candidate = classify(remember.group(1), true, "EXPLICIT_REMEMBER");
            return candidate == null ? List.of() : List.of(candidate);
        }
        Matcher future = EXPLICIT_FUTURE.matcher(message);
        if (future.matches()) {
            String content = explicitFutureDirective(future.group(1));
            if (content == null) {
                return List.of();
            }
            String semanticKey = responseLanguageKey(content);
            return List.of(new ExtractedCandidate("RULE", semanticKey, "长期交互规则", content,
                    "EXPLICIT_FUTURE_RULE", BigDecimal.valueOf(0.98), true));
        }

        Map<String, ExtractedCandidate> unique = new LinkedHashMap<>();
        for (String clause : message.split("[。！？!?；;\\r\\n]+")) {
            ExtractedCandidate candidate = classify(clause, false, "PASSIVE_HIGH_PRECISION_RULE");
            if (candidate != null && PersonalMemoryContentPolicy.isSafe(candidate.content())) {
                unique.putIfAbsent(candidate.type() + "\n" + normalize(candidate.content()), candidate);
                if (unique.size() >= MAX_CANDIDATES) {
                    break;
                }
            }
        }
        return List.copyOf(unique.values());
    }

    /** True only for an explicit user instruction; safe to evaluate on the completed-turn thread. */
    public boolean hasExplicitRememberSignal(String userMessage) {
        String message = trim(userMessage);
        if (!StringUtils.hasText(message) || message.length() > PersonalMemoryContentPolicy.MAX_CONTENT_CHARS) {
            return false;
        }
        Matcher remember = EXPLICIT_REMEMBER.matcher(message);
        if (remember.matches()) {
            return classify(remember.group(1), true, "EXPLICIT_REMEMBER") != null;
        }
        Matcher future = EXPLICIT_FUTURE.matcher(message);
        return future.matches() && explicitFutureDirective(future.group(1)) != null;
    }

    /** Detects an explicit remember request even when its payload is unsafe or malformed. */
    public boolean hasExplicitRememberPrefix(String userMessage) {
        String message = trim(userMessage);
        return StringUtils.hasText(message)
                && EXPLICIT_REMEMBER.matcher(message).matches();
    }

    private ExtractedCandidate classify(String raw, boolean explicit, String reason) {
        String rawContent = trim(raw);
        String content = stripTerminalPunctuation(rawContent);
        if (!StringUtils.hasText(content) || looksLikeQuestion(rawContent) || looksLikeQuestion(content)
                || !PersonalMemoryContentPolicy.isSafe(content)) {
            return null;
        }
        for (Classifier classifier : CLASSIFIERS) {
            for (Pattern pattern : classifier.patterns()) {
                Matcher matcher = pattern.matcher(content);
                if (matcher.matches() && StringUtils.hasText(matcher.group(1))) {
                    String semanticKey = classifier.semanticKey();
                    if ("PREFERENCE".equals(classifier.type())) {
                        semanticKey = firstText(responseLanguageKey(content), semanticKey);
                    }
                    return new ExtractedCandidate(classifier.type(), semanticKey, classifier.title(), content,
                            reason, BigDecimal.valueOf(explicit ? 0.99 : 0.86), explicit);
                }
            }
        }
        if (!explicit) {
            return null;
        }
        String semanticKey = responseLanguageKey(content);
        return new ExtractedCandidate(semanticKey == null ? "NOTE" : "PREFERENCE", semanticKey,
                semanticKey == null ? "用户要求记住" : "回复语言", content,
                reason, BigDecimal.valueOf(0.97), true);
    }

    private static String explicitFutureDirective(String raw) {
        String rawContent = trim(raw);
        String content = stripTerminalPunctuation(rawContent);
        if (!StringUtils.hasText(content) || looksLikeQuestion(rawContent)
                || !PersonalMemoryContentPolicy.isSafe(content)) {
            return null;
        }
        String normalized = normalize(content);
        return containsAny(normalized,
                "请", "都", "不要", "别", "必须", "只", "使用", "用", "称呼", "叫我", "回答", "回复",
                "提醒", "优先", "避免", "please", "always", "never", "must", "use", "respond", "reply",
                "call me", "remind") ? content : null;
    }

    private static Classifier classifier(String type, String title, String semanticKey, String... expressions) {
        List<Pattern> patterns = new ArrayList<>();
        for (String expression : expressions) {
            patterns.add(Pattern.compile("(?iu)^" + expression + "$"));
        }
        return new Classifier(type, title, semanticKey, List.copyOf(patterns));
    }

    private static String responseLanguageKey(String value) {
        String normalized = normalize(value);
        boolean language = normalized.contains("中文") || normalized.contains("英文")
                || normalized.contains("英语") || normalized.contains("chinese") || normalized.contains("english");
        boolean response = normalized.contains("回答") || normalized.contains("回复")
                || normalized.contains("交流") || normalized.contains("respond") || normalized.contains("reply");
        return language && response ? "response-language" : null;
    }

    private static boolean looksLikeQuestion(String value) {
        String normalized = normalize(value);
        return normalized.endsWith("?") || normalized.endsWith("？")
                || normalized.endsWith("吗") || normalized.endsWith("么")
                || normalized.endsWith("如何") || normalized.endsWith("怎么样")
                || normalized.startsWith("是否")
                || normalized.startsWith("can you") || normalized.startsWith("could you")
                || normalized.startsWith("do you");
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String stripTerminalPunctuation(String value) {
        return value == null ? null : value.replaceFirst("[。.!！;；]+$", "").trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String firstText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    private record Classifier(String type, String title, String semanticKey, List<Pattern> patterns) {
    }

    public record ExtractedCandidate(String type,
                                     String semanticKey,
                                     String title,
                                     String content,
                                     String reason,
                                     BigDecimal confidence,
                                     boolean explicit) {
    }
}

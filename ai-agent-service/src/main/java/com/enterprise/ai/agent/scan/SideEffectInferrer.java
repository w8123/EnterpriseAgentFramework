package com.enterprise.ai.agent.scan;

import com.enterprise.ai.skill.SideEffectLevel;

import java.util.Locale;
import java.util.Set;

/**
 * 扫描期根据 HTTP method + URL 命名线索，为新 Tool 推断默认 {@link SideEffectLevel}。
 * <p>
 * 这只是保守默认值，管理员在 Tool 列表页可以覆盖；目标是让"没人标注的历史接口"也有一个
 * 不太离谱的初始副作用等级，便于后续 HITL（Phase 4.1）真正闸口化时不需要全员回填。
 * <p>
 * 规则优先级：
 * <ol>
 *   <li>DELETE / path 含 {@code delete|drop|purge|remove} → IRREVERSIBLE</li>
 *   <li>path 含 {@code refund|cancel|void} → IRREVERSIBLE</li>
 *   <li>GET / HEAD / path 以 {@code query|search|list|get|fetch|describe} 开头 → READ_ONLY</li>
 *   <li>PUT / path 含 {@code upsert|idempotent} → IDEMPOTENT_WRITE</li>
 *   <li>POST / PATCH → WRITE</li>
 *   <li>其它兜底 → WRITE</li>
 * </ol>
 */
public final class SideEffectInferrer {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> READ_PREFIX = Set.of(
            "query", "search", "list", "get", "fetch", "describe", "find", "view", "show", "lookup", "count", "exists");
    private static final Set<String> IRREVERSIBLE_KEYWORDS = Set.of(
            "delete", "drop", "purge", "remove", "refund", "cancel", "void", "destroy", "erase");
    private static final Set<String> IDEMPOTENT_KEYWORDS = Set.of("upsert", "idempotent", "merge");

    private SideEffectInferrer() {}

    public static SideEffectLevel infer(String httpMethod, String endpointPath) {
        String method = httpMethod == null ? "" : httpMethod.trim().toUpperCase(Locale.ROOT);
        String path = endpointPath == null ? "" : endpointPath.toLowerCase(Locale.ROOT);
        String lastSegment = lastSegment(path);

        if ("DELETE".equals(method)) {
            return SideEffectLevel.IRREVERSIBLE;
        }
        if (containsAny(path, IRREVERSIBLE_KEYWORDS)) {
            return SideEffectLevel.IRREVERSIBLE;
        }

        if (READ_METHODS.contains(method)) {
            return SideEffectLevel.READ_ONLY;
        }
        if (startsWithAny(lastSegment, READ_PREFIX)) {
            return SideEffectLevel.READ_ONLY;
        }

        if ("PUT".equals(method) || containsAny(path, IDEMPOTENT_KEYWORDS)) {
            return SideEffectLevel.IDEMPOTENT_WRITE;
        }

        if ("POST".equals(method) || "PATCH".equals(method)) {
            return SideEffectLevel.WRITE;
        }

        return SideEffectLevel.WRITE;
    }

    /** 与 {@link #infer(String, String)} 等价，但返回 DB 存储的字符串形式。 */
    public static String inferAsString(String httpMethod, String endpointPath) {
        return infer(httpMethod, endpointPath).name();
    }

    private static String lastSegment(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int end = path.endsWith("/") ? path.length() - 1 : path.length();
        int start = path.lastIndexOf('/', end - 1);
        return path.substring(start + 1, end);
    }

    private static boolean containsAny(String text, Set<String> keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(String segment, Set<String> prefixes) {
        for (String p : prefixes) {
            if (segment.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}

package com.enterprise.ai.runtime.trace;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persistence boundary for Supervisor Trace / Tool logs.
 * Runtime memory may keep full node results; DB entities only store allowlisted summaries.
 */
public final class WorkflowTraceSanitizer {

    private static final Set<String> SAFE_ARGUMENT_KEYS = Set.of(
            "toolname", "workflowid", "workflowversionid", "callno");
    private static final Set<String> SECRET_ARGUMENT_KEY_PARTS = Set.of(
            "secret", "token", "password", "authorization", "credential", "apikey", "api_key", "cookie");
    private static final Set<String> HTTP_SUMMARY_KEYS = Set.of(
            "statusCode", "bodyBytes", "redirectCount", "contentType", "durationMs");
    private static final Set<String> KNOWLEDGE_SUMMARY_KEYS = Set.of(
            "queryLength", "hitCount", "topK", "searchMode", "rerankApplied",
            "evidencePolicy", "outcome", "empty", "route",
            "knowledgeBaseCount", "failedKnowledgeBaseCount",
            "vectorRawCandidateCount", "vectorAcceptedCandidateCount",
            "keywordRawCandidateCount", "keywordAcceptedCandidateCount",
            "preMergeCandidateCount", "mergedCandidateCount", "rerankedCandidateCount",
            "scoreAcceptedCandidateCount", "scoreFilteredCandidateCount",
            "topKTruncatedCandidateCount", "returnedCandidateCount", "returnedHitCount",
            "returnedContentChars", "contentTruncatedCount", "budgetOmittedHitCount",
            "contentBudgetExhausted", "perHitContentLimit", "totalContentLimit",
            "retrievalStageMs", "rerankStageMs", "totalCostMs");
    private static final Set<String> PAGE_ACTION_SUMMARY_KEYS = Set.of(
            "actionKey", "success", "status", "outcomeClass", "businessOutcome",
            "total", "empty");
    private static final Set<String> SAFE_FINISH_KEYS = Set.of(
            "status", "code", "failureCode", "waiting", "planCount", "replanCount",
            "workflowCallCount", "toolCallCount", "guardDenyCount", "approvalCount",
            "tokenCost", "outcomeClass", "businessOutcome");
    private static final Pattern NODE_TYPE_PATTERN =
            Pattern.compile("\"type\"\\s*:\\s*\"([A-Za-z0-9_.-]{1,120})\"");

    private WorkflowTraceSanitizer() {
    }

    public static String sanitizeAnswer(String answer) {
        return StringUtils.hasText(answer) ? "[omitted]" : "";
    }

    /**
     * Records only the shape of an external request. Message and input values must never cross a
     * persistence boundary because they are caller-controlled and may contain secrets.
     */
    public static Map<String, Object> sanitizeInputSummary(Map<String, Object> input) {
        Map<String, Object> source = input == null ? Map.of() : input;
        boolean hasMessageKey = source.containsKey("message");
        Object rawMessage = hasMessageKey ? source.get("message") : source.get("input");
        String message = rawMessage == null ? null : String.valueOf(rawMessage);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inputType", hasMessageKey ? "message" : (source.containsKey("input") ? "input" : "none"));
        out.put("hasMessage", StringUtils.hasText(message));
        out.put("messageLength", message == null ? 0 : message.length());
        putScalar(out, "sessionId", source.get("sessionId"));
        putScalar(out, "agentId", source.get("agentId"));
        putScalar(out, "workflowId", source.get("workflowId"));
        putScalar(out, "traceId", source.get("traceId"));
        putScalar(out, "entryType", source.get("entryType"));
        return out;
    }

    public static Map<String, Object> sanitizeGuardMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : Set.of("policyProfile", "permissionKey", "riskLevel", "readOnly",
                "projectCode", "tenantId", "developmentBypass", "interactionId")) {
            putScalar(out, key, metadata.get(key));
        }
        Object args = metadata.get("args");
        if (args instanceof Map<?, ?> rawArgs) {
            Map<String, Object> typedArgs = new LinkedHashMap<>();
            rawArgs.forEach((key, value) -> {
                if (key != null) {
                    typedArgs.put(String.valueOf(key), value);
                }
            });
            out.put("args", sanitizeArgs(typedArgs));
        }
        return out;
    }

    public static String sanitizeRejectionSummary(String code) {
        if (!StringUtils.hasText(code) || !code.matches("[A-Za-z0-9_.-]{1,120}")) {
            return "[omitted]";
        }
        return "[rejected:" + code + "]";
    }

    public static Map<String, Object> sanitizeWorkflowResult(Map<String, Object> resultMetadata) {
        if (resultMetadata == null || resultMetadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        putScalar(out, "workflowId", resultMetadata.get("workflowId"));
        putScalar(out, "workflowVersionId", resultMetadata.get("workflowVersionId"));
        putScalar(out, "workflowVersion", resultMetadata.get("workflowVersion"));
        putScalar(out, "success", resultMetadata.get("success"));
        putScalar(out, "code", resultMetadata.get("code"));
        putScalar(out, "waiting", resultMetadata.get("waiting"));
        putScalar(out, "interactionId", resultMetadata.get("interactionId"));
        putScalar(out, "callNo", resultMetadata.get("callNo"));
        putScalar(out, "toolName", resultMetadata.get("toolName"));
        putScalar(out, "outcomeClass", resultMetadata.get("outcomeClass"));
        putScalar(out, "businessOutcome", resultMetadata.get("businessOutcome"));
        Object traces = resultMetadata.get("workflowNodeTraces");
        if (traces instanceof List<?> list) {
            out.put("workflowNodeTraces", sanitizeNodeTraces(list));
        }
        Object steps = resultMetadata.get("workflowSteps");
        if (steps instanceof List<?> list) {
            out.put("workflowStepCount", list.size());
        }
        return out;
    }

    public static Map<String, Object> sanitizeArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        args.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            String lower = key.toLowerCase(Locale.ROOT);
            if (containsSecretKeyPart(lower)) {
                out.put(key, "[redacted]");
                return;
            }
            out.put(key, SAFE_ARGUMENT_KEYS.contains(lower) && isScalar(value)
                    ? value
                    : typeTag(value));
        });
        return out;
    }

    public static Map<String, Object> sanitizeFinishMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : SAFE_FINISH_KEYS) {
            Object value = metadata.get(key);
            if (value != null && isScalar(value)) {
                out.put(key, value);
            }
        }
        return out;
    }

    /**
     * Final persistence boundary for runtime_run metadata. In particular, interaction cards,
     * request maps and execution payloads are intentionally not represented here.
     */
    public static Map<String, Object> sanitizeRunMetadata(Map<String, Object> metadata) {
        return sanitizeFinishMetadata(metadata);
    }

    /**
     * Final persistence boundary for debug child spans. A raw step can include model output,
     * HTTP headers/bodies and interaction cards, so retain only the existing node trace allowlist.
     */
    public static Map<String, Object> sanitizeDebugStepMetadata(Map<?, ?> step) {
        Map<String, Object> out = new LinkedHashMap<>(sanitizeNodeTrace(step));
        out.remove("traceSummary");
        Object rawSummary = step == null ? null : step.get("traceSummary");
        if (rawSummary instanceof Map<?, ?> summary) {
            Map<String, Object> safeSummary = sanitizeTraceSummary(text(step.get("nodeType")), summary);
            if (!safeSummary.isEmpty()) {
                out.put("traceSummary", safeSummary);
            }
        }
        return out;
    }

    /**
     * Stores GraphSpec observability facts without retaining executable configuration.
     */
    public static Map<String, Object> sanitizeWorkflowSnapshot(String graphSpecJson) {
        String graph = graphSpecJson == null ? "" : graphSpecJson;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graphSpecDigest", sha256(graph));
        List<String> nodeTypes = new ArrayList<>();
        Matcher matcher = NODE_TYPE_PATTERN.matcher(graph);
        while (matcher.find()) {
            nodeTypes.add(matcher.group(1));
        }
        out.put("nodeCount", nodeTypes.size());
        out.put("nodeTypes", nodeTypes.stream().distinct().toList());
        return out;
    }

    private static List<Map<String, Object>> sanitizeNodeTraces(List<?> traces) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object raw : traces) {
            if (!(raw instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> node = sanitizeNodeTrace(map);
            if (!node.isEmpty()) {
                out.add(node);
            }
        }
        return out;
    }

    public static Map<String, Object> sanitizeNodeTrace(Map<?, ?> trace) {
        if (trace == null || trace.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> node = new LinkedHashMap<>();
        putScalar(node, "nodeId", trace.get("nodeId"));
        putScalar(node, "nodeType", trace.get("nodeType"));
        putScalar(node, "nodeName", trace.get("nodeName"));
        putScalar(node, "qualifiedName", trace.get("qualifiedName"));
        putScalar(node, "status", trace.get("status"));
        putScalar(node, "attempt", trace.get("attempt"));
        putScalar(node, "maxAttempts", trace.get("maxAttempts"));
        putScalar(node, "errorPolicy", trace.get("errorPolicy"));
        putScalar(node, "failureCode", trace.get("failureCode"));
        putScalar(node, "failureCategory", trace.get("failureCategory"));
        putScalar(node, "retryableFailure", trace.get("retryableFailure"));
        for (String key : Set.of("sideEffect", "dispatchStage", "nonRecoverableWrite", "httpStatus", "inputField")) {
            putScalar(node, key, trace.get(key));
        }
        putScalar(node, "fallbackNodeId", trace.get("fallbackNodeId"));
        putScalar(node, "nextNodeId", trace.get("nextNodeId"));
        putScalar(node, "interactionId", trace.get("interactionId"));
        putScalar(node, "interactionType", trace.get("interactionType"));
        putScalar(node, "outcomeClass", trace.get("outcomeClass"));
        putScalar(node, "businessOutcome", trace.get("businessOutcome"));
        putScalar(node, "latencyMs", trace.get("latencyMs"));
        putScalar(node, "startedAt", trace.get("startedAt"));
        putScalar(node, "endedAt", trace.get("endedAt"));
        Object rawSummary = trace.get("traceSummary");
        if (rawSummary instanceof Map<?, ?> summary) {
            Map<String, Object> safeSummary = sanitizeTraceSummary(text(trace.get("nodeType")), summary);
            if (!safeSummary.isEmpty()) {
                node.put("traceSummary", safeSummary);
            }
        }
        return node;
    }

    public static Map<String, Object> sanitizePlanSummary(int planNo, Map<String, Object> plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planNo", planNo);
        Object rawSteps = plan == null ? null : plan.get("steps");
        if (rawSteps instanceof List<?> steps) {
            out.put("stepCount", steps.size());
            List<String> toolNames = new ArrayList<>();
            for (Object rawStep : steps) {
                if (rawStep instanceof Map<?, ?> step) {
                    String toolName = text(step.get("toolName"));
                    if (!StringUtils.hasText(toolName)) {
                        toolName = text(step.get("tool"));
                    }
                    if (StringUtils.hasText(toolName)) {
                        toolNames.add(limit(toolName, 200));
                    }
                }
            }
            out.put("toolNames", toolNames);
        } else {
            out.put("stepCount", 0);
            out.put("toolNames", List.of());
        }
        out.put("status", "RECORDED");
        return out;
    }

    private static Map<String, Object> sanitizeTraceSummary(String nodeType, Map<?, ?> summary) {
        Map<String, Object> out = new LinkedHashMap<>();
        if ("HTTP_REQUEST".equals(nodeType)) {
            for (String key : HTTP_SUMMARY_KEYS) {
                putScalar(out, key, summary.get(key));
            }
            String safeUrl = safeUrlSummary(summary.get("url"));
            if (StringUtils.hasText(safeUrl)) {
                out.put("url", safeUrl);
            }
        } else if ("KNOWLEDGE_RETRIEVAL".equals(nodeType)) {
            for (String key : KNOWLEDGE_SUMMARY_KEYS) {
                putScalar(out, key, summary.get(key));
            }
        } else if ("PAGE_ACTION".equals(nodeType)) {
            for (String key : PAGE_ACTION_SUMMARY_KEYS) {
                putScalar(out, key, summary.get(key));
            }
        }
        return out;
    }

    private static String safeUrlSummary(Object value) {
        String raw = text(value);
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            URI uri = URI.create(raw);
            if (!StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                return null;
            }
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean containsSecretKeyPart(String lowerKey) {
        return SECRET_ARGUMENT_KEY_PARTS.stream().anyMatch(lowerKey::contains);
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>;
    }

    private static String typeTag(Object value) {
        if (value == null) {
            return "[null]";
        }
        if (value instanceof Map<?, ?>) {
            return "[object]";
        }
        if (value instanceof List<?> || value instanceof Iterable<?> || value.getClass().isArray()) {
            return "[list]";
        }
        if (value instanceof Number) {
            return "[number]";
        }
        if (value instanceof Boolean) {
            return "[boolean]";
        }
        return "[string]";
    }

    private static void putScalar(Map<String, Object> out, String key, Object value) {
        if (value != null && isScalar(value)) {
            out.put(key, value);
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static String limit(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            return "[unavailable]";
        }
    }
}

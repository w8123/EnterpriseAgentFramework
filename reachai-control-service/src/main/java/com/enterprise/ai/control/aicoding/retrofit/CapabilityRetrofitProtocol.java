package com.enterprise.ai.control.aicoding.retrofit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Wire formats shared by the retrofit task provider, the console and the platform MCP tools.
 *
 * <p>The candidate list travels as the body of one ordinary task question (so both the HTTP task
 * protocol and the MCP task tools can carry it), and the developer's choice is that question's
 * answer. Both are plain JSON documents with their own schema names.</p>
 */
public final class CapabilityRetrofitProtocol {

    public static final String TASK_KIND = "CAPABILITY_RETROFIT";
    public static final String CANDIDATES_SCHEMA = "reachai.capability-retrofit-candidates.v1";
    public static final String SELECTION_SCHEMA = "reachai.capability-retrofit-selection.v1";
    public static final String SELECTION_QUESTION_PREFIX = "retrofit-select-";
    public static final String SELECTION_QUESTION_TITLE = "请勾选要开放给 AI 的业务能力";
    public static final int MAX_CANDIDATES = 20;
    public static final Set<String> SIDE_EFFECTS = Set.of("READ", "WRITE", "IRREVERSIBLE");
    private static final Pattern CANDIDATE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private CapabilityRetrofitProtocol() {
    }

    public static boolean isSelectionQuestion(String questionId) {
        return questionId != null && questionId.startsWith(SELECTION_QUESTION_PREFIX);
    }

    /** Validates and normalizes a candidate document; throws IllegalArgumentException with a Chinese reason. */
    public static ObjectNode normalizeCandidates(ObjectMapper json, JsonNode source) {
        if (source == null || !source.isObject()) {
            throw new IllegalArgumentException("候选清单必须是 JSON 对象");
        }
        JsonNode candidates = source.get("candidates");
        if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
            throw new IllegalArgumentException("candidates 至少需要一项");
        }
        if (candidates.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("一次最多提交 " + MAX_CANDIDATES + " 个候选能力，请分批改造");
        }
        ObjectNode root = json.createObjectNode();
        root.put("schema", CANDIDATES_SCHEMA);
        root.put("summary", limit(text(source.get("summary")), 1000));
        ArrayNode rows = root.putArray("candidates");
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode candidate : candidates) {
            if (candidate == null || !candidate.isObject()) {
                throw new IllegalArgumentException("candidates[] 必须是对象");
            }
            String key = text(candidate.get("candidateKey"));
            if (!StringUtils.hasText(key) || !CANDIDATE_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("candidateKey 只能用字母、数字和 . _ : -，长度不超过 128");
            }
            if (!keys.add(key)) {
                throw new IllegalArgumentException("candidateKey 重复：" + key);
            }
            String title = text(candidate.get("title"));
            if (!StringUtils.hasText(title)) {
                throw new IllegalArgumentException("候选 " + key + " 缺少中文名称 title");
            }
            String sideEffect = text(candidate.get("sideEffect")).toUpperCase(Locale.ROOT);
            if (!SIDE_EFFECTS.contains(sideEffect)) {
                throw new IllegalArgumentException("候选 " + key + " 的 sideEffect 必须是 READ、WRITE 或 IRREVERSIBLE");
            }
            String sourceFile = text(candidate.get("sourceFile"));
            if (!StringUtils.hasText(sourceFile)) {
                throw new IllegalArgumentException("候选 " + key + " 缺少 sourceFile");
            }
            ObjectNode row = rows.addObject();
            row.put("candidateKey", key);
            row.put("title", limit(title, 200));
            row.put("description", limit(text(candidate.get("description")), 1000));
            row.put("sideEffect", sideEffect);
            row.put("sourceFile", limit(sourceFile, 500));
            row.put("symbol", limit(text(candidate.get("symbol")), 300));
            row.put("httpMethod", limit(text(candidate.get("httpMethod")).toUpperCase(Locale.ROOT), 16));
            row.put("path", limit(text(candidate.get("path")), 500));
            row.put("reason", limit(text(candidate.get("reason")), 1000));
            row.put("risk", limit(text(candidate.get("risk")), 1000));
            row.put("recommended", !candidate.has("recommended") || candidate.get("recommended").asBoolean(true));
        }
        return root;
    }

    /** Reads a stored question body back; returns null when it is not a candidate document. */
    public static JsonNode parseCandidates(ObjectMapper json, String body) {
        if (!StringUtils.hasText(body)) return null;
        try {
            JsonNode node = json.readTree(body);
            return node != null && node.isObject() && CANDIDATES_SCHEMA.equals(text(node.get("schema"))) ? node : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static List<String> candidateKeys(JsonNode candidates) {
        List<String> keys = new ArrayList<>();
        if (candidates != null && candidates.path("candidates").isArray()) {
            candidates.path("candidates").forEach(row -> keys.add(text(row.get("candidateKey"))));
        }
        return keys;
    }

    /**
     * Accepts the console's JSON answer or a plain list ("a, b") typed by a developer in their own
     * coding tool. Unknown keys are rejected so a typo never silently widens the change.
     */
    public static List<String> parseSelection(ObjectMapper json, String answer, List<String> allowedKeys) {
        if (!StringUtils.hasText(answer)) return List.of();
        List<String> picked = new ArrayList<>();
        String trimmed = answer.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode node = json.readTree(trimmed);
                node.path("selected").forEach(item -> picked.add(text(item)));
            } catch (Exception invalid) {
                throw new IllegalArgumentException("勾选结果不是合法 JSON");
            }
        } else {
            for (String part : trimmed.split("[,，、\\s]+")) {
                if (StringUtils.hasText(part)) picked.add(part.trim());
            }
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String key : picked) {
            if (!StringUtils.hasText(key)) continue;
            if (allowedKeys != null && !allowedKeys.contains(key)) {
                throw new IllegalArgumentException("勾选了候选清单里没有的能力：" + key);
            }
            unique.add(key);
        }
        return List.copyOf(unique);
    }

    public static String selectionAnswer(ObjectMapper json, List<String> selected, String note) {
        ObjectNode root = json.createObjectNode();
        root.put("schema", SELECTION_SCHEMA);
        ArrayNode rows = root.putArray("selected");
        selected.forEach(rows::add);
        root.put("note", note == null ? "" : limit(note, 1000));
        return root.toString();
    }

    static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").trim();
    }

    private static String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}

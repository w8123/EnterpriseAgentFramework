package com.enterprise.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.repository.KnowledgeBaseLookup;
import com.enterprise.ai.repository.KnowledgeBaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Atomic settings edits shared by the console and future approved AI commands. Never indexes or reparses files. */
@Service
@RequiredArgsConstructor
public class KnowledgeSettingsService {
    private static final Set<String> FIELDS = Set.of("name", "description", "embeddingModelInstanceId", "llmModelInstanceId",
            "rerankModelInstanceId", "accessMode", "chunkSize", "chunkOverlap", "splitType", "searchMode", "topK",
            "similarityThreshold", "rerankEnabled", "vectorWeight", "keywordWeight");
    private final KnowledgeBaseRepository bases;
    private final ObjectMapper json;

    public Map<String, Object> context(String code) {
        return context(new KnowledgeBaseLookup(bases).requireByCode(identifier(code)));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preview(String code, String revision, Map<String, Object> changes) {
        var base = new KnowledgeBaseLookup(bases).requireByCode(identifier(code));
        requireRevision(base, revision);
        var result = context(base);
        result.put("after", changed(settings(base), changes));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(String code, String revision, Map<String, Object> changes) {
        var found = new KnowledgeBaseLookup(bases).requireByCode(identifier(code));
        var base = bases.lockById(found.getId());
        if (base == null || !code.equals(base.getCode())) throw changed();
        requireRevision(base, revision);
        var after = changed(settings(base), changes);
        var update = new UpdateWrapper<KnowledgeBase>().eq("id", base.getId()).eq("code", code)
                .eq("vector_collection_name", base.getVectorCollectionName());
        // The field whitelist owns column names; caller keys cannot introduce SQL or identity fields.
        for (String field : changes.keySet()) update.set(column(field), after.get(field));
        if (bases.update(null, update) != 1) throw changed();
        return context(bases.lockById(base.getId()));
    }

    private Map<String, Object> context(KnowledgeBase base) {
        var result = new LinkedHashMap<String, Object>();
        result.put("contractVersion", 1); result.put("kbCode", base.getCode()); result.put("knowledgeBaseId", base.getId());
        result.put("revision", revision(base)); result.put("dimension", base.getDimension()); result.put("resource", resource(base));
        result.put("before", settings(base));
        return result;
    }

    private Map<String, Object> settings(KnowledgeBase base) {
        var values = new LinkedHashMap<String, Object>();
        values.put("name", base.getName()); values.put("description", base.getDescription());
        values.put("embeddingModelInstanceId", base.getEmbeddingModelInstanceId()); values.put("llmModelInstanceId", base.getLlmModelInstanceId());
        values.put("rerankModelInstanceId", base.getRerankModelInstanceId()); values.put("accessMode", base.getAccessMode());
        values.put("chunkSize", base.getChunkSize()); values.put("chunkOverlap", base.getChunkOverlap()); values.put("splitType", base.getSplitType());
        values.put("searchMode", base.getSearchMode()); values.put("topK", base.getTopK()); values.put("similarityThreshold", base.getSimilarityThreshold());
        values.put("rerankEnabled", base.getRerankEnabled()); values.put("vectorWeight", base.getVectorWeight()); values.put("keywordWeight", base.getKeywordWeight());
        return values;
    }

    private Map<String, Object> resource(KnowledgeBase base) {
        var result = new LinkedHashMap<String, Object>();
        String scope = base.getScope() == null || base.getScope().isBlank() ? "WORKSPACE" : base.getScope().trim().toUpperCase(java.util.Locale.ROOT);
        String project = base.getProjectCode() == null || base.getProjectCode().isBlank() ? null : base.getProjectCode().trim();
        if (!Set.of("PROJECT", "WORKSPACE", "SHARED").contains(scope) || scope.equals("PROJECT") && project == null) throw changed();
        result.put("scope", scope); result.put("workspaceId", base.getWorkspaceId() == null || base.getWorkspaceId().isBlank() ? "default" : base.getWorkspaceId().trim());
        result.put("projectCode", project);
        return result;
    }

    private String revision(KnowledgeBase base) {
        try {
            if (base.getVectorCollectionName() == null || base.getVectorCollectionName().isBlank()) throw changed();
            var fingerprint = new TreeMap<String, Object>();
            fingerprint.put("settings", settings(base)); fingerprint.put("resource", resource(base)); fingerprint.put("id", base.getId());
            fingerprint.put("physicalCollection", base.getVectorCollectionName()); fingerprint.put("code", base.getCode());
            fingerprint.put("status", base.getStatus()); fingerprint.put("dimension", base.getDimension());
            fingerprint.put("updatedAt", base.getUpdateTime() == null ? null : base.getUpdateTime().toString());
            byte[] bytes = json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(fingerprint);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (ResponseStatusException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("Knowledge settings revision unavailable", failure); }
    }

    private void requireRevision(KnowledgeBase base, String expected) {
        if (expected == null || !expected.matches("[0-9a-f]{64}")
                || !MessageDigest.isEqual(revision(base).getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII))) throw changed();
    }

    private Map<String, Object> changed(Map<String, Object> before, Map<String, Object> changes) {
        if (changes == null || changes.isEmpty() || !FIELDS.containsAll(changes.keySet())) throw invalid();
        var after = new LinkedHashMap<>(before);
        changes.forEach((field, value) -> after.put(field, validate(field, value)));
        if (Boolean.TRUE.equals(after.get("rerankEnabled")) && (after.get("rerankModelInstanceId") == null || after.get("rerankModelInstanceId").toString().isBlank())) throw invalid();
        return after;
    }

    private Object validate(String field, Object value) {
        return switch (field) {
            case "name" -> text(value, 128, false);
            case "description" -> text(value, 512, true);
            case "embeddingModelInstanceId", "llmModelInstanceId" -> text(value, 64, false);
            case "rerankModelInstanceId" -> text(value, 64, true);
            case "accessMode" -> choice(value, Set.of("ALL_CALLERS", "FILE_GRANTS"));
            case "splitType" -> choice(value, Set.of("FIXED", "PARAGRAPH", "SEMANTIC"));
            case "searchMode" -> choice(value, Set.of("hybrid", "vector", "keyword"));
            case "chunkSize" -> integer(value, 100, 4000);
            case "chunkOverlap" -> integer(value, 0, 1000);
            case "topK" -> integer(value, 1, 20);
            case "rerankEnabled" -> { if (!(value instanceof Boolean)) throw invalid(); yield value; }
            case "similarityThreshold", "vectorWeight", "keywordWeight" -> {
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0 || number.doubleValue() > 1) throw invalid();
                yield number.floatValue();
            }
            default -> throw invalid();
        };
    }
    private Object choice(Object value, Set<String> choices) { if (!(value instanceof String text) || !choices.contains(text)) throw invalid(); return value; }
    private Object integer(Object value, int minimum, int maximum) {
        if (!(value instanceof Number)) throw invalid();
        try { int number = Integer.parseInt(value.toString()); if (number >= minimum && number <= maximum) return number; }
        catch (NumberFormatException ignored) { }
        throw invalid();
    }
    private Object text(Object value, int max, boolean nullable) {
        if (value == null && nullable) return null;
        if (!(value instanceof String text) || text.length() > max || !nullable && text.isBlank()) throw invalid();
        return text;
    }
    private String column(String field) { return field.replaceAll("([A-Z])", "_$1").toLowerCase(java.util.Locale.ROOT); }
    private String identifier(String value) { if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) throw invalid(); return value; }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid Knowledge settings changes"); }
    private static ResponseStatusException changed() { return new ResponseStatusException(HttpStatus.CONFLICT, "Knowledge settings changed; read the current revision"); }
}

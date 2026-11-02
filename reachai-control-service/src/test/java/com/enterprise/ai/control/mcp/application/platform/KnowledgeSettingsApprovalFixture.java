package com.enterprise.ai.control.mcp.application.platform;

import java.util.LinkedHashMap;
import java.util.Map;

/** Full owner projection for protocol/approval fixtures, including nullable and floating-point settings. */
public final class KnowledgeSettingsApprovalFixture {
    private KnowledgeSettingsApprovalFixture() { }
    public static Map<String, Object> preview(String code, String revision, Map<String, Object> changes) {
        var before = new LinkedHashMap<String, Object>();
        before.put("name", "协作知识库"); before.put("description", "private-fixture-description");
        before.put("embeddingModelInstanceId", "embedding"); before.put("llmModelInstanceId", "llm"); before.put("rerankModelInstanceId", null);
        before.put("accessMode", "FILE_GRANTS"); before.put("chunkSize", 500); before.put("chunkOverlap", 50); before.put("splitType", "FIXED");
        before.put("searchMode", "hybrid"); before.put("topK", 5); before.put("similarityThreshold", 0.5f); before.put("rerankEnabled", false);
        before.put("vectorWeight", 0.7f); before.put("keywordWeight", 0.3f);
        var after = new LinkedHashMap<>(before); after.putAll(changes);
        var result = new LinkedHashMap<String, Object>();
        result.put("contractVersion", 1); result.put("kbCode", code); result.put("revision", revision); result.put("knowledgeBaseId", 7L);
        result.put("dimension", 1536); result.put("resource", Map.of("scope", "PROJECT", "workspaceId", "team", "projectCode", "orders"));
        result.put("before", before); result.put("after", after); return result;
    }
    public static Map<String, Object> saved(String code, String revision, Map<String, Object> changes) {
        var result = preview(code, revision, changes); result.put("before", result.remove("after")); return result;
    }
}

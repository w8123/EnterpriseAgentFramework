package com.enterprise.ai.model.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 解析供应商模型列表。兼容 OpenAI 风格 {@code {"data":[{"id":...}]}}（阿里云百炼、DeepSeek、OpenAI、
 * Gemini OpenAI 兼容层、火山方舟等）以及 Gemini 原生 {@code {"models":[{"name":"models/..."}]}}。
 */
public final class UpstreamModelListParser {

    static final int MAX_MODELS = 2_000;

    private static final Pattern RERANK = Pattern.compile("rerank");
    private static final Pattern EMBEDDING = Pattern.compile("embed|bge-(?!rerank)|text-similarity");
    private static final Pattern OTHER = Pattern.compile(
            "tts|asr|whisper|speech|audio|realtime|transcri|paraformer|sambert|cosyvoice|"
                    + "dall-e|image|wanx|wan2|flux|stable-diffusion|sora|video|moderation|"
                    + "-ocr|ocr-|livetranslate|gummy");

    private UpstreamModelListParser() {
    }

    public static List<UpstreamModel> parse(JsonNode root) {
        JsonNode items = root == null ? null : root.path("data");
        if (items == null || !items.isArray()) {
            items = root == null ? null : root.path("models");
        }
        if (items == null || !items.isArray()) {
            if (root != null && root.isArray()) {
                items = root;
            } else {
                throw new IllegalArgumentException("Model list response does not contain a data/models array");
            }
        }
        Map<String, UpstreamModel> unique = new LinkedHashMap<>();
        for (JsonNode item : items) {
            String id = text(item, "id");
            if (id == null) id = text(item, "name");
            if (id == null && item.isTextual()) id = item.asText().trim();
            if (id == null || id.isEmpty()) continue;
            if (id.startsWith("models/")) id = id.substring("models/".length());
            Long created = item.hasNonNull("created") && item.get("created").canConvertToLong()
                    ? item.get("created").asLong() : null;
            unique.putIfAbsent(id, new UpstreamModel(id, text(item, "owned_by"), created, inferType(id)));
            if (unique.size() >= MAX_MODELS) break;
        }
        List<UpstreamModel> models = new ArrayList<>(unique.values());
        models.sort(Comparator
                .comparing((UpstreamModel model) -> model.created() == null ? Long.MIN_VALUE : model.created())
                .reversed()
                .thenComparing(UpstreamModel::id));
        return models;
    }

    public static String inferType(String modelId) {
        String id = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);
        if (RERANK.matcher(id).find()) return "RERANKER";
        if (EMBEDDING.matcher(id).find()) return "EMBEDDING";
        if (OTHER.matcher(id).find()) return "OTHER";
        return "LLM";
    }

    private static String text(JsonNode item, String field) {
        if (item == null || !item.isObject()) return null;
        JsonNode value = item.get(field);
        if (value == null || !value.isTextual()) return null;
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
}

package com.enterprise.ai.agent.tool.retrieval;

import com.enterprise.ai.agent.config.ToolRetrievalProperties;
import com.enterprise.ai.agent.tool.log.ToolCallLogService;
import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionMapper;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.milvus.client.MilvusServiceClient;

/**
 * Tool 语义召回服务。
 * <p>
 * 输入 user query + {@link RetrievalScope}，输出相似度降序的 {@link ToolCandidate} top-K；
 * Milvus 异常或返回为空都走降级路径（交由调用方处理）。
 */
@Slf4j
@Service
public class ToolRetrievalService {

    private final MilvusServiceClient milvus;
    private final EmbeddingClient embeddingClient;
    private final ToolRetrievalProperties properties;
    private final ToolEmbeddingService embeddingService;
    private final ToolDefinitionMapper toolDefinitionMapper;
    private final ToolCallLogService toolCallLogService;

    public ToolRetrievalService(MilvusServiceClient milvus,
                                EmbeddingClient embeddingClient,
                                ToolRetrievalProperties properties,
                                ToolEmbeddingService embeddingService,
                                ToolDefinitionMapper toolDefinitionMapper,
                                ToolCallLogService toolCallLogService) {
        this.milvus = milvus;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
        this.embeddingService = embeddingService;
        this.toolDefinitionMapper = toolDefinitionMapper;
        this.toolCallLogService = toolCallLogService;
    }

    /**
     * 检索 top-K tool 候选。任何异常/未就绪情况返回空列表，由上层决定降级策略。
     */
    public List<ToolCandidate> retrieve(String query, RetrievalScope scope, int topK) {
        return retrieve(query, scope, topK, null);
    }

    /**
     * 同 {@link #retrieve(String, RetrievalScope, int)}，在提供 {@code auditContext} 时写入向量化 / Milvus 检索 Trace。
     */
    public List<ToolCandidate> retrieve(String query, RetrievalScope scope, int topK,
                                        ToolExecutionContext auditContext) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        if (!embeddingService.isReady()) {
            log.debug("[ToolRetrieval] collection 未就绪，跳过检索");
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int k = topK > 0 ? topK : properties.getTopK();
        RetrievalScope effectiveScope = scope == null ? RetrievalScope.agentRuntime(null) : scope;

        // whitelist 空列表（而非 null）表示 "硬限制为空集"，没必要去 Milvus 查
        if (effectiveScope.toolWhitelist() != null && effectiveScope.toolWhitelist().isEmpty()) {
            return List.of();
        }

        try {
            long tEmbed = System.currentTimeMillis();
            List<Float> queryVector = embeddingClient.embed(query);
            long embedMs = System.currentTimeMillis() - tEmbed;
            logEmbeddingSpan(auditContext, query, queryVector, embedMs);

            String expr = buildExpression(effectiveScope);
            SearchParam.Builder builder = SearchParam.newBuilder()
                    .withCollectionName(properties.getCollectionName())
                    .withMetricType(MetricType.COSINE)
                    .withTopK(k)
                    .withVectors(List.of(queryVector))
                    .withVectorFieldName(ToolEmbeddingService.F_VECTOR)
                    .withOutFields(List.of(
                            ToolEmbeddingService.F_ID,
                            ToolEmbeddingService.F_PROJECT,
                            ToolEmbeddingService.F_MODULE,
                            ToolEmbeddingService.F_TEXT))
                    .withParams("{\"nprobe\":16}");
            if (expr != null && !expr.isBlank()) {
                builder.withExpr(expr);
            }
            long tSearch = System.currentTimeMillis();
            R<SearchResults> resp = milvus.search(builder.build());
            long searchMs = System.currentTimeMillis() - tSearch;
            if (resp.getException() != null) {
                log.warn("[ToolRetrieval] Milvus 搜索异常: {}", resp.getException().toString());
                logMilvusSpan(auditContext, query, expr, k, List.of(), searchMs,
                        "Milvus: " + resp.getException());
                return List.of();
            }
            SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
            List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);
            if (idScores == null || idScores.isEmpty()) {
                logMilvusSpan(auditContext, query, expr, k, List.of(), searchMs, null);
                return List.of();
            }

            List<Long> toolIds = idScores.stream()
                    .map(SearchResultsWrapper.IDScore::getLongID)
                    .toList();
            Map<Long, ToolDefinitionEntity> byId = toolDefinitionMapper.selectBatchIds(toolIds)
                    .stream()
                    .collect(Collectors.toMap(ToolDefinitionEntity::getId, t -> t, (a, b) -> a));

            double minScore = properties.getMinScore();
            List<ToolCandidate> candidates = new ArrayList<>();
            List<?> rowRecords = wrapper.getRowRecords(0);
            for (int i = 0; i < idScores.size(); i++) {
                SearchResultsWrapper.IDScore idScore = idScores.get(i);
                if (idScore.getScore() < minScore) {
                    continue;
                }
                ToolDefinitionEntity tool = byId.get(idScore.getLongID());
                if (tool == null) {
                    continue;
                }
                String text = null;
                if (rowRecords != null && i < rowRecords.size()) {
                    Object raw = wrapper.getFieldData(ToolEmbeddingService.F_TEXT, 0).get(i);
                    text = raw == null ? null : raw.toString();
                }
                candidates.add(new ToolCandidate(
                        tool.getId(),
                        tool.getName(),
                        tool.getProjectId(),
                        tool.getModuleId(),
                        idScore.getScore(),
                        text
                ));
            }
            logMilvusSpan(auditContext, query, expr, k, candidates, searchMs, null);
            return Collections.unmodifiableList(candidates);
        } catch (Exception ex) {
            log.warn("[ToolRetrieval] 检索异常，返回空结果: {}", ex.toString());
            logRetrievalFailureSpan(auditContext, query, ex);
            return List.of();
        }
    }

    private void logEmbeddingSpan(ToolExecutionContext ctx, String query, List<Float> vector, long elapsedMs) {
        if (toolCallLogService == null || ctx == null || ctx.getTraceId() == null || ctx.getTraceId().isBlank()) {
            return;
        }
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("queryText", truncate(query, 4000));
            args.put("provider", blankToNull(properties.getEmbeddingProvider()));
            args.put("model", blankToNull(properties.getEmbeddingModel()));
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("dimensions", vector == null ? 0 : vector.size());
            res.put("vectorPreview", previewVector(vector, 12));
            res.put("elapsedMs", elapsedMs);
            toolCallLogService.record(ctx, "_trace:embedding.encode", args, res, true, null, elapsedMs, null);
        } catch (Exception ignored) {
        }
    }

    private void logMilvusSpan(ToolExecutionContext ctx,
                               String query,
                               String expr,
                               int topK,
                               List<ToolCandidate> candidates,
                               long elapsedMs,
                               String errorNote) {
        if (toolCallLogService == null || ctx == null || ctx.getTraceId() == null || ctx.getTraceId().isBlank()) {
            return;
        }
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("queryText", truncate(query, 2000));
            args.put("collection", properties.getCollectionName());
            args.put("topK", topK);
            args.put("expr", expr == null ? null : truncate(expr, 6000));
            args.put("metric", "COSINE");
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("hitCount", candidates == null ? 0 : candidates.size());
            res.put("candidates", summarizeCandidatesForTrace(candidates));
            res.put("elapsedMs", elapsedMs);
            if (errorNote != null) {
                res.put("error", errorNote);
            }
            boolean ok = errorNote == null;
            toolCallLogService.record(ctx, "_trace:milvus.tool_search", args, res, ok,
                    ok ? null : "MILVUS", elapsedMs, null);
        } catch (Exception ignored) {
        }
    }

    private void logRetrievalFailureSpan(ToolExecutionContext ctx, String query, Exception ex) {
        if (toolCallLogService == null || ctx == null || ctx.getTraceId() == null || ctx.getTraceId().isBlank()) {
            return;
        }
        try {
            Map<String, Object> args = Map.of("queryText", truncate(query, 2000));
            Map<String, Object> res = Map.of("error", ex.getClass().getSimpleName() + ": " + safeMsg(ex.getMessage()));
            toolCallLogService.record(ctx, "_trace:tool_retrieval.failed", args, res, false,
                    ex.getClass().getSimpleName(), 0L, null);
        } catch (Exception ignored) {
        }
    }

    private static List<Double> previewVector(List<Float> vector, int n) {
        if (vector == null || vector.isEmpty()) {
            return List.of();
        }
        int limit = Math.min(n, vector.size());
        List<Double> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            out.add(vector.get(i) == null ? null : vector.get(i).doubleValue());
        }
        return out;
    }

    private List<Map<String, Object>> summarizeCandidatesForTrace(List<ToolCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ToolCandidate c : candidates) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("toolId", c.toolId());
            row.put("toolName", c.toolName());
            row.put("score", c.score());
            row.put("indexedText", truncate(c.text(), 400));
            rows.add(row);
        }
        return rows;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (max <= 0 || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...[truncated]";
    }

    private static String safeMsg(String m) {
        return m == null ? "" : m;
    }

    /**
     * 根据 scope 构造 Milvus boolean 过滤表达式；返回 null 表示无约束。
     */
    String buildExpression(RetrievalScope scope) {
        if (scope == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (scope.enabledOnly()) {
            parts.add(ToolEmbeddingService.F_ENABLED + " == true");
        }
        if (scope.agentVisibleOnly()) {
            parts.add(ToolEmbeddingService.F_VISIBLE + " == true");
        }
        appendInList(parts, ToolEmbeddingService.F_ID, scope.toolWhitelist());
        appendInList(parts, ToolEmbeddingService.F_PROJECT, scope.projectIds());
        appendInList(parts, ToolEmbeddingService.F_MODULE, scope.moduleIds());
        appendStringInList(parts, ToolEmbeddingService.F_KIND, scope.kinds());
        if (parts.isEmpty()) {
            return null;
        }
        return String.join(" && ", parts);
    }

    private static void appendInList(List<String> parts, String field, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String list = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        parts.add(field + " in [" + list + "]");
    }

    private static void appendStringInList(List<String> parts, String field, java.util.Set<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        String list = values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(v -> "\"" + v.trim().toUpperCase().replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(","));
        if (list.isEmpty()) {
            return;
        }
        parts.add(field + " in [" + list + "]");
    }

    /**
     * 方便调用方封装成可追溯的 retrievalTrace JSON（Phase 2 Skill Mining 用）。
     */
    public List<Map<String, Object>> toTrace(List<ToolCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> trace = new ArrayList<>();
        for (ToolCandidate c : candidates) {
            Map<String, Object> row = new HashMap<>();
            row.put("toolId", c.toolId());
            row.put("toolName", c.toolName());
            row.put("score", c.score());
            trace.add(row);
        }
        return trace;
    }
}

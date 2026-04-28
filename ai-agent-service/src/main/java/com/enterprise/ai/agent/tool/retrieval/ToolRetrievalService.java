package com.enterprise.ai.agent.tool.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.config.DomainProperties;
import com.enterprise.ai.agent.config.ToolRetrievalProperties;
import com.enterprise.ai.agent.domain.DomainAssignmentService;
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
import java.util.Set;
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
    private final DomainAssignmentService domainAssignmentService;
    private final DomainProperties domainProperties;

    public ToolRetrievalService(MilvusServiceClient milvus,
                                EmbeddingClient embeddingClient,
                                ToolRetrievalProperties properties,
                                ToolEmbeddingService embeddingService,
                                ToolDefinitionMapper toolDefinitionMapper,
                                ToolCallLogService toolCallLogService,
                                DomainAssignmentService domainAssignmentService,
                                DomainProperties domainProperties) {
        this.milvus = milvus;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
        this.embeddingService = embeddingService;
        this.toolDefinitionMapper = toolDefinitionMapper;
        this.toolCallLogService = toolCallLogService;
        this.domainAssignmentService = domainAssignmentService;
        this.domainProperties = domainProperties;
    }

    /**
     * 检索 top-K tool 候选。任何异常/未就绪情况返回空列表，由上层决定降级策略。
     */
    public List<ToolCandidate> retrieve(String query, RetrievalScope scope, int topK) {
        return retrieve(query, scope, topK, null, null);
    }

    /**
     * 同 {@link #retrieve(String, RetrievalScope, int)}，在提供 {@code auditContext} 时写入向量化 / Milvus 检索 Trace。
     */
    public List<ToolCandidate> retrieve(String query, RetrievalScope scope, int topK,
                                        ToolExecutionContext auditContext) {
        return retrieve(query, scope, topK, auditContext, null);
    }

    /**
     * 同 {@link #retrieve(String, RetrievalScope, int, ToolExecutionContext)}，
     * {@code minScoreOverride} 非空时覆盖配置的相似度下限（0 表示不按阈值过滤）；管理端检索测试可用。
     */
    public List<ToolCandidate> retrieve(String query, RetrievalScope scope, int topK,
                                        ToolExecutionContext auditContext, Double minScoreOverride) {
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
                return finishWithOptionalKeywordFallback(query, effectiveScope, k, List.of());
            }
            SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
            List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);
            if (idScores == null || idScores.isEmpty()) {
                logMilvusSpan(auditContext, query, expr, k, List.of(), searchMs, null);
                return finishWithOptionalKeywordFallback(query, effectiveScope, k, List.of());
            }

            List<Long> toolIds = idScores.stream()
                    .map(SearchResultsWrapper.IDScore::getLongID)
                    .toList();
            Map<Long, ToolDefinitionEntity> byId = toolDefinitionMapper.selectBatchIds(toolIds)
                    .stream()
                    .collect(Collectors.toMap(ToolDefinitionEntity::getId, t -> t, (a, b) -> a));

            double minScore = resolveMinScore(minScoreOverride);
            List<ToolCandidate> candidates = new ArrayList<>();
            List<?> rowRecords = wrapper.getRowRecords(0);
            for (int i = 0; i < idScores.size(); i++) {
                SearchResultsWrapper.IDScore idScore = idScores.get(i);
                if (minScore > 0 && idScore.getScore() < minScore) {
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
            List<ToolCandidate> domainFiltered = applyDomainSoftFilter(candidates, effectiveScope);
            return finishWithOptionalKeywordFallback(query, effectiveScope, k, domainFiltered);
        } catch (Exception ex) {
            log.warn("[ToolRetrieval] 检索异常，尝试关键词兜底: {}", ex.toString());
            logRetrievalFailureSpan(auditContext, query, ex);
            List<ToolCandidate> fb = keywordFallbackSearch(query, effectiveScope, k);
            if (!fb.isEmpty()) {
                log.debug("[ToolRetrieval] 异常后关键词兜底 {} 条", fb.size());
                return Collections.unmodifiableList(fb);
            }
            return List.of();
        }
    }

    private double resolveMinScore(Double override) {
        if (override == null) {
            return properties.getMinScore();
        }
        double v = override;
        if (v < 0) {
            return 0;
        }
        if (v > 1) {
            return 1;
        }
        return v;
    }

    /**
     * BUGFIX：向量分数低于阈值或 Milvus 无命中时仍可能「应召回」——例如用户粘贴长描述的一小段，
     * 余弦相似度偏低。此时用语义字段子串匹配兜底（与 {@link ToolEmbeddingService#buildText} 数据源一致）。
     */
    private List<ToolCandidate> finishWithOptionalKeywordFallback(String query,
                                                                  RetrievalScope scope,
                                                                  int topK,
                                                                  List<ToolCandidate> vectorHits) {
        if (vectorHits != null && !vectorHits.isEmpty()) {
            return Collections.unmodifiableList(vectorHits);
        }
        List<ToolCandidate> fb = keywordFallbackSearch(query, scope, topK);
        if (fb.isEmpty()) {
            return List.of();
        }
        log.debug("[ToolRetrieval] 向量阶段无可用命中，关键词兜底 {} 条", fb.size());
        return Collections.unmodifiableList(fb);
    }

    private List<ToolCandidate> keywordFallbackSearch(String query, RetrievalScope scope, int topK) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2) {
            return List.of();
        }
        String pattern = escapeForLike(q);
        LambdaQueryWrapper<ToolDefinitionEntity> w = new LambdaQueryWrapper<>();
        w.and(x -> x.like(ToolDefinitionEntity::getDescription, pattern)
                .or()
                .like(ToolDefinitionEntity::getAiDescription, pattern)
                .or()
                .like(ToolDefinitionEntity::getName, pattern));
        applyScopeToKeywordQuery(w, scope);
        int limit = Math.min(Math.max(topK, 1), 100);
        w.last("LIMIT " + limit);
        List<ToolDefinitionEntity> rows = toolDefinitionMapper.selectList(w);
        if (rows.isEmpty()) {
            return List.of();
        }
        final float fallbackScore = 0.92f;
        List<ToolCandidate> out = new ArrayList<>(rows.size());
        for (ToolDefinitionEntity tool : rows) {
            String text = ToolEmbeddingService.buildText(tool);
            out.add(new ToolCandidate(
                    tool.getId(),
                    tool.getName(),
                    tool.getProjectId(),
                    tool.getModuleId(),
                    fallbackScore,
                    text));
        }
        return out;
    }

    private static void applyScopeToKeywordQuery(LambdaQueryWrapper<ToolDefinitionEntity> w, RetrievalScope scope) {
        if (scope == null) {
            return;
        }
        if (scope.enabledOnly()) {
            w.eq(ToolDefinitionEntity::getEnabled, true);
        }
        if (scope.agentVisibleOnly()) {
            w.eq(ToolDefinitionEntity::getAgentVisible, true);
        }
        if (scope.toolWhitelist() != null && !scope.toolWhitelist().isEmpty()) {
            w.in(ToolDefinitionEntity::getId, scope.toolWhitelist());
        }
        if (scope.projectIds() != null && !scope.projectIds().isEmpty()) {
            w.in(ToolDefinitionEntity::getProjectId, scope.projectIds());
        }
        if (scope.moduleIds() != null && !scope.moduleIds().isEmpty()) {
            w.in(ToolDefinitionEntity::getModuleId, scope.moduleIds());
        }
        Set<String> kinds = scope.kinds();
        if (kinds != null && !kinds.isEmpty()) {
            w.and(outer -> {
                boolean first = true;
                for (String raw : kinds) {
                    if (raw == null || raw.isBlank()) {
                        continue;
                    }
                    String k = raw.trim().toUpperCase();
                    if (first) {
                        if ("TOOL".equals(k)) {
                            outer.nested(sub -> sub.isNull(ToolDefinitionEntity::getKind)
                                    .or()
                                    .eq(ToolDefinitionEntity::getKind, "TOOL"));
                        } else {
                            outer.eq(ToolDefinitionEntity::getKind, k);
                        }
                        first = false;
                    } else if ("TOOL".equals(k)) {
                        outer.or(sub -> sub.isNull(ToolDefinitionEntity::getKind)
                                .or()
                                .eq(ToolDefinitionEntity::getKind, "TOOL"));
                    } else {
                        outer.or().eq(ToolDefinitionEntity::getKind, k);
                    }
                }
            });
        }
    }

    private static String escapeForLike(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
     * 领域软过滤：仅当 {@code scope.domains()} 非空时启用。
     * <ul>
     *     <li>若 candidate 在 {@code domain_assignment} 中关联了 {@code scope.domains()} 任意一个 domain → 保留；</li>
     *     <li>若 candidate 没有任何 domain 归属 → 默认保留（避免历史 Tool 全部被过滤掉）；</li>
     *     <li>若过滤后整体为空且 {@code ai.domain.soft-fallback=true} → 退回原始候选集。</li>
     * </ul>
     */
    List<ToolCandidate> applyDomainSoftFilter(List<ToolCandidate> candidates, RetrievalScope scope) {
        if (candidates == null || candidates.isEmpty()) return candidates;
        if (scope == null || scope.domains() == null || scope.domains().isEmpty()) return candidates;
        if (domainAssignmentService == null) return candidates;

        List<String> names = new ArrayList<>();
        for (ToolCandidate c : candidates) names.add(c.toolName());
        Map<String, Set<String>> deptByName;
        try {
            // 简化处理：把 TOOL/SKILL 一起按 name 查（domain_assignment 用 (kind, name) 复合键，但不同 kind name 通常不冲突）
            Map<String, Set<String>> tools = domainAssignmentService.domainsByTargetName("TOOL", names);
            Map<String, Set<String>> skills = domainAssignmentService.domainsByTargetName("SKILL", names);
            deptByName = new HashMap<>(tools);
            for (Map.Entry<String, Set<String>> e : skills.entrySet()) {
                deptByName.merge(e.getKey(), e.getValue(), (a, b) -> {
                    Set<String> merged = new java.util.LinkedHashSet<>(a);
                    merged.addAll(b);
                    return merged;
                });
            }
        } catch (Exception ex) {
            log.debug("[DomainFilter] 查询 domain_assignment 失败，跳过过滤: {}", ex.toString());
            return candidates;
        }

        List<ToolCandidate> kept = new ArrayList<>();
        for (ToolCandidate c : candidates) {
            Set<String> domains = deptByName.get(c.toolName());
            if (domains == null || domains.isEmpty()) {
                kept.add(c); // 未挂 domain 的视为通用工具，保留
                continue;
            }
            boolean any = false;
            for (String d : scope.domains()) {
                if (domains.contains(d)) { any = true; break; }
            }
            if (any) kept.add(c);
        }
        boolean soft = domainProperties == null || domainProperties.isSoftFallback();
        if (kept.isEmpty() && soft) {
            log.debug("[DomainFilter] 过滤后为空，软回退到原始候选 {} 条", candidates.size());
            return candidates;
        }
        return kept;
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

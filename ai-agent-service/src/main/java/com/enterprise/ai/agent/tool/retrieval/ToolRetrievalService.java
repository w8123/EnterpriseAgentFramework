package com.enterprise.ai.agent.tool.retrieval;

import com.enterprise.ai.agent.config.ToolRetrievalProperties;
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

    public ToolRetrievalService(MilvusServiceClient milvus,
                                EmbeddingClient embeddingClient,
                                ToolRetrievalProperties properties,
                                ToolEmbeddingService embeddingService,
                                ToolDefinitionMapper toolDefinitionMapper) {
        this.milvus = milvus;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
        this.embeddingService = embeddingService;
        this.toolDefinitionMapper = toolDefinitionMapper;
    }

    /**
     * 检索 top-K tool 候选。任何异常/未就绪情况返回空列表，由上层决定降级策略。
     */
    public List<ToolCandidate> retrieve(String query, RetrievalScope scope, int topK) {
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
            List<Float> queryVector = embeddingClient.embed(query);
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
            R<SearchResults> resp = milvus.search(builder.build());
            if (resp.getException() != null) {
                log.warn("[ToolRetrieval] Milvus 搜索异常: {}", resp.getException().toString());
                return List.of();
            }
            SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
            List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);
            if (idScores == null || idScores.isEmpty()) {
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
            return Collections.unmodifiableList(candidates);
        } catch (Exception ex) {
            log.warn("[ToolRetrieval] 检索异常，返回空结果: {}", ex.toString());
            return List.of();
        }
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

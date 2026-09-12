package com.enterprise.ai.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.client.ModelServiceClient;
import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.domain.dto.RetrievalTestRequest;
import com.enterprise.ai.domain.dto.RetrievalTestResponse;
import com.enterprise.ai.domain.entity.Chunk;
import com.enterprise.ai.domain.entity.FileInfo;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.domain.entity.KnowledgeHitLog;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.repository.ChunkRepository;
import com.enterprise.ai.repository.FileInfoRepository;
import com.enterprise.ai.repository.KnowledgeBaseLookup;
import com.enterprise.ai.repository.KnowledgeHitLogRepository;
import com.enterprise.ai.vector.VectorSearchRequest;
import com.enterprise.ai.vector.VectorSearchResult;
import com.enterprise.ai.vector.VectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.*;
import java.util.stream.Collectors;
import static com.enterprise.ai.domain.KnowledgeBaseSettings.normalizeSearchMode;
import static com.enterprise.ai.domain.KnowledgeBaseSettings.requireEmbeddingModelInstanceId;
import static com.enterprise.ai.domain.KnowledgeBaseSettings.requireVectorCollectionName;

/** Retrieval execution owns recall, merge, rerank and hit evidence; catalog and file mutations stay outside. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultKnowledgeRetrievalEngine implements KnowledgeRetrievalEngine {
    private final KnowledgeBaseLookup knowledgeBaseLookup;
    private final FileInfoRepository fileInfoRepository;
    private final ChunkRepository chunkRepository;
    private final KnowledgeHitLogRepository knowledgeHitLogRepository;
    private final EmbeddingService embeddingService;
    private final ModelServiceClient modelServiceClient;
    private final VectorService vectorService;
    private final KnowledgeRetrievalAuthorization authorization;

    @Value("${rag.default-top-k:5}")
    private int defaultTopK;
    @Value("${rag.score-threshold:0.5}")
    private float defaultScoreThreshold;

    /**
     * Production retrieval engine shared by admin retrievalTest and {@link com.enterprise.ai.retrieval.KnowledgeRetrievalCore}.
     */
    @Override
    public RetrievalTestResponse execute(RetrievalTestRequest request) {
        long start = System.currentTimeMillis();
        int topK = request.getTopK() != null ? request.getTopK() : defaultTopK;
        float threshold = request.getScoreThreshold() != null ? request.getScoreThreshold() : defaultScoreThreshold;
        String searchMode = normalizeSearchMode(request.getSearchMode(), "hybrid");
        Set<String> allowedFiles = request.getAccessibleFileIds() == null ? null : new HashSet<>(request.getAccessibleFileIds());

        List<KnowledgeBase> knowledgeBases = knowledgeBaseLookup.resolveActive(request.getKnowledgeBaseCodes());

        List<RetrievalTestResponse.RetrievalItem> allItems = new ArrayList<>();
        int vectorRawCandidateCount = 0;
        int vectorAcceptedCandidateCount = 0;
        int keywordRawCandidateCount = 0;
        int keywordAcceptedCandidateCount = 0;
        int failedKnowledgeBaseCount = 0;
        long retrievalStageStartedAt = System.currentTimeMillis();
        for (KnowledgeBase kb : knowledgeBases) {
            try {
                if (!"keyword".equals(searchMode)) {
                    List<Float> queryVector = embeddingService.embed(requireEmbeddingModelInstanceId(kb), request.getQuery());
                    VectorSearchRequest.VectorSearchRequestBuilder searchBuilder = VectorSearchRequest.builder()
                            .collectionName(requireVectorCollectionName(kb))
                            .queryVector(queryVector)
                            .topK(topK)
                            .outputFields(List.of("id", "file_id", "content"));
                    if (StringUtils.hasText(request.getFileIdFilterExpression())) {
                        searchBuilder.filterExpression(request.getFileIdFilterExpression());
                    }
                    List<VectorSearchResult> results = vectorService.search(searchBuilder.build());
                    if (results == null) {
                        results = List.of();
                    }
                    vectorRawCandidateCount += results.size();

                    for (VectorSearchResult sr : results) {
                        if (sr.getScore() >= threshold) {
                            Chunk chunk = findChunkByVectorId(kb.getId(), sr.getId());
                            if (chunk == null || (chunk.getEnabled() != null && chunk.getEnabled() == 0)) {
                                continue;
                            }
                            if (allowedFiles != null && !allowedFiles.contains(chunk.getFileId())) continue;
                            RetrievalTestResponse.RetrievalItem item = buildRetrievalItem(kb, chunk);
                            item.setVectorScore(sr.getScore());
                            item.setScore(sr.getScore());
                            item.setReason("vector");
                            allItems.add(item);
                            vectorAcceptedCandidateCount++;
                        }
                    }
                }
                if (!"vector".equals(searchMode)) {
                    List<Chunk> keywordCandidates = keywordSearch(kb.getId(), request.getQuery(), topK * 3);
                    if (keywordCandidates == null) {
                        keywordCandidates = List.of();
                    }
                    keywordRawCandidateCount += keywordCandidates.size();
                    for (Chunk chunk : keywordCandidates) {
                        if (allowedFiles != null
                                && (chunk.getFileId() == null || !allowedFiles.contains(String.valueOf(chunk.getFileId())))) {
                            continue;
                        }
                        float keywordScore = keywordScore(request.getQuery(), chunk.getContent());
                        if (keywordScore >= threshold) {
                            RetrievalTestResponse.RetrievalItem item = buildRetrievalItem(kb, chunk);
                            item.setKeywordScore(keywordScore);
                            item.setScore(keywordScore);
                            item.setReason("keyword");
                            allItems.add(item);
                            keywordAcceptedCandidateCount++;
                        }
                    }
                }
            } catch (Exception e) {
                failedKnowledgeBaseCount++;
                log.warn("检索知识库 {} 失败: {}", kb.getCode(), e.getMessage());
            }
        }
        long retrievalStageMs = Math.max(0L, System.currentTimeMillis() - retrievalStageStartedAt);
        int preMergeCandidateCount = allItems.size();

        // 按分数降序排列，取 topK
        Map<String, KnowledgeBase> kbMap = knowledgeBases.stream()
                .collect(Collectors.toMap(KnowledgeBase::getCode, kb -> kb, (a, b) -> a));
        Map<String, RetrievalTestResponse.RetrievalItem> merged = new LinkedHashMap<>();
        for (RetrievalTestResponse.RetrievalItem item : allItems) {
            String key = item.getChunkDbId() != null ? "db:" + item.getChunkDbId() : "vec:" + item.getChunkId();
            RetrievalTestResponse.RetrievalItem existing = merged.get(key);
            if (existing == null) {
                merged.put(key, item);
            } else {
                if (item.getVectorScore() != null) existing.setVectorScore(item.getVectorScore());
                if (item.getKeywordScore() != null) existing.setKeywordScore(item.getKeywordScore());
            }
        }
        allItems = new ArrayList<>(merged.values());
        allItems = authorization.retain(request.getAccessSnapshot(), allItems);
        int mergedCandidateCount = allItems.size();
        long rerankStageStartedAt = System.currentTimeMillis();
        applyModelRerank(request.getQuery(), kbMap, allItems, request.getRerankEnabled(), request.getAccessSnapshot());
        long rerankStageMs = Math.max(0L, System.currentTimeMillis() - rerankStageStartedAt);
        for (RetrievalTestResponse.RetrievalItem item : allItems) {
            KnowledgeBase kb = kbMap.get(item.getKnowledgeBaseCode());
            float vectorWeight = request.getVectorWeight() != null ? request.getVectorWeight()
                    : (kb != null ? valueOrDefault(kb.getVectorWeight(), 0.7f) : 0.7f);
            float keywordWeight = request.getKeywordWeight() != null ? request.getKeywordWeight()
                    : (kb != null ? valueOrDefault(kb.getKeywordWeight(), 0.3f) : 0.3f);
            boolean useRerank = request.getRerankEnabled() != null ? request.getRerankEnabled()
                    : kb == null || Boolean.TRUE.equals(kb.getRerankEnabled());
            scoreItem(request, kb, searchMode, vectorWeight, keywordWeight, useRerank, item);
        }
        int rerankedCandidateCount = (int) allItems.stream()
                .filter(item -> item.getRerankScore() != null)
                .count();
        allItems = allItems.stream()
                .filter(item -> item.getScore() != null && item.getScore() >= threshold)
                .collect(Collectors.toList());
        int scoreAcceptedCandidateCount = allItems.size();

        allItems.sort(Comparator.comparingDouble(RetrievalTestResponse.RetrievalItem::getScore).reversed());
        List<RetrievalTestResponse.RetrievalItem> topItems = allItems.stream()
                .limit(topK).collect(Collectors.toList());

        // 补充文件名和chunkIndex
        enrichRetrievalItems(topItems);
        topItems = authorization.retain(request.getAccessSnapshot(), topItems);
        if (Boolean.TRUE.equals(request.getRecordHit())) {
            if (topItems.isEmpty()) {
                recordMisses(request, knowledgeBases);
            } else {
                recordHits(request, topItems);
            }
        }
        RetrievalTestResponse.RetrievalItem direct = topItems.stream()
                .filter(item -> Boolean.TRUE.equals(item.getDirectReturn()))
                .findFirst()
                .orElse(null);

        long costMs = System.currentTimeMillis() - start;
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("knowledgeBaseCount", knowledgeBases.size());
        diagnostics.put("failedKnowledgeBaseCount", failedKnowledgeBaseCount);
        diagnostics.put("vectorRawCandidateCount", vectorRawCandidateCount);
        diagnostics.put("vectorAcceptedCandidateCount", vectorAcceptedCandidateCount);
        diagnostics.put("keywordRawCandidateCount", keywordRawCandidateCount);
        diagnostics.put("keywordAcceptedCandidateCount", keywordAcceptedCandidateCount);
        diagnostics.put("preMergeCandidateCount", preMergeCandidateCount);
        diagnostics.put("mergedCandidateCount", mergedCandidateCount);
        diagnostics.put("rerankedCandidateCount", rerankedCandidateCount);
        diagnostics.put("scoreAcceptedCandidateCount", scoreAcceptedCandidateCount);
        diagnostics.put("scoreFilteredCandidateCount",
                Math.max(0, mergedCandidateCount - scoreAcceptedCandidateCount));
        diagnostics.put("topKTruncatedCandidateCount",
                Math.max(0, scoreAcceptedCandidateCount - topItems.size()));
        diagnostics.put("returnedCandidateCount", topItems.size());
        diagnostics.put("retrievalStageMs", retrievalStageMs);
        diagnostics.put("rerankStageMs", rerankStageMs);
        diagnostics.put("totalCostMs", costMs);
        return RetrievalTestResponse.builder()
                .query(request.getQuery())
                .searchMode(searchMode)
                .totalResults(topItems.size())
                .costMs(costMs)
                .directReturn(direct != null)
                .directReturnContent(direct != null ? direct.getContent() : null)
                .items(topItems)
                .diagnostics(diagnostics)
                .build();
    }

    /**
     * 为检索结果项补充文件名和chunkIndex
     */
    private void enrichRetrievalItems(List<RetrievalTestResponse.RetrievalItem> items) {
        if (items == null || items.isEmpty()) return;
        Set<String> fileIds = items.stream()
                .map(RetrievalTestResponse.RetrievalItem::getFileId)
                .collect(Collectors.toSet());
        List<FileInfo> files = fileInfoRepository.selectList(
                new LambdaQueryWrapper<FileInfo>().in(FileInfo::getFileId, fileIds));
        Map<String, String> fileNameMap = files.stream()
                .collect(Collectors.toMap(FileInfo::getFileId, FileInfo::getFileName, (a, b) -> a));

        // 从 chunkId 中提取 chunkIndex（格式: fileId_chunk_N）
        items.forEach(item -> {
            item.setFileName(fileNameMap.getOrDefault(item.getFileId(), "未知文件"));
            String chunkId = item.getChunkId();
            if (chunkId != null && chunkId.contains("_chunk_")) {
                try {
                    String indexStr = chunkId.substring(chunkId.lastIndexOf("_chunk_") + 7);
                    item.setChunkIndex(Integer.parseInt(indexStr));
                } catch (NumberFormatException e) {
                    item.setChunkIndex(null);
                }
            }
        });
    }

    private RetrievalTestResponse.RetrievalItem buildRetrievalItem(KnowledgeBase kb, Chunk chunk) {
        return RetrievalTestResponse.RetrievalItem.builder()
                .chunkDbId(chunk.getId())
                .chunkId(chunk.getVectorId())
                .fileId(chunk.getFileId())
                .content(chunk.getContent())
                .score(0f)
                .hitCount(chunk.getHitCount() != null ? chunk.getHitCount() : 0)
                .knowledgeBaseCode(kb.getCode())
                .chunkIndex(chunk.getChunkIndex())
                .build();
    }

    private Chunk findChunkByVectorId(Long knowledgeBaseId, String vectorId) {
        if (!StringUtils.hasText(vectorId)) {
            return null;
        }
        return chunkRepository.selectOne(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getKnowledgeBaseId, knowledgeBaseId)
                .eq(Chunk::getVectorId, vectorId)
                .last("LIMIT 1"));
    }

    private List<Chunk> keywordSearch(Long knowledgeBaseId, String queryText, int limit) {
        List<String> terms = tokenize(queryText);
        if (terms.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<Chunk> query = new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getKnowledgeBaseId, knowledgeBaseId)
                .ne(Chunk::getEnabled, 0)
                .and(wrapper -> {
                    for (String term : terms) {
                        wrapper.or().like(Chunk::getContent, term);
                    }
                })
                .last("LIMIT " + Math.max(1, limit));
        return chunkRepository.selectList(query);
    }

    private float keywordScore(String queryText, String content) {
        List<String> terms = tokenize(queryText);
        if (terms.isEmpty() || content == null || content.isBlank()) {
            return 0f;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        long matches = terms.stream().filter(lower::contains).count();
        return Math.min(1f, (float) matches / (float) terms.size());
    }

    private float lightweightRerankScore(String queryText, String content) {
        float keyword = keywordScore(queryText, content);
        int length = content != null ? content.length() : 0;
        float lengthScore = length > 0 && length <= 1200 ? 1f : 0.85f;
        return Math.min(1f, keyword * 0.85f + lengthScore * 0.15f);
    }

    private void scoreItem(RetrievalTestRequest request, KnowledgeBase kb, String searchMode,
                           float vectorWeight, float keywordWeight, boolean useRerank,
                           RetrievalTestResponse.RetrievalItem item) {
        float vectorScore = item.getVectorScore() != null ? item.getVectorScore() : 0f;
        float keywordScore = item.getKeywordScore() != null ? item.getKeywordScore() : 0f;
        float score = switch (searchMode) {
            case "vector" -> vectorScore;
            case "keyword" -> keywordScore;
            default -> vectorScore * vectorWeight + keywordScore * keywordWeight;
        };
        if (useRerank) {
            float rerank = item.getRerankScore() != null
                    ? item.getRerankScore()
                    : lightweightRerankScore(request.getQuery(), item.getContent());
            item.setRerankScore(rerank);
            score = score * 0.8f + rerank * 0.2f;
        }
        item.setScore(score);
        item.setReason(reasonFor(item, searchMode, useRerank));
        item.setDirectReturn(isDirectReturn(request, kb, score));
    }

    private String reasonFor(RetrievalTestResponse.RetrievalItem item, String searchMode, boolean useRerank) {
        return searchMode + (useRerank ? " + rerank" : "")
                + " (vector=" + formatScore(item.getVectorScore())
                + ", keyword=" + formatScore(item.getKeywordScore()) + ")";
    }

    private String formatScore(Float score) {
        return score == null ? "0.000" : String.format(Locale.ROOT, "%.3f", score);
    }

    private boolean isDirectReturn(RetrievalTestRequest request, KnowledgeBase kb, float score) {
        boolean enabled = request.getDirectReturnEnabled() != null
                ? request.getDirectReturnEnabled()
                : kb == null || Boolean.TRUE.equals(kb.getDirectReturnEnabled());
        float threshold = request.getDirectReturnThreshold() != null
                ? request.getDirectReturnThreshold()
                : (kb != null ? valueOrDefault(kb.getDirectReturnThreshold(), 0.9f) : 0.9f);
        return enabled && score >= threshold;
    }

    private void recordHits(RetrievalTestRequest request, List<RetrievalTestResponse.RetrievalItem> items) {
        for (RetrievalTestResponse.RetrievalItem item : items) {
            if (item.getChunkDbId() == null) {
                continue;
            }
            Chunk chunk = chunkRepository.selectById(item.getChunkDbId());
            KnowledgeBase kb = knowledgeBaseLookup.requireByCode(item.getKnowledgeBaseCode());
            if (chunk != null) {
                chunk.setHitCount((chunk.getHitCount() != null ? chunk.getHitCount() : 0) + 1);
                chunkRepository.updateById(chunk);
                item.setHitCount(chunk.getHitCount());
            }
            KnowledgeHitLog log = new KnowledgeHitLog();
            log.setKnowledgeBaseId(kb.getId());
            log.setChunkId(item.getChunkDbId());
            log.setQueryText(request.getQuery());
            log.setSearchMode(request.getSearchMode());
            log.setScore(item.getScore());
            log.setDirectReturn(Boolean.TRUE.equals(item.getDirectReturn()) ? 1 : 0);
            log.setTraceId(request.getTraceId());
            log.setUserId(request.getUserId());
            knowledgeHitLogRepository.insert(log);
        }
    }

    private void applyModelRerank(String query,
                                  Map<String, KnowledgeBase> kbMap,
                                  List<RetrievalTestResponse.RetrievalItem> items,
                                  Boolean requestRerankEnabled,
                                  com.enterprise.ai.security.FileAccessSnapshot snapshot) {
        if (items == null || items.isEmpty()) {
            return;
        }
        if (Boolean.FALSE.equals(requestRerankEnabled)) {
            return;
        }
        Map<String, List<RetrievalTestResponse.RetrievalItem>> byKb = items.stream()
                .filter(item -> item.getContent() != null && !item.getContent().isBlank())
                .collect(Collectors.groupingBy(RetrievalTestResponse.RetrievalItem::getKnowledgeBaseCode));
        byKb.forEach((kbCode, group) -> {
            KnowledgeBase kb = kbMap.get(kbCode);
            boolean enabled = requestRerankEnabled != null
                    ? Boolean.TRUE.equals(requestRerankEnabled)
                    : kb != null && Boolean.TRUE.equals(kb.getRerankEnabled());
            if (kb == null || !enabled || kb.getRerankModelInstanceId() == null || kb.getRerankModelInstanceId().isBlank()) {
                return;
            }
            var readable = authorization.retain(snapshot, group);
            items.removeIf(item -> group.contains(item) && !readable.contains(item));
            if (readable.isEmpty()) return;
            try {
                List<String> documents = readable.stream().map(RetrievalTestResponse.RetrievalItem::getContent).toList();
                ApiResult<ModelServiceClient.RerankResult> result = modelServiceClient.rerank(
                        new ModelServiceClient.RerankParam(kb.getRerankModelInstanceId(), query, documents, documents.size(), Map.of()));
                if (result.getCode() != 200 || result.getData() == null || result.getData().results() == null) {
                    log.warn("Rerank failed for kb={}, message={}", kbCode, result.getMessage());
                    return;
                }
                for (ModelServiceClient.RerankItem rerankItem : result.getData().results()) {
                    int index = rerankItem.index();
                    if (index >= 0 && index < readable.size()) {
                        readable.get(index).setRerankScore(rerankItem.score());
                    }
                }
            } catch (Exception e) {
                log.warn("Rerank model failed for kb={}, error={}", kbCode, e.getMessage());
            }
        });
    }

    private void recordMisses(RetrievalTestRequest request, List<KnowledgeBase> knowledgeBases) {
        for (KnowledgeBase kb : knowledgeBases) {
            KnowledgeHitLog log = new KnowledgeHitLog();
            log.setKnowledgeBaseId(kb.getId());
            log.setQueryText(request.getQuery());
            log.setSearchMode(request.getSearchMode());
            log.setScore(0f);
            log.setDirectReturn(0);
            log.setTraceId(request.getTraceId());
            log.setUserId(request.getUserId());
            knowledgeHitLogRepository.insert(log);
        }
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[\\s,.;:!?，。；：！？、()（）\\[\\]{}]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .limit(16)
                .collect(Collectors.toList());
    }


    private float valueOrDefault(Float value, float fallback) {
        return value != null ? value : fallback;
    }
}

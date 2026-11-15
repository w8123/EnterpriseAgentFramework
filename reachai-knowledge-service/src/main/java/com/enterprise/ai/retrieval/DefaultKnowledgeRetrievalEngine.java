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

    private com.enterprise.ai.external.ExternalKnowledgeService external;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setExternalKnowledgeService(com.enterprise.ai.external.ExternalKnowledgeService external) {
        this.external = external;
    }

    @Value("${rag.default-top-k:5}")
    private int defaultTopK;
    @Value("${rag.score-threshold:0.5}")
    private float defaultScoreThreshold;
    @Value("${rag.vector-retry-delay-ms:300}")
    private long vectorRetryDelayMs = 300;

    private static final int VECTOR_RECALL_ATTEMPTS = 2;

    /**
     * Production retrieval engine shared by admin retrievalTest and {@link com.enterprise.ai.retrieval.KnowledgeRetrievalCore}.
     */
    @Override
    public RetrievalTestResponse execute(RetrievalTestRequest request) {
        long start = System.currentTimeMillis();
        Set<String> allowedFiles = request.getAccessibleFileIds() == null ? null : new HashSet<>(request.getAccessibleFileIds());
        var accessSnapshot = request.getAccessSnapshot();

        List<KnowledgeBase> knowledgeBases = knowledgeBaseLookup.resolveActive(request.getKnowledgeBaseCodes());
        // Values the caller left empty come from the knowledge base's own retrieval settings.
        int topK = request.getTopK() != null ? request.getTopK()
                : knowledgeBases.stream().map(KnowledgeBase::getTopK).filter(java.util.Objects::nonNull)
                        .max(Integer::compare).orElse(defaultTopK);
        String searchMode = normalizeSearchMode(request.getSearchMode(),
                knowledgeBases.size() == 1 ? normalizeSearchMode(knowledgeBases.get(0).getSearchMode(), "hybrid") : "hybrid");

        List<RetrievalTestResponse.RetrievalItem> allItems = new ArrayList<>();
        // Passages from external knowledge bases keep the provider's own score and skip local merge/rerank.
        List<RetrievalTestResponse.RetrievalItem> externalItems = new ArrayList<>();
        int externalCandidateCount = 0;
        int vectorRawCandidateCount = 0;
        int vectorAcceptedCandidateCount = 0;
        int keywordRawCandidateCount = 0;
        int keywordAcceptedCandidateCount = 0;
        int failedKnowledgeBaseCount = 0;
        long retrievalStageStartedAt = System.currentTimeMillis();
        for (KnowledgeBase kb : knowledgeBases) {
            float threshold = request.getScoreThreshold() != null ? request.getScoreThreshold()
                    : valueOrDefault(kb.getSimilarityThreshold(), defaultScoreThreshold);
            int kbTopK = request.getTopK() != null ? request.getTopK() : (kb.getTopK() != null ? kb.getTopK() : topK);
            // ALL_CALLERS bases are readable by any trusted caller; FILE_GRANTS bases keep the per-file filter.
            boolean openBase = accessSnapshot != null && accessSnapshot.isOpen(kb.getId());
            Set<String> kbAllowedFiles = openBase ? null : allowedFiles;
            // No grant can match this restricted base. Avoid calling external providers
            // or reporting their failures for data this request cannot access.
            if (accessSnapshot != null && !openBase && (kbAllowedFiles == null || kbAllowedFiles.isEmpty())) {
                continue;
            }
            if (com.enterprise.ai.domain.KnowledgeBaseSettings.isExternal(kb)) {
                try {
                    if (external == null) throw new IllegalStateException("外部知识库功能未启用");
                    var hits = external.search(kb, request.getQuery(), kbTopK, threshold);
                    externalCandidateCount += hits.size();
                    int rank = 0;
                    for (var hit : hits) {
                        externalItems.add(externalItem(kb, hit, rank++));
                    }
                } catch (Exception e) {
                    failedKnowledgeBaseCount++;
                    log.warn("检索外部知识库 {} 失败: {}", kb.getCode(), e.getMessage());
                }
                continue;
            }
            try {
                if (!"keyword".equals(searchMode)) {
                    List<VectorSearchResult> results = vectorRecall(request, kb, kbTopK, openBase);
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
                            if (kbAllowedFiles != null && !kbAllowedFiles.contains(chunk.getFileId())) continue;
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
                    List<Chunk> keywordCandidates = keywordSearch(kb.getId(), request.getQuery(), kbTopK * 3);
                    if (keywordCandidates == null) {
                        keywordCandidates = List.of();
                    }
                    keywordRawCandidateCount += keywordCandidates.size();
                    for (Chunk chunk : keywordCandidates) {
                        if (kbAllowedFiles != null
                                && (chunk.getFileId() == null || !kbAllowedFiles.contains(String.valueOf(chunk.getFileId())))) {
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
        // The threshold already gated each recall channel on its own similarity scale. The blended
        // score only ranks: a chunk recalled by one channel scores 0 in the other, so re-applying
        // the same threshold here silently dropped relevant hits (e.g. vector 0.52 → hybrid 0.36).
        allItems = allItems.stream()
                .filter(item -> item.getScore() != null)
                .collect(Collectors.toList());
        int scoreAcceptedCandidateCount = allItems.size();
        // External bases are readable here only when open to this caller (checked above); they join the ranking as-is.
        allItems.addAll(externalItems);

        allItems.sort(Comparator.comparingDouble(RetrievalTestResponse.RetrievalItem::getScore).reversed());
        List<RetrievalTestResponse.RetrievalItem> topItems = allItems.stream()
                .limit(topK).collect(Collectors.toList());

        // 补充文件名和chunkIndex
        enrichRetrievalItems(topItems);
        topItems = authorization.retain(request.getAccessSnapshot(), topItems);
        // A base that failed (model service or vector store not ready yet, e.g. right after a restart)
        // is not evidence of "no answer": recording it as a miss pollutes the hit log, and callers
        // reject partial results anyway. Only a complete retrieval is recorded.
        if (Boolean.TRUE.equals(request.getRecordHit()) && failedKnowledgeBaseCount == 0) {
            if (topItems.isEmpty()) {
                recordMisses(request, knowledgeBases, searchMode);
            } else {
                recordHits(request, topItems, searchMode);
            }
        }
        long costMs = System.currentTimeMillis() - start;
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("knowledgeBaseCount", knowledgeBases.size());
        diagnostics.put("failedKnowledgeBaseCount", failedKnowledgeBaseCount);
        diagnostics.put("externalCandidateCount", externalCandidateCount);
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
                .items(topItems)
                .diagnostics(diagnostics)
                .build();
    }

    /**
     * 为检索结果项补充文件名和chunkIndex
     */
    private void enrichRetrievalItems(List<RetrievalTestResponse.RetrievalItem> items) {
        if (items == null || items.isEmpty()) return;
        items = items.stream().filter(item -> item.getExternalProvider() == null).toList();
        if (items.isEmpty()) return;
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

    /** fileId/chunkId are namespaced so they can never collide with, or be mistaken for, local identifiers. */
    static RetrievalTestResponse.RetrievalItem externalItem(KnowledgeBase kb, com.enterprise.ai.external.ExternalKnowledgeHit hit, int rank) {
        String provider = kb.getExternalProvider() == null ? "EXTERNAL" : kb.getExternalProvider();
        String documentId = hit.documentId() == null || hit.documentId().isBlank() ? "doc" : hit.documentId();
        String hitId = hit.id() == null || hit.id().isBlank() ? documentId + "#" + rank : hit.id();
        String documentName = hit.documentName() == null || hit.documentName().isBlank() ? "外部文档" : hit.documentName();
        return RetrievalTestResponse.RetrievalItem.builder()
                .chunkId("ext:" + kb.getCode() + ":" + hitId)
                .fileId("ext:" + provider.toLowerCase(Locale.ROOT) + ":" + documentId)
                .fileName(documentName)
                .content(hit.content())
                .score(hit.score())
                .vectorScore(hit.score())
                .hitCount(0)
                .knowledgeBaseCode(kb.getCode())
                .reason("external " + provider + " (score=" + String.format(Locale.ROOT, "%.3f", hit.score()) + ")")
                .externalProvider(provider)
                .externalKnowledgeBaseId(kb.getId())
                .build();
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
    }

    private String reasonFor(RetrievalTestResponse.RetrievalItem item, String searchMode, boolean useRerank) {
        return searchMode + (useRerank ? " + rerank" : "")
                + " (vector=" + formatScore(item.getVectorScore())
                + ", keyword=" + formatScore(item.getKeywordScore()) + ")";
    }

    /**
     * Vector recall for one base. The first call after a service restart can hit a cold dependency
     * (model-service connection, embedding provider, Milvus collection loading); one quick retry
     * keeps that transient failure from turning a real answer into "knowledge unavailable".
     */
    private List<VectorSearchResult> vectorRecall(RetrievalTestRequest request, KnowledgeBase kb,
                                                  int kbTopK, boolean openBase) {
        RuntimeException first = null;
        for (int attempt = 1; attempt <= VECTOR_RECALL_ATTEMPTS; attempt++) {
            try {
                List<Float> queryVector = embeddingService.embed(requireEmbeddingModelInstanceId(kb), request.getQuery());
                VectorSearchRequest.VectorSearchRequestBuilder searchBuilder = VectorSearchRequest.builder()
                        .collectionName(requireVectorCollectionName(kb))
                        .queryVector(queryVector)
                        .topK(kbTopK)
                        .outputFields(List.of("id", "file_id", "content"));
                if (!openBase && StringUtils.hasText(request.getFileIdFilterExpression())) {
                    searchBuilder.filterExpression(request.getFileIdFilterExpression());
                }
                return vectorService.search(searchBuilder.build());
            } catch (IllegalArgumentException misconfigured) {
                throw misconfigured; // Missing model/collection settings never heal by retrying.
            } catch (RuntimeException e) {
                if (first == null) first = e;
                if (attempt < VECTOR_RECALL_ATTEMPTS) {
                    log.warn("知识库 {} 向量检索第 {} 次失败，稍后重试: {}", kb.getCode(), attempt, e.getMessage());
                    sleepBeforeRetry();
                }
            }
        }
        throw first;
    }

    private void sleepBeforeRetry() {
        if (vectorRetryDelayMs <= 0) return;
        try {
            Thread.sleep(vectorRetryDelayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private String formatScore(Float score) {
        return score == null ? "0.000" : String.format(Locale.ROOT, "%.3f", score);
    }

    private void recordHits(RetrievalTestRequest request, List<RetrievalTestResponse.RetrievalItem> items, String searchMode) {
        for (RetrievalTestResponse.RetrievalItem item : items) {
            if (item.getExternalProvider() != null && item.getExternalKnowledgeBaseId() != null) {
                // External passages have no local chunk row; log the hit against the base only.
                KnowledgeHitLog log = new KnowledgeHitLog();
                log.setKnowledgeBaseId(item.getExternalKnowledgeBaseId());
                log.setQueryText(request.getQuery());
                log.setSearchMode("external");
                log.setScore(item.getScore());
                log.setSource(hitSource(request));
                log.setTraceId(request.getTraceId());
                log.setUserId(request.getUserId());
                knowledgeHitLogRepository.insert(log);
                continue;
            }
            if (item.getChunkDbId() == null) {
                continue;
            }
            KnowledgeBase kb = knowledgeBaseLookup.requireByCode(item.getKnowledgeBaseCode());
            // Increment in place: never write a previously read chunk row (vector reference) back.
            if (chunkRepository.incrementHitCount(item.getChunkDbId()) == 1) {
                item.setHitCount((item.getHitCount() != null ? item.getHitCount() : 0) + 1);
            }
            KnowledgeHitLog log = new KnowledgeHitLog();
            log.setKnowledgeBaseId(kb.getId());
            log.setChunkId(item.getChunkDbId());
            log.setQueryText(request.getQuery());
            log.setSearchMode(searchMode);
            log.setScore(item.getScore());
            log.setSource(hitSource(request));
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

    private static String hitSource(RetrievalTestRequest request) {
        return StringUtils.hasText(request.getHitSource()) ? request.getHitSource() : "CONSOLE";
    }

    private void recordMisses(RetrievalTestRequest request, List<KnowledgeBase> knowledgeBases, String searchMode) {
        for (KnowledgeBase kb : knowledgeBases) {
            KnowledgeHitLog log = new KnowledgeHitLog();
            log.setKnowledgeBaseId(kb.getId());
            log.setQueryText(request.getQuery());
            log.setSearchMode(searchMode);
            log.setScore(0f);
            log.setSource(hitSource(request));
            log.setTraceId(request.getTraceId());
            log.setUserId(request.getUserId());
            knowledgeHitLogRepository.insert(log);
        }
    }

    /**
     * Whitespace/punctuation split plus character bigrams for CJK runs: Chinese text has no spaces, so
     * a whole sentence used to become one LIKE term and never matched. Bigrams keep matching cheap
     * (plain LIKE) while letting "退货运费谁出" hit content containing "退货运费".
     */
    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String part : text.toLowerCase(Locale.ROOT).split("[\\s,.;:!?，。；：！？、()（）\\[\\]{}\"'“”‘’《》<>]+")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            StringBuilder latin = new StringBuilder();
            StringBuilder cjk = new StringBuilder();
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                    flushLatin(latin, terms);
                    cjk.append(c);
                } else {
                    flushCjk(cjk, terms);
                    latin.append(c);
                }
            }
            flushLatin(latin, terms);
            flushCjk(cjk, terms);
        }
        return terms.stream().distinct().limit(24).collect(Collectors.toList());
    }

    private static void flushLatin(StringBuilder buffer, List<String> terms) {
        if (!buffer.isEmpty()) {
            terms.add(buffer.toString());
            buffer.setLength(0);
        }
    }

    private static void flushCjk(StringBuilder buffer, List<String> terms) {
        if (buffer.isEmpty()) {
            return;
        }
        if (buffer.length() == 1) {
            terms.add(buffer.toString());
        } else {
            for (int i = 0; i + 1 < buffer.length(); i++) {
                terms.add(buffer.substring(i, i + 2));
            }
        }
        buffer.setLength(0);
    }

    private float valueOrDefault(Float value, float fallback) {
        return value != null ? value : fallback;
    }
}

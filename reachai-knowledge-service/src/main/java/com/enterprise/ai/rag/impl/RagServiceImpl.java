package com.enterprise.ai.rag.impl;

import com.enterprise.ai.domain.dto.RagRequest;
import com.enterprise.ai.domain.dto.RagResponse;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.domain.vo.SimilarItem;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.rag.LlmService;
import com.enterprise.ai.rag.PromptBuilder;
import com.enterprise.ai.rag.RagService;
import com.enterprise.ai.security.PermissionService;
import com.enterprise.ai.service.KnowledgeService;
import com.enterprise.ai.vector.VectorSearchRequest;
import com.enterprise.ai.vector.VectorSearchResult;
import com.enterprise.ai.vector.VectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagServiceImpl implements RagService {

    private final EmbeddingService embeddingService;
    private final VectorService vectorService;
    private final PermissionService permissionService;
    private final KnowledgeService knowledgeService;
    private final LlmService llmService;
    private final PromptBuilder promptBuilder;

    @Value("${rag.default-top-k:5}")
    private int defaultTopK;

    @Value("${rag.score-threshold:0.5}")
    private float defaultScoreThreshold;

    @Override
    public RagResponse query(RagRequest request) {
        RetrievalBundle bundle = retrieveInternal(request);
        RagResponse response = new RagResponse();
        response.setReferences(bundle.hits());
        if (bundle.hits().isEmpty()) {
            response.setAnswer(bundle.emptyReason() != null
                    ? bundle.emptyReason()
                    : "未找到与您问题相关的知识库内容。");
            return response;
        }
        String prompt = promptBuilder.build(request.getQuestion(), bundle.hits());
        String answer = llmService.chat(prompt, resolveAnswerModelInstanceId(bundle.hits(), bundle.kbByCode()));
        response.setAnswer(answer);
        return response;
    }

    @Override
    public RagResponse retrieve(RagRequest request) {
        RetrievalBundle bundle = retrieveInternal(request);
        RagResponse response = new RagResponse();
        response.setReferences(bundle.hits());
        response.setAnswer(null);
        return response;
    }

    private RetrievalBundle retrieveInternal(RagRequest request) {
        int topK = request.getTopK() != null ? request.getTopK() : defaultTopK;
        float threshold = request.getScoreThreshold() != null ? request.getScoreThreshold() : defaultScoreThreshold;

        List<String> fileIds = permissionService.getAccessibleFileIds(request.getUserId());
        if (fileIds == null || fileIds.isEmpty()) {
            log.warn("用户 {} 无任何文件权限", request.getUserId());
            return new RetrievalBundle(Collections.emptyList(), Map.of(), "您当前没有可访问的知识库内容。");
        }
        String filter = permissionService.buildMilvusFilter(fileIds);

        List<KnowledgeBase> knowledgeBases = knowledgeService.resolveKnowledgeBases(request.getKnowledgeBaseCodes());
        Map<String, KnowledgeBase> kbByCode = knowledgeBases.stream()
                .collect(Collectors.toMap(KnowledgeBase::getCode, kb -> kb, (a, b) -> a));

        List<SimilarItem> allResults = new ArrayList<>();
        for (KnowledgeBase kb : knowledgeBases) {
            List<Float> queryVector = embeddingService.embed(requireEmbeddingModelInstanceId(kb), request.getQuestion());
            List<VectorSearchResult> searchResults = vectorService.search(
                    VectorSearchRequest.builder()
                            .collectionName(kb.getCode())
                            .queryVector(queryVector)
                            .topK(topK)
                            .filterExpression(filter)
                            .outputFields(List.of("id", "file_id", "content"))
                            .build()
            );

            for (VectorSearchResult sr : searchResults) {
                if (sr.getScore() >= threshold) {
                    allResults.add(SimilarItem.builder()
                            .chunkId(sr.getId())
                            .fileId(String.valueOf(sr.getFields().get("file_id")))
                            .content(String.valueOf(sr.getFields().get("content")))
                            .score(sr.getScore())
                            .knowledgeBaseCode(kb.getCode())
                            .build());
                }
            }
        }

        allResults.sort(Comparator.comparingDouble(SimilarItem::getScore).reversed());
        List<SimilarItem> topResults = allResults.stream().limit(topK).collect(Collectors.toList());
        knowledgeService.enrichFileName(topResults);
        return new RetrievalBundle(topResults, kbByCode, null);
    }

    private record RetrievalBundle(
            List<SimilarItem> hits,
            Map<String, KnowledgeBase> kbByCode,
            String emptyReason
    ) {
    }

    private String requireEmbeddingModelInstanceId(KnowledgeBase kb) {
        if (kb == null || kb.getEmbeddingModelInstanceId() == null || kb.getEmbeddingModelInstanceId().isBlank()) {
            throw new IllegalArgumentException("embeddingModelInstanceId is required for knowledge base");
        }
        return kb.getEmbeddingModelInstanceId().trim();
    }

    private String resolveAnswerModelInstanceId(List<SimilarItem> results, Map<String, KnowledgeBase> kbByCode) {
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException("llmModelInstanceId is required for RAG generation");
        }
        String code = results.get(0).getKnowledgeBaseCode();
        KnowledgeBase kb = kbByCode.get(code);
        if (kb == null || kb.getLlmModelInstanceId() == null || kb.getLlmModelInstanceId().isBlank()) {
            throw new IllegalArgumentException("llmModelInstanceId is required for knowledge base: " + code);
        }
        return kb.getLlmModelInstanceId().trim();
    }
}

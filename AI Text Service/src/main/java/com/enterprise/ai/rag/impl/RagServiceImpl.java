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
        int topK = request.getTopK() != null ? request.getTopK() : defaultTopK;
        float threshold = request.getScoreThreshold() != null ? request.getScoreThreshold() : defaultScoreThreshold;

        // 1. 获取用户权限
        List<String> fileIds = permissionService.getAccessibleFileIds(request.getUserId());
        if (fileIds == null || fileIds.isEmpty()) {
            log.warn("用户 {} 无任何文件权限", request.getUserId());
            RagResponse resp = new RagResponse();
            resp.setAnswer("您当前没有可访问的知识库内容。");
            resp.setReferences(Collections.emptyList());
            return resp;
        }
        String filter = permissionService.buildMilvusFilter(fileIds);

        // 2. Embedding
        List<Float> queryVector = embeddingService.embed(request.getQuestion());

        // 3. 确定要检索的知识库
        List<KnowledgeBase> knowledgeBases = knowledgeService.resolveKnowledgeBases(request.getKnowledgeBaseCodes());

        // 4. 多库并行检索 + 权限过滤
        List<SimilarItem> allResults = new ArrayList<>();
        for (KnowledgeBase kb : knowledgeBases) {
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

        // 5. TopK 合并（按分数降序）
        allResults.sort(Comparator.comparingDouble(SimilarItem::getScore).reversed());
        List<SimilarItem> topResults = allResults.stream().limit(topK).collect(Collectors.toList());

        // 6. 补充文件名
        knowledgeService.enrichFileName(topResults);

        // 7. Prompt 构建 + LLM 调用
        RagResponse response = new RagResponse();
        if (topResults.isEmpty()) {
            response.setAnswer("未找到与您问题相关的知识库内容。");
        } else {
            String prompt = promptBuilder.build(request.getQuestion(), topResults);
            String answer = llmService.chat(prompt);
            response.setAnswer(answer);
        }
        response.setReferences(topResults);

        return response;
    }
}

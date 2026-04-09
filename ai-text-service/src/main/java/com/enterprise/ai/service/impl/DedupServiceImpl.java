package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.DedupRequest;
import com.enterprise.ai.domain.dto.DedupResponse;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.domain.vo.SimilarItem;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.security.PermissionService;
import com.enterprise.ai.service.DedupService;
import com.enterprise.ai.service.KnowledgeService;
import com.enterprise.ai.vector.VectorSearchRequest;
import com.enterprise.ai.vector.VectorSearchResult;
import com.enterprise.ai.vector.VectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DedupServiceImpl implements DedupService {

    private final EmbeddingService embeddingService;
    private final VectorService vectorService;
    private final PermissionService permissionService;
    private final KnowledgeService knowledgeService;

    @Override
    public DedupResponse check(DedupRequest request) {
        // 1. 获取用户权限
        List<String> fileIds = permissionService.getAccessibleFileIds(request.getUserId());
        String filter = permissionService.buildMilvusFilter(fileIds);

        // 2. Embedding
        List<Float> queryVector = embeddingService.embed(request.getText());

        // 3. 确定查重范围
        List<KnowledgeBase> knowledgeBases = knowledgeService.resolveKnowledgeBases(request.getKnowledgeBaseCodes());

        // 4. 多库检索
        List<SimilarItem> allResults = new ArrayList<>();
        for (KnowledgeBase kb : knowledgeBases) {
            List<VectorSearchResult> searchResults = vectorService.search(
                    VectorSearchRequest.builder()
                            .collectionName(kb.getCode())
                            .queryVector(queryVector)
                            .topK(request.getTopK() != null ? request.getTopK() : 10)
                            .filterExpression(filter)
                            .outputFields(List.of("id", "file_id", "content"))
                            .build()
            );

            for (VectorSearchResult sr : searchResults) {
                float threshold = request.getScoreThreshold() != null ? request.getScoreThreshold() : 0.7f;
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

        // 5. 排序 + 截断
        allResults.sort(Comparator.comparingDouble(SimilarItem::getScore).reversed());
        int topK = request.getTopK() != null ? request.getTopK() : 10;
        List<SimilarItem> topResults = allResults.stream().limit(topK).collect(Collectors.toList());

        // 6. 补充文件名
        knowledgeService.enrichFileName(topResults);

        return DedupResponse.of(topResults);
    }
}

package com.enterprise.ai.bizindex.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.bizindex.domain.dto.BizSearchRequest;
import com.enterprise.ai.bizindex.domain.dto.BizSearchResponse;
import com.enterprise.ai.bizindex.domain.dto.BizSearchResponse.BizSearchItem;
import com.enterprise.ai.bizindex.domain.entity.BusinessIndex;
import com.enterprise.ai.bizindex.domain.entity.BusinessIndexRecord;
import com.enterprise.ai.bizindex.repository.BusinessIndexRecordRepository;
import com.enterprise.ai.bizindex.repository.BusinessIndexRepository;
import com.enterprise.ai.bizindex.service.BizIndexSearchService;
import com.enterprise.ai.bizindex.vector.BizVectorService;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.vector.VectorSearchRequest;
import com.enterprise.ai.vector.VectorSearchResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BizIndexSearchServiceImpl implements BizIndexSearchService {

    private final BusinessIndexRepository indexRepository;
    private final BusinessIndexRecordRepository recordRepository;
    private final BizVectorService bizVectorService;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    private static final int DEFAULT_TOP_K = 10;
    private static final float DEFAULT_SCORE_THRESHOLD = 0.5f;

    @Override
    public BizSearchResponse search(String indexCode, BizSearchRequest request) {
        long startTime = System.currentTimeMillis();

        // 校验索引
        BusinessIndex index = indexRepository.selectOne(
                new LambdaQueryWrapper<BusinessIndex>().eq(BusinessIndex::getIndexCode, indexCode));
        if (index == null) {
            throw new IllegalArgumentException("索引不存在: " + indexCode);
        }

        int topK = request.getTopK() != null ? request.getTopK() : DEFAULT_TOP_K;
        float scoreThreshold = request.getScoreThreshold() != null ? request.getScoreThreshold() : DEFAULT_SCORE_THRESHOLD;
        boolean includeAttachment = request.getIncludeAttachmentMatch() == null || request.getIncludeAttachmentMatch();

        // 1. 查询文本向量化
        List<Float> queryVector = embeddingService.embed(request.getQuery());

        // 2. 构建过滤表达式
        String filterExpr = buildFilterExpression(request.getFilters(), includeAttachment);

        // 3. Milvus 搜索（多取一些结果，因为去重后数量会减少）
        int searchTopK = topK * 3;
        VectorSearchRequest searchRequest = VectorSearchRequest.builder()
                .collectionName(indexCode)
                .queryVector(queryVector)
                .topK(searchTopK)
                .filterExpression(filterExpr)
                .build();

        List<VectorSearchResult> rawResults = bizVectorService.search(searchRequest);

        // 4. 按 bizId 去重，保留最高 score
        Map<String, BizSearchItem> deduped = new LinkedHashMap<>();
        for (VectorSearchResult result : rawResults) {
            if (result.getScore() < scoreThreshold) continue;

            String bizId = String.valueOf(result.getFields().get("biz_id"));
            String recordType = String.valueOf(result.getFields().get("record_type"));
            String content = String.valueOf(result.getFields().get("content"));
            String fileName = String.valueOf(result.getFields().get("file_name"));

            BizSearchItem existing = deduped.get(bizId);
            if (existing == null || result.getScore() > existing.getScore()) {
                BizSearchItem item = BizSearchItem.builder()
                        .bizId(bizId)
                        .bizType(toStringOrNull(result.getFields().get("biz_type")))
                        .score(result.getScore())
                        .matchSource(recordType)
                        .matchFileName("ATTACHMENT".equals(recordType) ? fileName : null)
                        .matchContent(content)
                        .build();
                deduped.put(bizId, item);
            }
        }

        // 5. 补充 metadata（从 MySQL 读取）
        List<BizSearchItem> items = new ArrayList<>(deduped.values());
        if (items.size() > topK) {
            items = items.subList(0, topK);
        }
        enrichMetadata(indexCode, items);

        long costMs = System.currentTimeMillis() - startTime;

        return BizSearchResponse.builder()
                .results(items)
                .total(items.size())
                .costMs(costMs)
                .build();
    }

    /**
     * 根据业务系统传来的 filters 构建 Milvus boolean 表达式。
     * <p>示例输入：{"owner_org_id": ["org_001"], "biz_type": ["purchase"]}</p>
     * <p>示例输出：owner_org_id in ["org_001"] && biz_type in ["purchase"]</p>
     */
    private String buildFilterExpression(Map<String, List<String>> filters, boolean includeAttachment) {
        List<String> conditions = new ArrayList<>();

        if (!includeAttachment) {
            conditions.add("record_type == \"FIELD\"");
        }

        if (filters != null) {
            for (Map.Entry<String, List<String>> entry : filters.entrySet()) {
                String field = entry.getKey();
                List<String> values = entry.getValue();
                // 安全校验：只允许已知的标量字段
                if (!isAllowedFilterField(field) || values == null || values.isEmpty()) continue;

                if (values.size() == 1) {
                    conditions.add(field + " == \"" + escapeValue(values.get(0)) + "\"");
                } else {
                    StringJoiner sj = new StringJoiner("\", \"", "[\"", "\"]");
                    values.forEach(v -> sj.add(escapeValue(v)));
                    conditions.add(field + " in " + sj);
                }
            }
        }

        return conditions.isEmpty() ? null : String.join(" && ", conditions);
    }

    /** 只允许通过已知字段进行过滤，防止注入 */
    private boolean isAllowedFilterField(String field) {
        return Set.of("owner_user_id", "owner_org_id", "biz_type", "record_type", "file_name").contains(field);
    }

    private String escapeValue(String value) {
        return value.replace("\"", "\\\"");
    }

    /** 从 MySQL 补充 metadata 到搜索结果 */
    private void enrichMetadata(String indexCode, List<BizSearchItem> items) {
        if (items.isEmpty()) return;

        List<String> bizIds = items.stream().map(BizSearchItem::getBizId).toList();
        List<BusinessIndexRecord> records = recordRepository.selectList(
                new LambdaQueryWrapper<BusinessIndexRecord>()
                        .eq(BusinessIndexRecord::getIndexCode, indexCode)
                        .in(BusinessIndexRecord::getBizId, bizIds)
                        .eq(BusinessIndexRecord::getStatus, "ACTIVE"));

        Map<String, BusinessIndexRecord> recordMap = new HashMap<>();
        for (BusinessIndexRecord r : records) {
            recordMap.put(r.getBizId(), r);
        }

        for (BizSearchItem item : items) {
            BusinessIndexRecord record = recordMap.get(item.getBizId());
            if (record != null && record.getMetadataJson() != null) {
                item.setMetadata(parseMetadata(record.getMetadataJson()));
                if (item.getBizType() == null || item.getBizType().isBlank()) {
                    item.setBizType(record.getBizType());
                }
            }
        }
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("解析 metadata 失败: {}", e.getMessage());
            return null;
        }
    }

    private String toStringOrNull(Object obj) {
        if (obj == null) return null;
        String s = String.valueOf(obj);
        return s.isBlank() || "null".equals(s) ? null : s;
    }
}

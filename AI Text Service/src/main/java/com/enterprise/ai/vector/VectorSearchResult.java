package com.enterprise.ai.vector;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class VectorSearchResult {

    /** Milvus 返回的主键 ID */
    private String id;

    /** 相似度分数 */
    private float score;

    /** 返回的字段值 */
    private Map<String, Object> fields;
}

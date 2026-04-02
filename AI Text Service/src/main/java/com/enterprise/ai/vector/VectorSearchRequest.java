package com.enterprise.ai.vector;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class VectorSearchRequest {

    /** 目标 collection 名称 */
    private String collectionName;

    /** 查询向量 */
    private List<Float> queryVector;

    /** TopK */
    private int topK;

    /** Milvus boolean filter 表达式 */
    private String filterExpression;

    /** 需要返回的字段列表 */
    private List<String> outputFields;
}

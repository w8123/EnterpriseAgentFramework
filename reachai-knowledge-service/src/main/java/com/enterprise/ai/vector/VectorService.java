package com.enterprise.ai.vector;

import java.util.List;

/**
 * 向量数据库服务接口 — 封装向量的增删查操作。
 * <p>扩展点：可替换为其他向量数据库实现（如 Qdrant、Pinecone 等）。</p>
 */
public interface VectorService {

    /**
     * 创建 collection（如不存在）
     */
    void ensureCollection(String collectionName, int dimension);

    /**
     * 批量幂等写入向量。相同主键必须覆盖，确保文档索引失败后的任务重试
     * 不会留下重复主键或重复向量。
     *
     * @param collectionName collection 名称
     * @param ids            向量 ID 列表
     * @param vectors        向量数据
     * @param fileIds        每条向量对应的 file_id（用于权限过滤）
     * @param contents       每条向量对应的文本内容
     */
    void upsert(String collectionName, List<String> ids, List<List<Float>> vectors,
                List<String> fileIds, List<String> contents);

    /**
     * 向量检索
     */
    List<VectorSearchResult> search(VectorSearchRequest request);

    /**
     * 按准确主键删除一条向量
     */
    void deleteById(String collectionName, String id);

    void dropCollection(String collectionName);
}

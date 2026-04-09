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
     * 批量插入向量
     *
     * @param collectionName collection 名称
     * @param ids            向量 ID 列表
     * @param vectors        向量数据
     * @param fileIds        每条向量对应的 file_id（用于权限过滤）
     * @param contents       每条向量对应的文本内容
     */
    void insert(String collectionName, List<String> ids, List<List<Float>> vectors,
                List<String> fileIds, List<String> contents);

    /**
     * 向量检索
     */
    List<VectorSearchResult> search(VectorSearchRequest request);

    /**
     * 按 file_id 删除向量
     */
    void deleteByFileId(String collectionName, String fileId);

    /**
     * 删除整个 collection
     */
    void dropCollection(String collectionName);
}

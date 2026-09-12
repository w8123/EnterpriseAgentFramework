package com.enterprise.ai.security;

import java.util.List;

/**
 * 权限服务接口 — 负责文件级权限查询与过滤表达式构建。
 * <p>扩展点：可替换为基于 RBAC / ABAC 的实现。</p>
 */
public interface PermissionService {

    /** Capture current grant and physical file identities before retrieval begins. */
    FileAccessSnapshot capture(String userId);

    /** Re-read current grants and metadata; only the original identities remain authorized. */
    List<AuthorizedKnowledgeChunk> resolveAuthorizedChunks(FileAccessSnapshot snapshot, List<Long> chunkIds);

    /**
     * 获取用户有权限访问的 file_id 列表，仅供枚举；进行中的检索必须使用 capture 和 resolveAuthorizedChunks。
     */
    List<String> getAccessibleFileIds(String userId);

    /**
     * 构建 Milvus filter 表达式（基于 file_id 列表）
     *
     * @param fileIds 有权限的 file_id 列表
     * @return Milvus boolean expression，例如 file_id in ["f1","f2"]
     */
    String buildMilvusFilter(List<String> fileIds);
}

package com.enterprise.ai.service;

import com.enterprise.ai.domain.dto.DedupRequest;
import com.enterprise.ai.domain.dto.DedupResponse;

/**
 * 查重服务接口
 */
public interface DedupService {

    /**
     * 执行查重检测：embedding → 多库检索 → 权限过滤 → 相似度排序
     */
    DedupResponse check(DedupRequest request);
}

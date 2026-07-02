package com.enterprise.ai.bizindex.service;

import com.enterprise.ai.bizindex.domain.dto.BizSearchRequest;
import com.enterprise.ai.bizindex.domain.dto.BizSearchResponse;

/**
 * 业务索引语义搜索服务
 */
public interface BizIndexSearchService {

    /**
     * 在指定索引中进行语义搜索
     *
     * @param indexCode 索引编码
     * @param request   搜索请求
     * @return 搜索结果（已按 bizId 去重，取最高 score）
     */
    BizSearchResponse search(String indexCode, BizSearchRequest request);
}

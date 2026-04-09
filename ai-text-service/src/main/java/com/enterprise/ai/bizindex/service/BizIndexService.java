package com.enterprise.ai.bizindex.service;

import com.enterprise.ai.bizindex.domain.dto.BizIndexRequest;
import com.enterprise.ai.bizindex.domain.dto.BizIndexStatsVO;
import com.enterprise.ai.bizindex.domain.dto.BizIndexVO;

import java.util.List;

/**
 * 业务索引管理服务 —— 索引注册、更新、删除、查询。
 */
public interface BizIndexService {

    /** 注册新索引（同时创建 Milvus Collection） */
    void create(BizIndexRequest request);

    /** 更新索引配置（模板、字段定义等） */
    void update(String indexCode, BizIndexRequest request);

    /** 删除索引（同时销毁 Milvus Collection 和所有关联数据） */
    void delete(String indexCode);

    /** 查询索引列表 */
    List<BizIndexVO> list();

    /** 查询索引详情 */
    BizIndexVO detail(String indexCode);

    /** 查询索引统计信息 */
    BizIndexStatsVO stats(String indexCode);
}

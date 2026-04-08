package com.enterprise.ai.bizindex.domain.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 业务语义搜索响应
 */
@Data
@Builder
public class BizSearchResponse {

    /** 搜索结果列表（已按 bizId 去重，取最高分） */
    private List<BizSearchItem> results;

    /** 去重后的结果总数 */
    private int total;

    /** 检索耗时（毫秒） */
    private long costMs;

    /**
     * 单条搜索结果
     */
    @Data
    @Builder
    public static class BizSearchItem {

        /** 业务主键 */
        private String bizId;

        /** 业务子类型 */
        private String bizType;

        /** 最高相似度分数 */
        private float score;

        /** 匹配来源: FIELD（业务字段）/ ATTACHMENT（附件） */
        private String matchSource;

        /** 匹配的附件文件名（matchSource=ATTACHMENT 时有值） */
        private String matchFileName;

        /** 命中的文本片段 */
        private String matchContent;

        /** 元数据（注册时 metadata 字段，原样返回给业务系统） */
        private Map<String, Object> metadata;
    }
}

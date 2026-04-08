package com.enterprise.ai.bizindex.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 业务语义搜索请求
 */
@Data
public class BizSearchRequest {

    /** 搜索文本 */
    @NotBlank(message = "搜索内容不能为空")
    private String query;

    /** 返回条数，默认 10 */
    private Integer topK;

    /** 相似度阈值，低于此分数的结果将被过滤，默认 0.5 */
    private Float scoreThreshold;

    /**
     * 过滤条件 —— key 为 Milvus 标量字段名，value 为允许的值列表。
     * <p>示例：{"owner_org_id": ["org_001","org_002"], "biz_type": ["purchase"]}</p>
     */
    private Map<String, List<String>> filters;

    /** 是否包含附件匹配结果，默认 true */
    private Boolean includeAttachmentMatch;
}

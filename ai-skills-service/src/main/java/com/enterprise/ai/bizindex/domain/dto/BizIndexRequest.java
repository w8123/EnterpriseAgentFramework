package com.enterprise.ai.bizindex.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 业务索引注册 / 编辑请求
 */
@Data
public class BizIndexRequest {

    @NotBlank(message = "索引编码不能为空")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]{1,62}$",
            message = "索引编码只能包含字母、数字和下划线，以字母开头，2-63 个字符")
    private String indexCode;

    @NotBlank(message = "索引名称不能为空")
    private String indexName;

    @NotBlank(message = "来源系统标识不能为空")
    private String sourceSystem;

    @NotBlank(message = "文本模板不能为空")
    private String textTemplate;

    /** 字段定义 JSON 字符串 */
    @NotBlank(message = "字段定义不能为空")
    private String fieldSchema;

    /** Embedding 模型标识（留空则使用系统默认） */
    private String embeddingModel;

    /** 向量维度（留空则使用系统默认） */
    private Integer dimension;

    /** 附件切分大小 */
    private Integer chunkSize;

    /** 附件切分重叠 */
    private Integer chunkOverlap;

    /** 附件切分策略 */
    private String splitType;

    /** 备注 */
    private String remark;
}

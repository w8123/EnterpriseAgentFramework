package com.enterprise.ai.bizindex.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Map;

/**
 * 业务数据推送请求（JSON 部分）。
 * <p>配合 Multipart 上传，附件通过 file part 传递，此对象通过 "data" part 传递。</p>
 */
@Data
public class BizUpsertRequest {

    @NotBlank(message = "业务主键不能为空")
    private String bizId;

    /** 业务子类型（可选） */
    private String bizType;

    /** 业务字段，key 为模板中的占位符名称 */
    @NotEmpty(message = "业务字段不能为空")
    private Map<String, String> fields;

    /** 元数据，搜索结果中原样返回（不参与语义检索） */
    private Map<String, Object> metadata;

    /** 数据所有者用户 ID */
    private String ownerUserId;

    /** 数据所属组织 ID */
    private String ownerOrgId;
}

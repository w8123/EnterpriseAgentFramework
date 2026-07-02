package com.enterprise.ai.bizindex.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务索引记录 —— 业务系统推送的每条业务数据对应一条记录。
 * <p>通过 index_code + biz_id 唯一标识一条业务数据，支持 upsert 语义。</p>
 */
@Data
@TableName("business_index_record")
public class BusinessIndexRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属索引编码 */
    private String indexCode;

    /** 业务主键（由业务系统定义） */
    private String bizId;

    /** 业务子类型（可选） */
    private String bizType;

    /** 模板渲染后的索引文本 */
    private String searchText;

    /** 业务系统推送的原始字段 JSON */
    private String fieldsJson;

    /** 元数据 JSON（搜索结果回显，不参与语义检索） */
    private String metadataJson;

    /** 数据所有者用户 ID */
    private String ownerUserId;

    /** 数据所属组织 ID */
    private String ownerOrgId;

    /** 主记录在 Milvus 中的向量 ID */
    private String vectorId;

    /** 是否包含附件 */
    private Boolean hasAttachment;

    /** 状态: ACTIVE / DELETED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

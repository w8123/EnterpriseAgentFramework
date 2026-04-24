package com.enterprise.ai.bizindex.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务语义索引注册表 —— 每个接入的业务系统注册一个索引。
 * <p>index_code 与 Milvus Collection 一一对应，实现业务系统之间的数据物理隔离。</p>
 */
@Data
@TableName("business_index")
public class BusinessIndex {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 索引编码，唯一标识，同时作为 Milvus Collection 名称 */
    private String indexCode;

    /** 索引显示名称 */
    private String indexName;

    /** 来源系统标识，如 material_system、contract_system */
    private String sourceSystem;

    /** 文本拼接模板，如：物资名称：{name}，规格：{spec} */
    private String textTemplate;

    /** 字段定义 JSON */
    private String fieldSchema;

    /** 使用的 Embedding 模型标识 */
    private String embeddingModel;

    /** 向量维度 */
    private Integer dimension;

    /** 附件切分大小（字符数） */
    private Integer chunkSize;

    /** 附件切分重叠（字符数） */
    private Integer chunkOverlap;

    /** 附件切分策略: FIXED / PARAGRAPH / SEMANTIC */
    private String splitType;

    /** 状态: ACTIVE / INACTIVE */
    private String status;

    /** 备注 */
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

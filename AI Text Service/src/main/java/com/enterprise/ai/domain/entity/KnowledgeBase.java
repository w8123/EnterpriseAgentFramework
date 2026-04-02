package com.enterprise.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_base")
public class KnowledgeBase {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 知识库名称 */
    private String name;

    /** 知识库编码（对应 Milvus collection 名称） */
    private String code;

    /** 描述 */
    private String description;

    /** Embedding 模型标识 */
    private String embeddingModel;

    /** 向量维度 */
    private Integer dimension;

    /** chunk 切分大小（字符数） */
    private Integer chunkSize;

    /** chunk 重叠大小（字符数） */
    private Integer chunkOverlap;

    /** 切分策略: FIXED / PARAGRAPH / SEMANTIC */
    private String splitType;

    /** 状态: 0-禁用 1-启用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

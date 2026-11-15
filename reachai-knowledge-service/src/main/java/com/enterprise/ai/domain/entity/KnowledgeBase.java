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

    /** 知识库业务编码，可在删除后重新使用。 */
    private String code;

    /** 本知识库独占的物理向量集合，创建后不再修改或复用。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String vectorCollectionName;

    /** 描述 */
    private String description;

    /** Embedding 模型标识 */
    private String embeddingModelInstanceId;

    private String rerankModelInstanceId;

    private String llmModelInstanceId;

    private String workspaceId;

    private String projectCode;

    private String scope;

    /** 谁能检索：ALL_CALLERS（调用方都能查到）/ FILE_GRANTS（仅逐文件授权的用户）。 */
    private String accessMode;

    /** 向量维度 */
    private Integer dimension;

    /** chunk 切分大小（字符数） */
    private Integer chunkSize;

    /** chunk 重叠大小（字符数） */
    private Integer chunkOverlap;

    /** 切分策略: FIXED / PARAGRAPH / SEMANTIC */
    private String splitType;

    private String searchMode;

    private Integer topK;

    private Float similarityThreshold;

    private Boolean rerankEnabled;

    private Float vectorWeight;

    private Float keywordWeight;

    /** 来源：LOCAL（本平台向量库）/ EXTERNAL（外部知识库，检索时调用外部系统）。 */
    private String sourceType;

    /** 外部知识库提供方：RAGFLOW / IMA / DIFY；本地知识库为空。 */
    private String externalProvider;

    /** 外部连接配置 JSON（地址、Client ID、选中的远端库），不含密钥。 */
    private String externalConfig;

    /** 外部系统 API Key，AES-GCM 加密后保存，永不返回给控制台。 */
    private String externalSecret;

    /** 状态: 0-禁用 1-启用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

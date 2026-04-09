package com.enterprise.ai.bizindex.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务索引列表展示 VO
 */
@Data
public class BizIndexVO {

    private Long id;
    private String indexCode;
    private String indexName;
    private String sourceSystem;
    private String textTemplate;
    private String fieldSchema;
    private String embeddingModel;
    private Integer dimension;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private String splitType;
    private String status;
    private String remark;

    /** 已索引的记录数（由查询动态填充） */
    private Long recordCount;

    /** 附件 Chunk 数（由查询动态填充） */
    private Long attachmentChunkCount;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

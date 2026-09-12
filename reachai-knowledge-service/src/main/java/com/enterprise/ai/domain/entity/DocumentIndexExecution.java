package com.enterprise.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** Durable identity of one external vector write, retained across worker restarts. */
@Data
@TableName("knowledge_document_index_execution")
public class DocumentIndexExecution {
    @TableId(type = IdType.INPUT)
    private String leaseOwner;
    private String jobId;
    private String operationType;
    private LocalDateTime publicationDeadline;
    private String fileId;
    private Long knowledgeBaseId;
    private String collectionName;
    private String vectorPrefix;
    private String singleVectorId;
    private Integer vectorCount;
    private Long targetFileRowId;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String targetFileGeneration;
    private Long targetChunkId;
    private String targetVectorId;
    private String targetCollectionName;
    private String targetContentHash;
    private String state;
    private Boolean writeAcknowledged;
    private Integer cleanupCursor;
    private LocalDateTime nextCleanupAt;
    private String cleanupLeaseOwner;
    private LocalDateTime cleanupLeaseUntil;
    private String lastCleanupError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

package com.enterprise.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_collection_lifecycle")
public class KnowledgeCollectionLifecycle {
    @TableId(type = IdType.INPUT)
    private String collectionName;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long knowledgeBaseId;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String knowledgeBaseCode;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer dimension;
    private String state;
    private Boolean createAcknowledged;
    private LocalDateTime createDeadline;
    private LocalDateTime nextCleanupAt;
    private String cleanupLeaseOwner;
    private LocalDateTime cleanupLeaseUntil;
    private String lastCleanupError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

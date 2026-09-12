package com.enterprise.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_document_artifact_lifecycle")
public class DocumentArtifactLifecycle {
    @TableId(type = IdType.INPUT)
    private String artifactId;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String storageId;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String objectKey;
    private String state;
    private Boolean writeAcknowledged;
    private LocalDateTime publicationDeadline;
    private LocalDateTime nextCleanupAt;
    private String cleanupLeaseOwner;
    private LocalDateTime cleanupLeaseUntil;
    private String lastCleanupError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

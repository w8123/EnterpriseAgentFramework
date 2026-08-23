package com.enterprise.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable state for one uploaded knowledge document. */
@Data
@TableName("knowledge_document_import_job")
public class DocumentImportJob {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String jobId;
    private String fileId;
    /** Existing file superseded after this replacement job completes. */
    private String replaceFileId;
    private Long knowledgeBaseId;
    private String knowledgeBaseCode;
    /** Control-configured tenant snapshot for the authenticated console actor. */
    private String tenantId;
    /** User that created the job; status and mutations are owner-fenced. */
    private String createdByActorId;
    /** Knowledge resource authorization snapshot captured at submission time. */
    private String workspaceId;
    private String projectCode;
    private String resourceScope;
    private String fileName;
    private String fileType;
    private String contentType;
    private Long fileSize;
    private String sourceObjectKey;
    private String sourceSha256;
    private String providerType;
    private String providerVersion;
    private String parseArtifactObjectKey;
    private String status;
    private String stage;
    private String chunkStrategy;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private String extraParamsJson;
    private Integer autoCommit;
    private Integer attemptCount;
    private Integer maxAttempts;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime nextAttemptAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private LocalDateTime parsedAt;
    private LocalDateTime completedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

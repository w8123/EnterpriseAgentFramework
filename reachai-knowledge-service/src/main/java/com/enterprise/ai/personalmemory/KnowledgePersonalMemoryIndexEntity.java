package com.enterprise.ai.personalmemory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_personal_memory_index")
public class KnowledgePersonalMemoryIndexEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long memoryId;
    private String tenantId;
    private String runtimeUserHash;
    private String itemType;
    private String title;
    private String content;
    private String summary;
    private String trustLevel;
    private Long sourceVersion;
    private String status;
    private LocalDateTime expiresAt;
    private byte[] embeddingVector;
    private String embeddingFormat;
    private Integer embeddingDimension;
    private String embeddingModelInstanceId;
    private Long embeddingSourceVersion;
    private String embeddingTextSha256;
    private String embeddingStatus;
    private Integer embeddingAttempts;
    private String embeddingErrorCode;
    private LocalDateTime embeddingNextAttemptAt;
    private String embeddingClaimToken;
    private LocalDateTime embeddingClaimUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}

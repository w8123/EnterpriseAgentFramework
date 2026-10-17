package com.enterprise.ai.control.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A memory the extractor proposed; it only takes effect after the owner confirms it. */
@Data
@TableName("control_personal_memory_candidate")
public class PersonalMemoryCandidateEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String candidateKey;
    private String tenantId;
    private String ownerId;
    private String memoryType;
    private String title;
    private String content;
    private String contentSha256;
    /** Nullable while not pending; unique only for the active de-duplication window. */
    private String dedupeKey;
    private String semanticKey;
    private String reason;
    private BigDecimal confidence;
    private String status;
    private String sourceAgentId;
    private String sourceSessionId;
    private String sourceTraceId;
    private Long conflictMemoryId;
    private Long approvedMemoryId;
    private Integer occurrenceCount;
    private LocalDateTime lastSeenAt;
    private LocalDateTime reviewedAt;
    private String extractionVersion;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

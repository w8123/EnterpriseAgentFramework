package com.enterprise.ai.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Browser-facing status for the durable document import lifecycle. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentImportJobResponse {

    private String jobId;
    private String fileId;
    private String replaceFileId;
    private String knowledgeBaseCode;
    private String fileName;
    private String fileType;
    private String providerType;
    private String providerVersion;
    private String status;
    private String stage;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Boolean autoCommit;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime parsedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private ChunkPreviewResponse preview;
}

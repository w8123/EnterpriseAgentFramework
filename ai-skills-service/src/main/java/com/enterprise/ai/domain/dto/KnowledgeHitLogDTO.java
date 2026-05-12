package com.enterprise.ai.domain.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class KnowledgeHitLogDTO {
    private Long id;
    private Long chunkId;
    private String queryText;
    private String searchMode;
    private Float score;
    private Boolean directReturn;
    private String fileId;
    private String fileName;
    private Integer chunkIndex;
    private String userId;
    private String traceId;
    private LocalDateTime createTime;
}

package com.enterprise.ai.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeQuestionDTO {
    private Long id;
    private Long chunkId;
    private String question;
    private Integer hitCount;
    private String source;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

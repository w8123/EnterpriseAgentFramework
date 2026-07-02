package com.enterprise.ai.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeTagDTO {
    private Long id;
    private String targetType;
    private String targetId;
    private String tagKey;
    private String tagValue;
    private String tagGroup;
    private String color;
    private String description;
    private Long parentId;
    private Integer sortOrder;
    private LocalDateTime createTime;
}

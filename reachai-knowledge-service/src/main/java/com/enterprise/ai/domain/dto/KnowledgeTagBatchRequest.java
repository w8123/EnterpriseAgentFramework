package com.enterprise.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class KnowledgeTagBatchRequest {
    @NotBlank
    private String targetType;

    @NotEmpty
    private List<String> targetIds;

    @NotBlank
    private String tagKey;

    @NotBlank
    private String tagValue;

    private String tagGroup;

    private String color;

    private String description;

    private Long parentId;

    private Integer sortOrder;
}

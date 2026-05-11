package com.enterprise.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class KnowledgeTagRequest {
    private String targetType = "KNOWLEDGE";
    private String targetId;

    @NotBlank
    private String tagKey;

    @NotBlank
    private String tagValue;
}

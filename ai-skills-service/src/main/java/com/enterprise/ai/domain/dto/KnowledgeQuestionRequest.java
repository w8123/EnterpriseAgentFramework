package com.enterprise.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class KnowledgeQuestionRequest {
    private Long chunkId;

    @NotBlank
    private String question;

    private String source = "MANUAL";
}

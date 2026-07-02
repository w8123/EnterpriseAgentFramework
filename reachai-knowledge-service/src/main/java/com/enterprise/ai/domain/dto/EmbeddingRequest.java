package com.enterprise.ai.domain.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class EmbeddingRequest {

    @NotBlank(message = "modelInstanceId cannot be empty")
    private String modelInstanceId;

    @NotEmpty(message = "texts cannot be empty")
    private List<String> texts;
}

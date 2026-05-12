package com.enterprise.ai.domain.dto;

import lombok.Data;

@Data
public class ChunkUpdateRequest {
    private String title;
    private String content;
    private Integer enabled;
}

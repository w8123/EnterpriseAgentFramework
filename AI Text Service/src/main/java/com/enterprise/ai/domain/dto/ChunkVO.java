package com.enterprise.ai.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Chunk 视图对象 — 文件详情页 chunk 列表展示
 */
@Data
public class ChunkVO {
    private Long id;
    private String fileId;
    private String content;
    private Integer chunkIndex;
    private Integer length;
    private String vectorId;
    private LocalDateTime createTime;
}

package com.enterprise.ai.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Chunk 预览响应 — 返回文件切分后的文本块列表
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkPreviewResponse {

    private String fileName;
    private String chunkStrategy;
    private int chunkSize;
    private int chunkOverlap;
    private int totalChunks;
    private List<ChunkItem> chunks;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkItem {
        private int index;
        private String content;
        private int length;
    }
}

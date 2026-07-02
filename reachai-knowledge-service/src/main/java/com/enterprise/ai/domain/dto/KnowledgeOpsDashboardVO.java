package com.enterprise.ai.domain.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class KnowledgeOpsDashboardVO {
    private KnowledgeStatsVO stats;
    private List<PipelineFileStatus> recentFiles;
    private List<ChunkVO> hotChunks;
    private List<ChunkVO> zeroHitChunks;
    private List<KnowledgeHitLogDTO> recentHits;
    private List<KnowledgeHitLogDTO> lowConfidenceHits;

    @Data
    @Builder
    public static class PipelineFileStatus {
        private String fileId;
        private String fileName;
        private Integer status;
        private Integer chunkCount;
        private List<PipelineStepStatus> steps;
    }

    @Data
    @Builder
    public static class PipelineStepStatus {
        private String name;
        private String label;
        private String status;
        private Long durationMs;
    }
}

package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.tool.retrieval.RetrievalScope;
import com.enterprise.ai.agent.tool.retrieval.ToolCandidate;
import com.enterprise.ai.agent.tool.retrieval.ToolEmbeddingRebuildManager;
import com.enterprise.ai.agent.tool.retrieval.ToolEmbeddingRebuildTask;
import com.enterprise.ai.agent.tool.retrieval.ToolRetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Tool Retrieval 管理端：
 * <ul>
 *   <li>{@code POST /api/tool-retrieval/search} 检索测试</li>
 *   <li>{@code POST /api/tool-retrieval/rebuild} 异步全量重建</li>
 *   <li>{@code GET  /api/tool-retrieval/rebuild/status} 任务进度</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/tool-retrieval")
@RequiredArgsConstructor
public class ToolRetrievalController {

    private final ToolRetrievalService toolRetrievalService;
    private final ToolEmbeddingRebuildManager rebuildManager;

    @PostMapping("/search")
    public ResponseEntity<SearchResponse> search(@RequestBody SearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest().body(new SearchResponse(List.of(), "query 不能为空"));
        }
        int topK = request.topK() == null ? 0 : request.topK();
        RetrievalScope scope = new RetrievalScope(
                request.projectIds(),
                request.moduleIds(),
                request.toolWhitelist(),
                request.enabledOnly() == null || request.enabledOnly(),
                request.agentVisibleOnly() == null || request.agentVisibleOnly()
        );
        List<ToolCandidate> candidates = toolRetrievalService.retrieve(request.query(), scope, topK);
        return ResponseEntity.ok(new SearchResponse(candidates, null));
    }

    @PostMapping("/rebuild")
    public ResponseEntity<?> rebuild() {
        try {
            String taskId = rebuildManager.start();
            return ResponseEntity.accepted().body(new StartResponse(taskId));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(409).body(new ApiError(ex.getMessage()));
        }
    }

    @GetMapping("/rebuild/status")
    public ResponseEntity<TaskDTO> status(@RequestParam(value = "taskId", required = false) String taskId) {
        Optional<ToolEmbeddingRebuildTask> task = taskId != null
                ? rebuildManager.getTask(taskId)
                : rebuildManager.latest();
        return task.map(t -> ResponseEntity.ok(TaskDTO.from(t)))
                .orElse(ResponseEntity.ok().body(null));
    }

    // ==================== DTO ====================

    public record SearchRequest(
            String query,
            Integer topK,
            List<Long> projectIds,
            List<Long> moduleIds,
            List<Long> toolWhitelist,
            Boolean enabledOnly,
            Boolean agentVisibleOnly
    ) {
    }

    public record SearchResponse(List<ToolCandidate> candidates, String message) {
    }

    public record StartResponse(String taskId) {
    }

    public record TaskDTO(
            String taskId,
            String stage,
            int totalSteps,
            int completedSteps,
            int successCount,
            int skippedCount,
            int failedCount,
            String currentStep,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt
    ) {
        public static TaskDTO from(ToolEmbeddingRebuildTask t) {
            return new TaskDTO(
                    t.getTaskId(),
                    t.getStage() == null ? null : t.getStage().name(),
                    t.getTotalSteps(),
                    t.getCompletedSteps(),
                    t.getSuccessCount(),
                    t.getSkippedCount(),
                    t.getFailedCount(),
                    t.getCurrentStep(),
                    t.getErrorMessage(),
                    t.getStartedAt(),
                    t.getFinishedAt()
            );
        }
    }

    public record ApiError(String message) {
    }
}

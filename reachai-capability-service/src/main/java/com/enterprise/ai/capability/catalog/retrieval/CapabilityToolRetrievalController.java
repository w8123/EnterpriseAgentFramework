package com.enterprise.ai.capability.catalog.retrieval;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/tool-retrieval")
@RequiredArgsConstructor
public class CapabilityToolRetrievalController {

    private final CapabilityToolRetrievalService toolRetrievalService;
    private final CapabilityToolRetrievalRebuildManager rebuildManager;

    @PostMapping("/search")
    public ResponseEntity<SearchResponse> search(@RequestBody SearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest().body(new SearchResponse(List.of(), "query 不能为空"));
        }
        int topK = request.topK() == null ? 0 : request.topK();
        CapabilityRetrievalScope scope = new CapabilityRetrievalScope(
                request.projectIds(),
                request.moduleIds(),
                request.toolWhitelist(),
                request.enabledOnly() == null || request.enabledOnly(),
                request.agentVisibleOnly() == null || request.agentVisibleOnly()
        );
        List<CapabilityToolCandidate> candidates = toolRetrievalService.retrieve(
                request.query(), scope, topK, request.minScore());
        return ResponseEntity.ok(new SearchResponse(candidates, null));
    }

    @PostMapping("/rebuild")
    public ResponseEntity<?> rebuild(@RequestBody(required = false) RebuildRequest request) {
        try {
            String requested = request == null ? null : request.embeddingModelInstanceId();
            return ResponseEntity.accepted().body(new StartResponse(rebuildManager.start(requested)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
        }
    }

    @GetMapping("/rebuild/status")
    public ResponseEntity<TaskDTO> status(@RequestParam(value = "taskId", required = false) String taskId) {
        Optional<CapabilityToolEmbeddingRebuildTask> task = taskId == null || taskId.isBlank()
                ? rebuildManager.latest()
                : rebuildManager.getTask(taskId);
        return ResponseEntity.ok(task.map(TaskDTO::from).orElse(null));
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(new HealthResponse(
                true,
                true,
                "tool_definition",
                "KEYWORD_FALLBACK_READY",
                "Capability Tool Retrieval is served locally from tool_definition keyword matching",
                rebuildManager.latest().map(TaskDTO::from).orElse(null)
        ));
    }

    public record SearchRequest(
            String query,
            Integer topK,
            List<Long> projectIds,
            List<Long> moduleIds,
            List<Long> toolWhitelist,
            Boolean enabledOnly,
            Boolean agentVisibleOnly,
            Double minScore
    ) {
    }

    public record SearchResponse(List<CapabilityToolCandidate> candidates, String message) {
    }

    public record StartResponse(String taskId) {
    }

    public record RebuildRequest(String embeddingModelInstanceId) {
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
            String embeddingModelInstanceId,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt
    ) {
        static TaskDTO from(CapabilityToolEmbeddingRebuildTask task) {
            return new TaskDTO(
                    task.getTaskId(),
                    task.getStage() == null ? null : task.getStage().name(),
                    task.getTotalSteps(),
                    task.getCompletedSteps(),
                    task.getSuccessCount(),
                    task.getSkippedCount(),
                    task.getFailedCount(),
                    task.getCurrentStep(),
                    task.getEmbeddingModelInstanceId(),
                    task.getErrorMessage(),
                    task.getStartedAt(),
                    task.getFinishedAt()
            );
        }
    }

    public record ApiError(String message) {
    }

    public record HealthResponse(
            boolean enabled,
            boolean ready,
            String collectionName,
            String status,
            String message,
            TaskDTO latestRebuild
    ) {
    }
}

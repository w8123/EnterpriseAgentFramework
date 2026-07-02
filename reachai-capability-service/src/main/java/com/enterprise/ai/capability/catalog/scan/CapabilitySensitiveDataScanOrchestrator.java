package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
public class CapabilitySensitiveDataScanOrchestrator {

    private final CapabilityScanProjectCatalogService scanProjectCatalogService;
    private final CapabilitySensitiveDataScanService sensitiveDataScanService;

    private final ConcurrentMap<String, CapabilitySensitiveDataScanTask> tasks = new ConcurrentHashMap<>();
    private final Map<Long, String> projectLocks = new ConcurrentHashMap<>();

    public String startProjectScan(Long projectId, String modelInstanceId) {
        scanProjectCatalogService.get(projectId);
        if (projectLocks.putIfAbsent(projectId, "-") != null) {
            throw new IllegalStateException("Project already has a running sensitive-data scan: " + projectId);
        }
        CapabilitySensitiveDataScanTask task = new CapabilitySensitiveDataScanTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setProjectId(projectId);
        task.setModelInstanceId(blankToNull(modelInstanceId));
        task.setStage(CapabilitySensitiveDataScanTask.Stage.QUEUED);
        task.setStartedAt(Instant.now());
        tasks.put(task.getTaskId(), task);
        projectLocks.put(projectId, task.getTaskId());
        CompletableFuture.runAsync(() -> runBatch(task));
        return task.getTaskId();
    }

    public Optional<CapabilitySensitiveDataScanTask> getTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tasks.get(taskId));
    }

    public Optional<CapabilitySensitiveDataScanTask> findLatestByProject(Long projectId) {
        return tasks.values().stream()
                .filter(task -> projectId != null && projectId.equals(task.getProjectId()))
                .filter(task -> task.getStartedAt() != null)
                .max((left, right) -> left.getStartedAt().compareTo(right.getStartedAt()));
    }

    void runBatch(CapabilitySensitiveDataScanTask task) {
        task.setStage(CapabilitySensitiveDataScanTask.Stage.RUNNING);
        int failed = 0;
        try {
            List<ScanProjectToolEntity> tools = scanProjectCatalogService.listTools(task.getProjectId());
            task.setTotalSteps(tools.size());
            for (ScanProjectToolEntity tool : tools) {
                task.setCurrentStep("tool:" + tool.getName());
                try {
                    int tokens = sensitiveDataScanService.scanAndPersist(tool, task.getModelInstanceId());
                    task.setTotalTokens(task.getTotalTokens() + tokens);
                } catch (Exception ex) {
                    failed++;
                    sensitiveDataScanService.persistFailure(tool.getId(), ex.getMessage(), null);
                }
                task.setCompletedSteps(task.getCompletedSteps() + 1);
                task.setFailedCount(failed);
            }
            task.setStage(CapabilitySensitiveDataScanTask.Stage.DONE);
            if (failed > 0) {
                task.setErrorMessage("共 " + failed + " 条接口扫描失败，其余已更新");
            }
        } catch (Exception ex) {
            task.setStage(CapabilitySensitiveDataScanTask.Stage.FAILED);
            task.setErrorMessage(ex.getMessage());
        } finally {
            projectLocks.remove(task.getProjectId());
            task.setFinishedAt(Instant.now());
        }
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

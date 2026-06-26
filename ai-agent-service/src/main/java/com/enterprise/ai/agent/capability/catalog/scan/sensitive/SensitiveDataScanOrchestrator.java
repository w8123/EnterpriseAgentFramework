package com.enterprise.ai.agent.capability.catalog.scan.sensitive;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 项目级敏感数据批量扫描：异步任务 + 同项目互斥。
 */
@Slf4j
@Service
public class SensitiveDataScanOrchestrator {

    private final ScanProjectService scanProjectService;
    private final SensitiveDataScanService sensitiveDataScanService;

    private final ConcurrentMap<String, SensitiveDataScanTask> tasks = new ConcurrentHashMap<>();
    private final Map<Long, String> projectLocks = new ConcurrentHashMap<>();

    @Autowired
    @Lazy
    private SensitiveDataScanOrchestrator self;

    public SensitiveDataScanOrchestrator(ScanProjectService scanProjectService,
                                         SensitiveDataScanService sensitiveDataScanService) {
        this.scanProjectService = scanProjectService;
        this.sensitiveDataScanService = sensitiveDataScanService;
    }

    public String startProjectScan(Long projectId, String modelInstanceId) {
        scanProjectService.getById(projectId);
        if (projectLocks.putIfAbsent(projectId, "-") != null) {
            throw new IllegalStateException("项目已有敏感数据扫描任务在进行中: " + projectId);
        }
        SensitiveDataScanTask task = new SensitiveDataScanTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setProjectId(projectId);
        task.setModelInstanceId(blankToNull(modelInstanceId));
        task.setStage(SensitiveDataScanTask.Stage.QUEUED);
        task.setStartedAt(Instant.now());
        tasks.put(task.getTaskId(), task);
        projectLocks.put(projectId, task.getTaskId());
        self.runBatchAsync(task);
        return task.getTaskId();
    }

    public Optional<SensitiveDataScanTask> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public Optional<SensitiveDataScanTask> findLatestByProject(Long projectId) {
        return tasks.values().stream()
                .filter(t -> projectId.equals(t.getProjectId()))
                .max((a, b) -> a.getStartedAt().compareTo(b.getStartedAt()));
    }

    @Async
    public void runBatchAsync(SensitiveDataScanTask task) {
        task.setStage(SensitiveDataScanTask.Stage.RUNNING);
        String modelInstanceId = task.getModelInstanceId();
        try {
            List<ScanProjectToolEntity> tools = scanProjectService.listTools(task.getProjectId());
            task.setTotalSteps(tools.size());
            int failed = 0;
            for (ScanProjectToolEntity t : tools) {
                task.setCurrentStep("tool:" + t.getName());
                try {
                    int tok = sensitiveDataScanService.scanAndPersist(t, modelInstanceId);
                    task.setTotalTokens(task.getTotalTokens() + tok);
                } catch (Exception ex) {
                    failed++;
                    log.warn("[SensitiveDataScan] 单条失败 projectId={} scanToolId={}", task.getProjectId(), t.getId(), ex);
                    sensitiveDataScanService.persistFailure(t.getId(), ex.getMessage(), null);
                }
                task.setCompletedSteps(task.getCompletedSteps() + 1);
                task.setFailedCount(failed);
            }
            task.setStage(SensitiveDataScanTask.Stage.DONE);
            if (failed > 0) {
                task.setErrorMessage("共 " + failed + " 条接口扫描失败，其余已更新");
            }
        } catch (Exception ex) {
            log.error("[SensitiveDataScan] 批量失败 projectId={}", task.getProjectId(), ex);
            task.setStage(SensitiveDataScanTask.Stage.FAILED);
            task.setErrorMessage(ex.getMessage());
        } finally {
            projectLocks.remove(task.getProjectId());
            task.setFinishedAt(Instant.now());
        }
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}

package com.enterprise.ai.capability.catalog.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilityToolRetrievalRebuildManager {

    private final ToolDefinitionMapper toolDefinitionMapper;
    private final CapabilityToolRetrievalSettingService settingService;

    private final ConcurrentMap<String, CapabilityToolEmbeddingRebuildTask> tasks = new ConcurrentHashMap<>();
    private final AtomicReference<String> runningTaskId = new AtomicReference<>();

    public String start(String requestedEmbeddingModelInstanceId) {
        if (!runningTaskId.compareAndSet(null, "-")) {
            throw new IllegalStateException("已有 Tool 检索索引重建任务在进行中");
        }
        String effectiveModel = firstNonBlank(requestedEmbeddingModelInstanceId,
                settingService.findEmbeddingModelInstanceId().orElse(null));
        if (effectiveModel != null) {
            settingService.saveEmbeddingModelInstanceId(effectiveModel);
        }
        CapabilityToolEmbeddingRebuildTask task = new CapabilityToolEmbeddingRebuildTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setEmbeddingModelInstanceId(effectiveModel);
        task.setStartedAt(Instant.now());
        tasks.put(task.getTaskId(), task);
        runningTaskId.set(task.getTaskId());
        CompletableFuture.runAsync(() -> runBatch(task));
        return task.getTaskId();
    }

    public Optional<CapabilityToolEmbeddingRebuildTask> getTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tasks.get(taskId));
    }

    public Optional<CapabilityToolEmbeddingRebuildTask> latest() {
        return tasks.values().stream()
                .filter(task -> task.getStartedAt() != null)
                .max((left, right) -> left.getStartedAt().compareTo(right.getStartedAt()));
    }

    void runBatch(CapabilityToolEmbeddingRebuildTask task) {
        task.setStage(CapabilityToolEmbeddingRebuildTask.Stage.RUNNING);
        try {
            List<ToolDefinitionEntity> tools = toolDefinitionMapper.selectList(new LambdaQueryWrapper<>());
            task.setTotalSteps(tools.size());
            for (ToolDefinitionEntity tool : tools) {
                task.setCurrentStep(tool.getName());
                String text = CapabilityToolRetrievalService.buildText(tool);
                if (text == null || text.isBlank()) {
                    task.setSkippedCount(task.getSkippedCount() + 1);
                } else {
                    task.setSuccessCount(task.getSuccessCount() + 1);
                }
                task.setCompletedSteps(task.getCompletedSteps() + 1);
            }
            task.setStage(CapabilityToolEmbeddingRebuildTask.Stage.DONE);
        } catch (Exception ex) {
            log.warn("[CapabilityToolRetrieval] rebuild failed: {}", ex.toString());
            task.setStage(CapabilityToolEmbeddingRebuildTask.Stage.FAILED);
            task.setErrorMessage(ex.getMessage());
        } finally {
            task.setFinishedAt(Instant.now());
            runningTaskId.set(null);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}

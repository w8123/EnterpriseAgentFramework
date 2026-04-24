package com.enterprise.ai.agent.tool.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 负责「异步全量重建 tool_embeddings」的任务编排；进度模型对齐 SemanticGenerationTask。
 * 同一时间只允许一个重建任务在跑，保证 Milvus 写入侧不会被并发拉爆。
 */
@Slf4j
@Service
public class ToolEmbeddingRebuildManager {

    private final ToolEmbeddingService toolEmbeddingService;
    private final ToolDefinitionMapper toolDefinitionMapper;

    private final ConcurrentMap<String, ToolEmbeddingRebuildTask> tasks = new ConcurrentHashMap<>();
    private final AtomicReference<String> runningTaskId = new AtomicReference<>();

    @Autowired
    @Lazy
    private ToolEmbeddingRebuildManager self;

    public ToolEmbeddingRebuildManager(ToolEmbeddingService toolEmbeddingService,
                                       ToolDefinitionMapper toolDefinitionMapper) {
        this.toolEmbeddingService = toolEmbeddingService;
        this.toolDefinitionMapper = toolDefinitionMapper;
    }

    public String start() {
        if (!runningTaskId.compareAndSet(null, "-")) {
            throw new IllegalStateException("已有 Tool 向量索引重建任务在进行中");
        }
        ToolEmbeddingRebuildTask task = new ToolEmbeddingRebuildTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setStartedAt(Instant.now());
        tasks.put(task.getTaskId(), task);
        runningTaskId.set(task.getTaskId());
        self.runAsync(task);
        return task.getTaskId();
    }

    public Optional<ToolEmbeddingRebuildTask> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public Optional<ToolEmbeddingRebuildTask> latest() {
        return tasks.values().stream()
                .max((a, b) -> a.getStartedAt().compareTo(b.getStartedAt()));
    }

    @Async
    public void runAsync(ToolEmbeddingRebuildTask task) {
        task.setStage(ToolEmbeddingRebuildTask.Stage.RUNNING);
        try {
            if (!toolEmbeddingService.isReady()) {
                toolEmbeddingService.ensureCollection();
            }
            List<ToolDefinitionEntity> all = toolDefinitionMapper.selectList(new LambdaQueryWrapper<>());
            task.setTotalSteps(all.size());
            for (ToolDefinitionEntity tool : all) {
                task.setCurrentStep(tool.getName());
                try {
                    String text = ToolEmbeddingService.buildText(tool);
                    if (text == null || text.isBlank()) {
                        toolEmbeddingService.delete(tool.getId());
                        task.setSkippedCount(task.getSkippedCount() + 1);
                    } else {
                        toolEmbeddingService.upsert(tool);
                        task.setSuccessCount(task.getSuccessCount() + 1);
                    }
                } catch (Exception ex) {
                    log.warn("[ToolEmbeddingRebuild] 条目失败: id={}, name={}, err={}",
                            tool.getId(), tool.getName(), ex.toString());
                    task.setFailedCount(task.getFailedCount() + 1);
                } finally {
                    task.setCompletedSteps(task.getCompletedSteps() + 1);
                }
            }
            task.setStage(ToolEmbeddingRebuildTask.Stage.DONE);
        } catch (Exception ex) {
            log.error("[ToolEmbeddingRebuild] 重建失败", ex);
            task.setStage(ToolEmbeddingRebuildTask.Stage.FAILED);
            task.setErrorMessage(ex.getMessage());
        } finally {
            task.setFinishedAt(Instant.now());
            runningTaskId.set(null);
        }
    }
}

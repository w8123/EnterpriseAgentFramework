package com.enterprise.ai.control.aicoding.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetView;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskTargetEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskTargetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 读取任务目标与 Provider 所需的任务描述，保持单条和列表批量读取的既有顺序。 */
@Component
@RequiredArgsConstructor
class AiCodingTaskDescriptorReader {
    private final AiCodingTaskTargetMapper targetMapper;
    private final AiCodingTaskJsonSupport json;

    List<TaskTargetView> targets(String taskId) {
        return targetMapper.selectList(
                        Wrappers.<AiCodingTaskTargetEntity>lambdaQuery()
                                .eq(AiCodingTaskTargetEntity::getTaskId, taskId)
                                .orderByAsc(AiCodingTaskTargetEntity::getId))
                .stream()
                .map(target -> new TaskTargetView(
                        target.getId(),
                        target.getTargetType(),
                        target.getTargetKey(),
                        target.getTargetRole(),
                        target.getAccessMode(),
                        json.readNode(target.getSnapshotJson())))
                .toList();
    }

    Map<String, List<TaskTargetView>> targetsByTask(
            List<String> taskIds) {
        Map<String, List<TaskTargetView>> result = new LinkedHashMap<>();
        targetMapper.selectList(
                        Wrappers.<AiCodingTaskTargetEntity>lambdaQuery()
                                .in(AiCodingTaskTargetEntity::getTaskId, taskIds)
                                .orderByAsc(AiCodingTaskTargetEntity::getId))
                .forEach(target -> result
                        .computeIfAbsent(
                                target.getTaskId(),
                                ignored -> new ArrayList<>())
                        .add(new TaskTargetView(
                                target.getId(),
                                target.getTargetType(),
                                target.getTargetKey(),
                                target.getTargetRole(),
                                target.getAccessMode(),
                                json.readNode(target.getSnapshotJson()))));
        return result;
    }

    TaskDescriptor descriptor(AiCodingTaskEntity task) {
        return new TaskDescriptor(
                task.getTaskId(),
                task.getProjectId(),
                task.getProjectCode(),
                task.getCapabilityKey(),
                task.getTaskKind(),
                task.getProtocolVersion(),
                task.getExecutorProvider(),
                task.getTitle(),
                task.getObjective(),
                task.getAccessMode(),
                task.getExecutionStatus(),
                json.readNode(task.getContextSnapshotJson()),
                targets(task.getTaskId()),
                task.getStartedAt(),
                task.getCreatedAt());
    }
}

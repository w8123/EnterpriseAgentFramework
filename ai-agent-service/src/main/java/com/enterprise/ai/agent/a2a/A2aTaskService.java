package com.enterprise.ai.agent.a2a;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Service
public class A2aTaskService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final A2aTaskMapper mapper;
    private final ObjectMapper objectMapper;

    public A2aTaskService(A2aTaskMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public A2aTaskEntity createWorking(String taskId, Long endpointId, String agentKey,
                                       String contextId, String userId, Object inputMessage) {
        LocalDateTime now = LocalDateTime.now();
        A2aTaskEntity entity = new A2aTaskEntity();
        entity.setTaskId(taskId);
        entity.setEndpointId(endpointId);
        entity.setAgentKey(agentKey);
        entity.setContextId(contextId);
        entity.setUserId(userId);
        entity.setState("working");
        entity.setInputMessageJson(toJson(inputMessage));
        entity.setStartedAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return entity;
    }

    public void complete(String taskId, Map<String, Object> task, String traceId) {
        A2aTaskEntity entity = findByTaskId(taskId).orElse(null);
        if (entity == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setState("completed");
        entity.setOutputTaskJson(toJson(task));
        entity.setTraceId(traceId);
        entity.setCompletedAt(now);
        entity.setUpdatedAt(now);
        mapper.updateById(entity);
    }

    public void fail(String taskId, String errorMessage) {
        A2aTaskEntity entity = findByTaskId(taskId).orElse(null);
        if (entity == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setState("failed");
        entity.setErrorMessage(errorMessage);
        entity.setCompletedAt(now);
        entity.setUpdatedAt(now);
        mapper.updateById(entity);
    }

    public Optional<A2aTaskEntity> findByTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<A2aTaskEntity>()
                .eq(A2aTaskEntity::getTaskId, taskId)
                .last("limit 1")));
    }

    public Optional<Map<String, Object>> outputTask(A2aTaskEntity entity) {
        if (entity == null || entity.getOutputTaskJson() == null || entity.getOutputTaskJson().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(entity.getOutputTaskJson(), MAP_TYPE));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public boolean cancel(String taskId) {
        A2aTaskEntity entity = findByTaskId(taskId).orElse(null);
        if (entity == null || "completed".equals(entity.getState()) || "failed".equals(entity.getState())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setState("canceled");
        entity.setCompletedAt(now);
        entity.setUpdatedAt(now);
        mapper.updateById(entity);
        return true;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }
}

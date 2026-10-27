package com.enterprise.ai.capability.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.ProjectInstanceMapper;
import com.enterprise.ai.agent.registry.RegistryContracts.InstanceHeartbeatRequest;
import com.enterprise.ai.agent.registry.RegistryContracts.InstanceHeartbeatResponse;
import com.enterprise.ai.capability.catalog.scan.CapabilityProjectInstanceOnlineSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/** Owns instance observations and administrative status within the Registry transaction. */
@Service
@RequiredArgsConstructor
public class RegistryInstanceLifecycleService {
    private final ProjectInstanceMapper instanceMapper;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public InstanceHeartbeatResponse heartbeat(ScanProjectEntity project, InstanceHeartbeatRequest request) {
        if (request == null || !StringUtils.hasText(request.instanceId())) {
            throw new IllegalArgumentException("instanceId 不能为空");
        }
        ProjectInstanceEntity entity = new ProjectInstanceEntity();
        entity.setProjectId(project.getId());
        entity.setProjectCode(project.getProjectCode());
        entity.setInstanceId(request.instanceId());
        LocalDateTime observedAt = LocalDateTime.now();
        entity.setCreatedAt(observedAt);
        entity.setBaseUrl(firstText(request.baseUrl(), project.getBaseUrl()));
        entity.setHost(request.host());
        entity.setPort(request.port());
        entity.setAppVersion(request.appVersion());
        entity.setSdkVersion(request.sdkVersion());
        entity.setMetadataJson(writeJson(request.metadata()));
        entity.setLastHeartbeatAt(observedAt);
        entity.setUpdatedAt(observedAt);
        // The database evaluates the current administrative status while holding the row lock.
        instanceMapper.upsertHeartbeat(entity);
        return new InstanceHeartbeatResponse(getInstance(project, request.instanceId()));
    }

    public List<ProjectInstanceEntity> listInstances(ScanProjectEntity project) {
        List<ProjectInstanceEntity> instances = instanceMapper.selectList(Wrappers.<ProjectInstanceEntity>lambdaQuery()
                .eq(ProjectInstanceEntity::getProjectId, project.getId())
                .orderByDesc(ProjectInstanceEntity::getLastHeartbeatAt));
        LocalDateTime now = LocalDateTime.now();
        // Derive liveness on read; never overwrite a concurrent heartbeat or administrative state.
        instances.forEach(instance -> instance.setStatus(
                CapabilityProjectInstanceOnlineSupport.statusOrNull(instance, now)));
        return instances;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void offline(ScanProjectEntity project, String instanceId) {
        instanceMapper.markOffline(project.getProjectCode(), instanceId, LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectInstanceEntity updateInstanceStatus(ScanProjectEntity project, String instanceId, String status) {
        if (!StringUtils.hasText(instanceId)) {
            throw new IllegalArgumentException("instanceId 不能为空");
        }
        String normalizedStatus = normalizeInstanceStatus(status);
        instanceMapper.updateStatus(project.getProjectCode(), instanceId, normalizedStatus, LocalDateTime.now());
        return getInstance(project, instanceId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int purgeOfflineInstances(ScanProjectEntity project, int minIdleMinutes) {
        int minutes = Math.max(0, minIdleMinutes);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusMinutes(minutes);
        LocalDateTime onlineCutoff = now.minusMinutes(CapabilityProjectInstanceOnlineSupport.HEARTBEAT_ONLINE_MINUTES);
        return instanceMapper.delete(Wrappers.<ProjectInstanceEntity>lambdaQuery()
                .eq(ProjectInstanceEntity::getProjectCode, project.getProjectCode())
                .and(q -> q.in(ProjectInstanceEntity::getStatus, List.of("OFFLINE", "STALE"))
                        .or(expired -> expired.eq(ProjectInstanceEntity::getStatus, "ONLINE")
                                .and(heartbeat -> heartbeat.isNull(ProjectInstanceEntity::getLastHeartbeatAt)
                                        .or().le(ProjectInstanceEntity::getLastHeartbeatAt, onlineCutoff))))
                .and(q -> q.isNull(ProjectInstanceEntity::getLastHeartbeatAt)
                        .or()
                        .lt(ProjectInstanceEntity::getLastHeartbeatAt, cutoff)));
    }

    private ProjectInstanceEntity getInstance(ScanProjectEntity project, String instanceId) {
        ProjectInstanceEntity entity = instanceMapper.selectOne(Wrappers.<ProjectInstanceEntity>lambdaQuery()
                .eq(ProjectInstanceEntity::getProjectCode, project.getProjectCode())
                .eq(ProjectInstanceEntity::getInstanceId, instanceId)
                .last("limit 1 FOR UPDATE"));
        if (entity == null) {
            throw new IllegalArgumentException("实例不存在: " + instanceId);
        }
        entity.setStatus(CapabilityProjectInstanceOnlineSupport.statusOrNull(entity));
        return entity;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private String firstText(String... values) {
        String fallback = null;
        for (String value : values) {
            if (value != null) {
                fallback = value;
            }
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return fallback;
    }

    private String normalizeInstanceStatus(String status) {
        if (!StringUtils.hasText(status)) {
            throw new IllegalArgumentException("status 不能为空");
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ONLINE", "OFFLINE", "DISABLED", "STALE" -> normalized;
            default -> throw new IllegalArgumentException("不支持的实例状态: " + status);
        };
    }
}

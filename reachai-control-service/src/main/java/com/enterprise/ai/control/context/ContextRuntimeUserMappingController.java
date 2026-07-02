package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class ContextRuntimeUserMappingController {

    private final ContextRuntimeUserMappingMapper mapper;

    @GetMapping("/api/context/runtime-user-mappings")
    public ResponseEntity<List<MappingView>> list(
            @RequestParam String tenantId,
            @RequestParam(required = false) Long platformUserId,
            @RequestParam(required = false) String runtimeUserId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        List<MappingView> views = mapper.selectList(Wrappers.<ContextRuntimeUserMappingEntity>lambdaQuery()
                        .eq(ContextRuntimeUserMappingEntity::getTenantId, tenantId)
                        .eq(platformUserId != null, ContextRuntimeUserMappingEntity::getPlatformUserId, platformUserId)
                        .eq(StringUtils.hasText(runtimeUserId),
                                ContextRuntimeUserMappingEntity::getRuntimeUserId, trim(runtimeUserId))
                        .eq(projectId != null, ContextRuntimeUserMappingEntity::getProjectId, projectId)
                        .eq(StringUtils.hasText(projectCode),
                                ContextRuntimeUserMappingEntity::getProjectCode, trim(projectCode))
                        .eq(StringUtils.hasText(status),
                                ContextRuntimeUserMappingEntity::getStatus, trim(status).toUpperCase())
                        .orderByDesc(ContextRuntimeUserMappingEntity::getUpdatedAt)
                        .orderByDesc(ContextRuntimeUserMappingEntity::getId)
                        .last("LIMIT " + boundedLimit))
                .stream()
                .map(this::view)
                .toList();
        return ResponseEntity.ok(views);
    }

    @PostMapping("/api/context/runtime-user-mappings")
    @Transactional
    public ResponseEntity<MappingView> create(@RequestBody CreateCommand command) {
        ContextRuntimeUserMappingEntity entity = new ContextRuntimeUserMappingEntity();
        entity.setTenantId(required(command.tenantId(), "tenantId"));
        entity.setPlatformUserId(required(command.platformUserId(), "platformUserId"));
        entity.setRuntimeUserId(resolveRuntimeUserId(command));
        entity.setGlobalUserId(trim(command.globalUserId()));
        entity.setExternalUserId(trim(command.externalUserId()));
        entity.setProjectId(command.projectId());
        entity.setProjectCode(trim(command.projectCode()));
        entity.setStatus("ACTIVE");
        entity.setCreatedBy("control-service");
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return ResponseEntity.ok(view(entity));
    }

    @DeleteMapping("/api/context/runtime-user-mappings/{id}")
    @Transactional
    public ResponseEntity<MappingView> delete(@PathVariable Long id) {
        ContextRuntimeUserMappingEntity entity = mapper.selectById(id);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setStatus("DELETED");
        entity.setUpdatedAt(now);
        entity.setDeletedAt(now);
        mapper.updateById(entity);
        return ResponseEntity.ok(view(entity));
    }

    private MappingView view(ContextRuntimeUserMappingEntity entity) {
        return new MappingView(
                entity.getId(),
                entity.getTenantId(),
                entity.getPlatformUserId(),
                entity.getRuntimeUserId(),
                entity.getGlobalUserId(),
                entity.getExternalUserId(),
                entity.getProjectId(),
                entity.getProjectCode(),
                entity.getStatus(),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt());
    }

    private String resolveRuntimeUserId(CreateCommand command) {
        String runtimeUserId = trim(command.runtimeUserId());
        if (StringUtils.hasText(runtimeUserId)) {
            return runtimeUserId;
        }
        String globalUserId = trim(command.globalUserId());
        if (StringUtils.hasText(globalUserId)) {
            return globalUserId;
        }
        String externalUserId = trim(command.externalUserId());
        if (StringUtils.hasText(externalUserId)) {
            return externalUserId;
        }
        throw new IllegalArgumentException("Context runtimeUserId, globalUserId or externalUserId is required");
    }

    private String required(String value, String field) {
        String text = trim(value);
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("Context " + field + " is required");
        }
        return text;
    }

    private Long required(Long value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Context " + field + " is required");
        }
        return value;
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record CreateCommand(String tenantId,
                                Long platformUserId,
                                String runtimeUserId,
                                String globalUserId,
                                String externalUserId,
                                Long projectId,
                                String projectCode) {
    }

    public record MappingView(Long id,
                              String tenantId,
                              Long platformUserId,
                              String runtimeUserId,
                              String globalUserId,
                              String externalUserId,
                              Long projectId,
                              String projectCode,
                              String status,
                              String createdBy,
                              LocalDateTime createdAt,
                              LocalDateTime updatedAt,
                              LocalDateTime deletedAt) {
    }
}

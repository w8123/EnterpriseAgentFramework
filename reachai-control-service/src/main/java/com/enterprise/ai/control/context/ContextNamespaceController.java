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
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequiredArgsConstructor
public class ContextNamespaceController {

    private final ContextNamespaceMapper mapper;

    @GetMapping("/api/context/namespaces")
    public ResponseEntity<List<NamespaceView>> list(
            @RequestParam String tenantId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String namespaceType,
            @RequestParam(required = false) String status) {
        List<NamespaceView> views = mapper.selectList(Wrappers.<ContextNamespaceEntity>lambdaQuery()
                        .eq(ContextNamespaceEntity::getTenantId, tenantId)
                        .eq(StringUtils.hasText(projectCode), ContextNamespaceEntity::getProjectCode, trim(projectCode))
                        .eq(projectId != null, ContextNamespaceEntity::getProjectId, projectId)
                        .eq(StringUtils.hasText(namespaceType),
                                ContextNamespaceEntity::getNamespaceType, upper(namespaceType))
                        .eq(StringUtils.hasText(status), ContextNamespaceEntity::getStatus, upper(status))
                        .orderByDesc(ContextNamespaceEntity::getUpdatedAt)
                        .orderByDesc(ContextNamespaceEntity::getId))
                .stream()
                .map(this::view)
                .toList();
        return ResponseEntity.ok(views);
    }

    @PostMapping("/api/context/namespaces")
    @Transactional
    public ResponseEntity<NamespaceView> create(@RequestBody NamespaceCommand command) {
        ContextNamespaceEntity entity = new ContextNamespaceEntity();
        entity.setTenantId(required(command.tenantId(), "tenantId"));
        entity.setNamespaceType(required(command.namespaceType(), "namespaceType").toUpperCase(Locale.ROOT));
        entity.setProjectId(command.projectId());
        entity.setProjectCode(trim(command.projectCode()));
        entity.setOwnerType(upper(command.ownerType()));
        entity.setOwnerId(trim(command.ownerId()));
        entity.setNamespaceKey(StringUtils.hasText(trim(command.namespaceKey()))
                ? trim(command.namespaceKey())
                : generatedKey(entity));
        entity.setDisplayName(trim(command.displayName()));
        entity.setDescription(trim(command.description()));
        entity.setStatus("ACTIVE");
        entity.setCreatedBy(StringUtils.hasText(command.createdBy()) ? trim(command.createdBy()) : "control-service");
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return ResponseEntity.ok(view(entity));
    }

    @GetMapping("/api/context/namespaces/{id}")
    public ResponseEntity<NamespaceView> get(@PathVariable Long id) {
        ContextNamespaceEntity entity = mapper.selectById(id);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(view(entity));
    }

    @DeleteMapping("/api/context/namespaces/{id}")
    @Transactional
    public ResponseEntity<NamespaceView> delete(@PathVariable Long id) {
        ContextNamespaceEntity entity = mapper.selectById(id);
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

    private NamespaceView view(ContextNamespaceEntity entity) {
        return new NamespaceView(
                entity.getId(),
                entity.getNamespaceKey(),
                entity.getNamespaceType(),
                entity.getTenantId(),
                entity.getProjectId(),
                entity.getProjectCode(),
                entity.getOwnerType(),
                entity.getOwnerId(),
                entity.getDisplayName(),
                entity.getDescription(),
                entity.getStatus(),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private String generatedKey(ContextNamespaceEntity entity) {
        String owner = StringUtils.hasText(entity.getOwnerId()) ? entity.getOwnerId() : entity.getProjectCode();
        return Stream.of("ctx", entity.getTenantId(), entity.getNamespaceType(), owner)
                .filter(StringUtils::hasText)
                .map(part -> part.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-"))
                .collect(Collectors.joining(":"));
    }

    private String required(String value, String field) {
        String text = trim(value);
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("Context namespace " + field + " is required");
        }
        return text;
    }

    private String upper(String value) {
        String text = trim(value);
        return StringUtils.hasText(text) ? text.toUpperCase(Locale.ROOT) : null;
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record NamespaceCommand(String namespaceKey,
                                   String namespaceType,
                                   String tenantId,
                                   Long projectId,
                                   String projectCode,
                                   String ownerType,
                                   String ownerId,
                                   String displayName,
                                   String description,
                                   String createdBy) {
    }

    public record NamespaceView(Long id,
                                String namespaceKey,
                                String namespaceType,
                                String tenantId,
                                Long projectId,
                                String projectCode,
                                String ownerType,
                                String ownerId,
                                String displayName,
                                String description,
                                String status,
                                String createdBy,
                                LocalDateTime createdAt,
                                LocalDateTime updatedAt) {
    }
}

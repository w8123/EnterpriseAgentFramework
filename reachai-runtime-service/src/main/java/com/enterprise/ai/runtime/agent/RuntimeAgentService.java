package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RuntimeAgentService {

    private static final Pattern KEY_SLUG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{1,127}");

    private final RuntimeAgentMapper mapper;
    private final RuntimeAgentConfigService configService;

    public List<RuntimeAgentView> list(Long projectId, String projectCode) {
        var query = Wrappers.<RuntimeAgentEntity>lambdaQuery()
                .orderByDesc(RuntimeAgentEntity::getUpdatedAt);
        if (projectId != null) {
            query.eq(RuntimeAgentEntity::getProjectId, projectId);
        }
        if (StringUtils.hasText(projectCode)) {
            query.eq(RuntimeAgentEntity::getProjectCode, projectCode.trim());
        }
        return mapper.selectList(query).stream()
                .map(this::toView)
                .toList();
    }

    public Optional<RuntimeAgentView> findById(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(id.trim()))
                .map(this::toView);
    }

    public Optional<RuntimeAgentView> findByIdOrKeySlug(String idOrKeySlug) {
        if (!StringUtils.hasText(idOrKeySlug)) {
            return Optional.empty();
        }
        String lookup = idOrKeySlug.trim();
        return Optional.ofNullable(mapper.selectByIdOrKeySlug(lookup)).map(this::toView);
    }

    @Transactional
    public RuntimeAgentView create(RuntimeAgentIdentityRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("agent is required");
        }
        RuntimeAgentEntity entity = toEntity(request);
        normalizeForCreate(entity);
        mapper.insert(entity);
        return toView(entity);
    }

    @Transactional
    public RuntimeAgentView update(String id, RuntimeAgentIdentityRequest update) {
        if (StringUtils.hasText(id)) mapper.lockById(id.trim());
        RuntimeAgentEntity current = findEntityById(id)
                .orElseThrow(() -> new IllegalArgumentException("agent not found: " + id));
        if (update != null) {
            merge(current, update);
            current.setUpdatedAt(LocalDateTime.now());
            mapper.update(null, Wrappers.<RuntimeAgentEntity>lambdaUpdate()
                    .eq(RuntimeAgentEntity::getId, current.getId())
                    .set(RuntimeAgentEntity::getKeySlug, current.getKeySlug())
                    .set(RuntimeAgentEntity::getName, current.getName())
                    .set(RuntimeAgentEntity::getDescription, current.getDescription())
                    .set(RuntimeAgentEntity::getProjectId, current.getProjectId())
                    .set(RuntimeAgentEntity::getProjectCode, current.getProjectCode())
                    .set(RuntimeAgentEntity::getVisibility, current.getVisibility())
                    .set(RuntimeAgentEntity::getAllowedRolesJson, current.getAllowedRolesJson())
                    .set(RuntimeAgentEntity::getEnabled, current.getEnabled())
                    .set(RuntimeAgentEntity::getUpdatedAt, current.getUpdatedAt()));
        }
        return toView(current);
    }

    @Transactional
    public boolean delete(String id) {
        if (!StringUtils.hasText(id)) {
            return false;
        }
        String normalizedId = id.trim();
        configService.deleteAllForAgent(normalizedId);
        return mapper.deleteById(normalizedId) > 0;
    }

    private Optional<RuntimeAgentEntity> findEntityById(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(id.trim()));
    }

    private void normalizeForCreate(RuntimeAgentEntity entity) {
        if (!StringUtils.hasText(entity.getId())) {
            entity.setId(newId());
        } else {
            entity.setId(entity.getId().trim());
        }
        requireValidKeySlug(entity.getKeySlug());
        entity.setKeySlug(entity.getKeySlug().trim());
        if (!StringUtils.hasText(entity.getName())) {
            throw new IllegalArgumentException("agent name is required");
        }
        entity.setName(entity.getName().trim());
        if (StringUtils.hasText(entity.getProjectCode())) {
            entity.setProjectCode(entity.getProjectCode().trim());
        }
        if (!StringUtils.hasText(entity.getVisibility())) {
            entity.setVisibility("PROJECT");
        } else {
            entity.setVisibility(entity.getVisibility().trim());
        }
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
    }

    private void merge(RuntimeAgentEntity current, RuntimeAgentIdentityRequest update) {
        if (StringUtils.hasText(update.keySlug())) {
            requireValidKeySlug(update.keySlug());
            current.setKeySlug(update.keySlug().trim());
        }
        if (StringUtils.hasText(update.name())) current.setName(update.name().trim());
        if (update.description() != null) current.setDescription(update.description());
        if (update.projectId() != null) current.setProjectId(update.projectId());
        if (StringUtils.hasText(update.projectCode())) current.setProjectCode(update.projectCode().trim());
        if (StringUtils.hasText(update.visibility())) current.setVisibility(update.visibility().trim());
        if (update.allowedRolesJson() != null) current.setAllowedRolesJson(update.allowedRolesJson());
        if (update.enabled() != null) current.setEnabled(update.enabled());
    }

    private static RuntimeAgentEntity toEntity(RuntimeAgentIdentityRequest view) {
        RuntimeAgentEntity entity = new RuntimeAgentEntity();
        entity.setId(view.id());
        entity.setProjectId(view.projectId());
        entity.setProjectCode(view.projectCode());
        entity.setKeySlug(view.keySlug());
        entity.setName(view.name());
        entity.setDescription(view.description());
        entity.setVisibility(view.visibility());
        entity.setAllowedRolesJson(view.allowedRolesJson());
        entity.setEnabled(view.enabled());
        return entity;
    }

    private RuntimeAgentView toView(RuntimeAgentEntity entity) {
        RuntimeAgentConfigVersionEntity config = configService.resolveDisplayConfig(entity.getId()).orElse(null);
        int workflowToolCount = config == null ? 0 : configService.listTools(entity.getId(), config.getId()).size();
        return new RuntimeAgentView(
                entity.getId(),
                entity.getProjectId(),
                entity.getProjectCode(),
                entity.getKeySlug(),
                entity.getName(),
                entity.getDescription(),
                entity.getVisibility(),
                entity.getAllowedRolesJson(),
                entity.getEnabled(),
                entity.getActiveConfigVersionId(),
                config == null ? null : config.getId(),
                config == null ? null : config.getVersionNo(),
                config == null ? "NONE" : config.getStatus(),
                config == null ? null : config.getRuntimeType(),
                workflowToolCount,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private void requireValidKeySlug(String keySlug) {
        if (!StringUtils.hasText(keySlug) || !KEY_SLUG.matcher(keySlug.trim()).matches()) {
            throw new IllegalArgumentException("invalid agent keySlug: " + keySlug);
        }
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

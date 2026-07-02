package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.workflow.RuntimeAgentEntryEntity;
import com.enterprise.ai.runtime.workflow.RuntimeAgentEntryMapper;
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
public class RuntimeAgentEntryService {

    private static final Pattern KEY_SLUG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{1,127}");

    private final RuntimeAgentEntryMapper mapper;

    public List<RuntimeAgentEntryView> list(Long projectId, String projectCode, String agentKind) {
        var query = Wrappers.<RuntimeAgentEntryEntity>lambdaQuery()
                .orderByDesc(RuntimeAgentEntryEntity::getUpdatedAt);
        if (projectId != null) {
            query.eq(RuntimeAgentEntryEntity::getProjectId, projectId);
        }
        if (StringUtils.hasText(projectCode)) {
            query.eq(RuntimeAgentEntryEntity::getProjectCode, projectCode.trim());
        }
        if (StringUtils.hasText(agentKind)) {
            query.eq(RuntimeAgentEntryEntity::getAgentKind, agentKind.trim());
        }
        return mapper.selectList(query).stream()
                .map(RuntimeAgentEntryService::toView)
                .toList();
    }

    public Optional<RuntimeAgentEntryView> findById(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(id.trim()))
                .map(RuntimeAgentEntryService::toView);
    }

    public Optional<RuntimeAgentEntryView> findByIdOrKeySlug(String idOrKeySlug) {
        if (!StringUtils.hasText(idOrKeySlug)) {
            return Optional.empty();
        }
        String lookup = idOrKeySlug.trim();
        RuntimeAgentEntryEntity byId = mapper.selectById(lookup);
        if (byId != null) {
            return Optional.of(toView(byId));
        }
        return Optional.ofNullable(mapper.selectOne(Wrappers.<RuntimeAgentEntryEntity>lambdaQuery()
                        .eq(RuntimeAgentEntryEntity::getKeySlug, lookup)
                        .last("LIMIT 1")))
                .map(RuntimeAgentEntryService::toView);
    }

    @Transactional
    public RuntimeAgentEntryView create(RuntimeAgentEntryView request) {
        if (request == null) {
            throw new IllegalArgumentException("agent is required");
        }
        RuntimeAgentEntryEntity entity = toEntity(request);
        normalizeForCreate(entity);
        mapper.insert(entity);
        return toView(entity);
    }

    @Transactional
    public RuntimeAgentEntryView update(String id, RuntimeAgentEntryView update) {
        RuntimeAgentEntryEntity current = findEntityById(id)
                .orElseThrow(() -> new IllegalArgumentException("agent not found: " + id));
        if (update != null) {
            merge(current, update);
            current.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(current);
        }
        return toView(current);
    }

    @Transactional
    public boolean delete(String id) {
        return StringUtils.hasText(id) && mapper.deleteById(id.trim()) > 0;
    }

    private Optional<RuntimeAgentEntryEntity> findEntityById(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(id.trim()));
    }

    private void normalizeForCreate(RuntimeAgentEntryEntity entity) {
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
        if (!StringUtils.hasText(entity.getAgentKind())) {
            entity.setAgentKind("PROJECT_ENTRY");
        } else {
            entity.setAgentKind(entity.getAgentKind().trim());
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

    private void merge(RuntimeAgentEntryEntity current, RuntimeAgentEntryView update) {
        if (StringUtils.hasText(update.keySlug())) {
            requireValidKeySlug(update.keySlug());
            current.setKeySlug(update.keySlug().trim());
        }
        if (StringUtils.hasText(update.name())) current.setName(update.name().trim());
        if (update.description() != null) current.setDescription(update.description());
        if (StringUtils.hasText(update.agentKind())) current.setAgentKind(update.agentKind().trim());
        if (update.projectId() != null) current.setProjectId(update.projectId());
        if (StringUtils.hasText(update.projectCode())) current.setProjectCode(update.projectCode().trim());
        if (StringUtils.hasText(update.visibility())) current.setVisibility(update.visibility().trim());
        if (update.systemPrompt() != null) current.setSystemPrompt(update.systemPrompt());
        if (update.modelInstanceId() != null) current.setModelInstanceId(update.modelInstanceId());
        if (update.allowedRolesJson() != null) current.setAllowedRolesJson(update.allowedRolesJson());
        if (update.entryConfigJson() != null) current.setEntryConfigJson(update.entryConfigJson());
        if (update.enabled() != null) current.setEnabled(update.enabled());
    }

    private static RuntimeAgentEntryEntity toEntity(RuntimeAgentEntryView view) {
        RuntimeAgentEntryEntity entity = new RuntimeAgentEntryEntity();
        entity.setId(view.id());
        entity.setProjectId(view.projectId());
        entity.setProjectCode(view.projectCode());
        entity.setKeySlug(view.keySlug());
        entity.setName(view.name());
        entity.setDescription(view.description());
        entity.setAgentKind(view.agentKind());
        entity.setVisibility(view.visibility());
        entity.setSystemPrompt(view.systemPrompt());
        entity.setModelInstanceId(view.modelInstanceId());
        entity.setAllowedRolesJson(view.allowedRolesJson());
        entity.setEntryConfigJson(view.entryConfigJson());
        entity.setEnabled(view.enabled());
        entity.setCreatedAt(view.createdAt());
        entity.setUpdatedAt(view.updatedAt());
        return entity;
    }

    private static RuntimeAgentEntryView toView(RuntimeAgentEntryEntity entity) {
        return new RuntimeAgentEntryView(
                entity.getId(),
                entity.getProjectId(),
                entity.getProjectCode(),
                entity.getKeySlug(),
                entity.getName(),
                entity.getDescription(),
                entity.getAgentKind(),
                entity.getVisibility(),
                entity.getSystemPrompt(),
                entity.getModelInstanceId(),
                entity.getAllowedRolesJson(),
                entity.getEntryConfigJson(),
                entity.getEnabled(),
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

package com.enterprise.ai.agent.workflow;

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
public class AgentEntryService {

    private static final Pattern KEY_SLUG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{1,127}");

    private final AgentEntryMapper mapper;

    public List<AgentEntryEntity> list(Long projectId, String projectCode, String agentKind) {
        var query = Wrappers.<AgentEntryEntity>lambdaQuery()
                .orderByDesc(AgentEntryEntity::getUpdatedAt);
        if (projectId != null) {
            query.eq(AgentEntryEntity::getProjectId, projectId);
        }
        if (StringUtils.hasText(projectCode)) {
            query.eq(AgentEntryEntity::getProjectCode, projectCode.trim());
        }
        if (StringUtils.hasText(agentKind)) {
            query.eq(AgentEntryEntity::getAgentKind, agentKind.trim());
        }
        return mapper.selectList(query);
    }

    public Optional<AgentEntryEntity> findById(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(id));
    }

    public Optional<AgentEntryEntity> findByKeySlug(String keySlug) {
        if (!StringUtils.hasText(keySlug)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(Wrappers.<AgentEntryEntity>lambdaQuery()
                .eq(AgentEntryEntity::getKeySlug, keySlug.trim())
                .last("LIMIT 1")));
    }

    @Transactional
    public AgentEntryEntity create(AgentEntryEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("agent is required");
        }
        normalizeForCreate(entity);
        mapper.insert(entity);
        return entity;
    }

    @Transactional
    public AgentEntryEntity update(String id, AgentEntryEntity update) {
        AgentEntryEntity current = findById(id)
                .orElseThrow(() -> new IllegalArgumentException("agent not found: " + id));
        if (update == null) {
            return current;
        }
        merge(current, update);
        current.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(current);
        return current;
    }

    @Transactional
    public boolean delete(String id) {
        return StringUtils.hasText(id) && mapper.deleteById(id) > 0;
    }

    private void normalizeForCreate(AgentEntryEntity entity) {
        if (!StringUtils.hasText(entity.getId())) {
            entity.setId(newId());
        }
        requireValidKeySlug(entity.getKeySlug());
        if (!StringUtils.hasText(entity.getName())) {
            throw new IllegalArgumentException("agent name is required");
        }
        if (!StringUtils.hasText(entity.getAgentKind())) {
            entity.setAgentKind("PROJECT_ENTRY");
        }
        if (!StringUtils.hasText(entity.getVisibility())) {
            entity.setVisibility("PROJECT");
        }
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
    }

    private void merge(AgentEntryEntity current, AgentEntryEntity update) {
        if (StringUtils.hasText(update.getKeySlug())) {
            requireValidKeySlug(update.getKeySlug());
            current.setKeySlug(update.getKeySlug().trim());
        }
        if (StringUtils.hasText(update.getName())) current.setName(update.getName().trim());
        if (update.getDescription() != null) current.setDescription(update.getDescription());
        if (StringUtils.hasText(update.getAgentKind())) current.setAgentKind(update.getAgentKind().trim());
        if (update.getProjectId() != null) current.setProjectId(update.getProjectId());
        if (StringUtils.hasText(update.getProjectCode())) current.setProjectCode(update.getProjectCode().trim());
        if (StringUtils.hasText(update.getVisibility())) current.setVisibility(update.getVisibility().trim());
        if (update.getSystemPrompt() != null) current.setSystemPrompt(update.getSystemPrompt());
        if (update.getModelInstanceId() != null) current.setModelInstanceId(update.getModelInstanceId());
        if (update.getAllowedRolesJson() != null) current.setAllowedRolesJson(update.getAllowedRolesJson());
        if (update.getEntryConfigJson() != null) current.setEntryConfigJson(update.getEntryConfigJson());
        if (update.getEnabled() != null) current.setEnabled(update.getEnabled());
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

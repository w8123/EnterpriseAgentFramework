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
public class WorkflowDefinitionService {

    private static final Pattern KEY_SLUG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{1,127}");

    private final WorkflowDefinitionMapper mapper;

    public List<WorkflowDefinitionEntity> list(Long projectId, String projectCode, String workflowType, String status) {
        var query = Wrappers.<WorkflowDefinitionEntity>lambdaQuery()
                .orderByDesc(WorkflowDefinitionEntity::getUpdatedAt);
        if (projectId != null) {
            query.eq(WorkflowDefinitionEntity::getProjectId, projectId);
        }
        if (StringUtils.hasText(projectCode)) {
            query.eq(WorkflowDefinitionEntity::getProjectCode, projectCode.trim());
        }
        if (StringUtils.hasText(workflowType)) {
            query.eq(WorkflowDefinitionEntity::getWorkflowType, workflowType.trim());
        }
        if (StringUtils.hasText(status)) {
            query.eq(WorkflowDefinitionEntity::getStatus, status.trim());
        }
        return mapper.selectList(query);
    }

    public Optional<WorkflowDefinitionEntity> findById(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(id));
    }

    public Optional<WorkflowDefinitionEntity> findByKeySlug(String keySlug) {
        if (!StringUtils.hasText(keySlug)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(Wrappers.<WorkflowDefinitionEntity>lambdaQuery()
                .eq(WorkflowDefinitionEntity::getKeySlug, keySlug.trim())
                .last("LIMIT 1")));
    }

    @Transactional
    public WorkflowDefinitionEntity create(WorkflowDefinitionEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("workflow is required");
        }
        normalizeForCreate(entity);
        mapper.insert(entity);
        return entity;
    }

    @Transactional
    public WorkflowDefinitionEntity update(String id, WorkflowDefinitionEntity update) {
        WorkflowDefinitionEntity current = findById(id)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + id));
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

    private void normalizeForCreate(WorkflowDefinitionEntity entity) {
        if (!StringUtils.hasText(entity.getId())) {
            entity.setId(newId());
        }
        requireValidKeySlug(entity.getKeySlug());
        if (!StringUtils.hasText(entity.getName())) {
            throw new IllegalArgumentException("workflow name is required");
        }
        if (!StringUtils.hasText(entity.getWorkflowType())) {
            entity.setWorkflowType("CHAT");
        }
        if (!StringUtils.hasText(entity.getRuntimeType())) {
            entity.setRuntimeType("LANGGRAPH4J");
        }
        if (!StringUtils.hasText(entity.getStatus())) {
            entity.setStatus("DRAFT");
        }
        if (!StringUtils.hasText(entity.getManagedBy())) {
            entity.setManagedBy("MANUAL");
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
    }

    private void merge(WorkflowDefinitionEntity current, WorkflowDefinitionEntity update) {
        if (StringUtils.hasText(update.getKeySlug())) {
            requireValidKeySlug(update.getKeySlug());
            current.setKeySlug(update.getKeySlug().trim());
        }
        if (StringUtils.hasText(update.getName())) current.setName(update.getName().trim());
        if (update.getDescription() != null) current.setDescription(update.getDescription());
        if (update.getProjectId() != null) current.setProjectId(update.getProjectId());
        if (StringUtils.hasText(update.getProjectCode())) current.setProjectCode(update.getProjectCode().trim());
        if (StringUtils.hasText(update.getWorkflowType())) current.setWorkflowType(update.getWorkflowType().trim());
        if (StringUtils.hasText(update.getRuntimeType())) current.setRuntimeType(update.getRuntimeType().trim());
        if (update.getGraphSpecJson() != null) current.setGraphSpecJson(update.getGraphSpecJson());
        if (update.getCanvasJson() != null) current.setCanvasJson(update.getCanvasJson());
        if (update.getInputSchemaJson() != null) current.setInputSchemaJson(update.getInputSchemaJson());
        if (update.getOutputSchemaJson() != null) current.setOutputSchemaJson(update.getOutputSchemaJson());
        if (update.getDefaultModelInstanceId() != null) current.setDefaultModelInstanceId(update.getDefaultModelInstanceId());
        if (update.getDefaultResourceConfigJson() != null) current.setDefaultResourceConfigJson(update.getDefaultResourceConfigJson());
        if (StringUtils.hasText(update.getStatus())) current.setStatus(update.getStatus().trim());
        if (StringUtils.hasText(update.getManagedBy())) current.setManagedBy(update.getManagedBy().trim());
        if (update.getExtraJson() != null) current.setExtraJson(update.getExtraJson());
    }

    private void requireValidKeySlug(String keySlug) {
        if (!StringUtils.hasText(keySlug) || !KEY_SLUG.matcher(keySlug.trim()).matches()) {
            throw new IllegalArgumentException("invalid workflow keySlug: " + keySlug);
        }
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

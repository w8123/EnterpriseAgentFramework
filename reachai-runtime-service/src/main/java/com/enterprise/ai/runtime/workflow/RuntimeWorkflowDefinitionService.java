package com.enterprise.ai.runtime.workflow;

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
public class RuntimeWorkflowDefinitionService {

    private static final Pattern KEY_SLUG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{1,127}");

    private final RuntimeWorkflowDefinitionMapper mapper;
    private final RuntimeWorkflowVersionMapper versionMapper;
    private final RuntimeAgentWorkflowBindingMapper bindingMapper;

    public List<RuntimeWorkflowDefinitionEntity> list(Long projectId, String projectCode, String workflowType, String status) {
        var query = Wrappers.<RuntimeWorkflowDefinitionEntity>lambdaQuery()
                .orderByDesc(RuntimeWorkflowDefinitionEntity::getUpdatedAt);
        if (projectId != null) {
            query.eq(RuntimeWorkflowDefinitionEntity::getProjectId, projectId);
        }
        if (StringUtils.hasText(projectCode)) {
            query.eq(RuntimeWorkflowDefinitionEntity::getProjectCode, projectCode.trim());
        }
        if (StringUtils.hasText(workflowType)) {
            query.eq(RuntimeWorkflowDefinitionEntity::getWorkflowType, workflowType.trim());
        }
        if (StringUtils.hasText(status)) {
            query.eq(RuntimeWorkflowDefinitionEntity::getStatus, status.trim());
        }
        List<RuntimeWorkflowDefinitionEntity> items = mapper.selectList(query);
        for (RuntimeWorkflowDefinitionEntity item : items) {
            item.setDeletable(isDeletable(item));
        }
        return items;
    }

    public Optional<RuntimeWorkflowDefinitionEntity> findById(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(id.trim()));
    }

    public Optional<RuntimeWorkflowDefinitionEntity> findByKeySlug(String keySlug) {
        if (!StringUtils.hasText(keySlug)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(Wrappers.<RuntimeWorkflowDefinitionEntity>lambdaQuery()
                .eq(RuntimeWorkflowDefinitionEntity::getKeySlug, keySlug.trim())
                .last("LIMIT 1")));
    }

    @Transactional
    public RuntimeWorkflowDefinitionEntity create(RuntimeWorkflowDefinitionEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("workflow is required");
        }
        normalizeForCreate(entity);
        mapper.insert(entity);
        return entity;
    }

    @Transactional
    public RuntimeWorkflowDefinitionEntity update(String id, RuntimeWorkflowDefinitionEntity update) {
        RuntimeWorkflowDefinitionEntity current = findById(id)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + id));
        merge(current, update);
        current.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(current);
        return current;
    }

    @Transactional
    public void delete(String id) {
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("workflow id is required");
        }
        String workflowId = id.trim();
        RuntimeWorkflowDefinitionEntity workflow = findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + id));
        if (!"DRAFT".equalsIgnoreCase(workflow.getStatus())) {
            throw new IllegalArgumentException("仅草稿状态的 Workflow 可删除");
        }
        if (!listBindings(workflowId).isEmpty()) {
            throw new IllegalArgumentException("该 Workflow 仍被 Agent 绑定，请先解除绑定后再删除");
        }
        versionMapper.delete(Wrappers.<RuntimeWorkflowVersionEntity>lambdaQuery()
                .eq(RuntimeWorkflowVersionEntity::getWorkflowId, workflowId));
        if (mapper.deleteById(workflowId) <= 0) {
            throw new IllegalArgumentException("workflow not found: " + id);
        }
    }

    public boolean isDeletable(String id) {
        if (!StringUtils.hasText(id)) {
            return false;
        }
        return isDeletable(mapper.selectById(id.trim()));
    }

    private boolean isDeletable(RuntimeWorkflowDefinitionEntity workflow) {
        if (workflow == null || !StringUtils.hasText(workflow.getId())) {
            return false;
        }
        if (!"DRAFT".equalsIgnoreCase(workflow.getStatus())) {
            return false;
        }
        return listBindings(workflow.getId()).isEmpty();
    }

    private List<RuntimeAgentWorkflowBindingEntity> listBindings(String workflowId) {
        return bindingMapper.selectList(Wrappers.<RuntimeAgentWorkflowBindingEntity>lambdaQuery()
                .eq(RuntimeAgentWorkflowBindingEntity::getWorkflowId, workflowId));
    }

    private void normalizeForCreate(RuntimeWorkflowDefinitionEntity entity) {
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

    private void merge(RuntimeWorkflowDefinitionEntity current, RuntimeWorkflowDefinitionEntity update) {
        if (update == null) {
            return;
        }
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

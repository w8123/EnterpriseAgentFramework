package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
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
    private final RuntimeAgentWorkflowToolMapper workflowToolMapper;

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

    public RuntimeWorkflowSearchPage search(Long projectId,
                                            String projectCode,
                                            String workflowType,
                                            String status,
                                            String keyword,
                                            int current,
                                            int size) {
        int safeCurrent = Math.max(current, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Long total = mapper.selectCount(searchQuery(projectId, projectCode, workflowType, status, keyword));
        long totalCount = total == null ? 0L : total;
        if (totalCount == 0L) {
            return new RuntimeWorkflowSearchPage(List.of(), 0L, safeCurrent, safeSize);
        }
        long offset = (long) (safeCurrent - 1) * safeSize;
        List<RuntimeWorkflowDefinitionEntity> records = mapper.selectList(
                searchQuery(projectId, projectCode, workflowType, status, keyword)
                        .orderByDesc(RuntimeWorkflowDefinitionEntity::getUpdatedAt)
                        .last("LIMIT " + offset + ", " + safeSize));
        return new RuntimeWorkflowSearchPage(records, totalCount, safeCurrent, safeSize);
    }

    private LambdaQueryWrapper<RuntimeWorkflowDefinitionEntity> searchQuery(Long projectId,
                                                                             String projectCode,
                                                                             String workflowType,
                                                                             String status,
                                                                             String keyword) {
        var query = Wrappers.<RuntimeWorkflowDefinitionEntity>lambdaQuery();
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
        if (StringUtils.hasText(keyword)) {
            String searchText = keyword.trim();
            query.and(nested -> nested
                    .like(RuntimeWorkflowDefinitionEntity::getName, searchText)
                    .or().like(RuntimeWorkflowDefinitionEntity::getKeySlug, searchText)
                    .or().like(RuntimeWorkflowDefinitionEntity::getDescription, searchText)
                    .or().like(RuntimeWorkflowDefinitionEntity::getProjectCode, searchText)
                    .or().like(RuntimeWorkflowDefinitionEntity::getWorkflowType, searchText));
        }
        return query;
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
        return update(id, update, null);
    }

    @Transactional
    public RuntimeWorkflowDefinitionEntity update(String id,
                                                  RuntimeWorkflowDefinitionEntity update,
                                                  String baseRevision) {
        RuntimeWorkflowDefinitionEntity current = findById(id)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + id));
        LocalDateTime persistedRevision = current.getUpdatedAt();
        LocalDateTime expectedRevision = parseBaseRevision(baseRevision);
        assertRevision(current, baseRevision, expectedRevision);
        merge(current, update);
        current.setUpdatedAt(nextRevision(persistedRevision));
        int updated = expectedRevision == null
                ? mapper.updateById(current)
                : mapper.update(current, Wrappers.<RuntimeWorkflowDefinitionEntity>lambdaUpdate()
                        .eq(RuntimeWorkflowDefinitionEntity::getId, current.getId())
                        .eq(RuntimeWorkflowDefinitionEntity::getUpdatedAt, expectedRevision));
        if (updated <= 0) {
            RuntimeWorkflowDefinitionEntity latest = findById(id).orElse(current);
            throw revisionConflict(id, baseRevision, latest.getUpdatedAt());
        }
        return current;
    }

    public void assertRevision(RuntimeWorkflowDefinitionEntity workflow, String baseRevision) {
        assertRevision(workflow, baseRevision, parseBaseRevision(baseRevision));
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
        if (!listWorkflowTools(workflowId).isEmpty()) {
            throw new IllegalArgumentException("该 Workflow 仍被 Agent 配置为 Workflow-as-Tool，请先从 Agent 配置中移除后再删除");
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
        return listWorkflowTools(workflow.getId()).isEmpty();
    }

    private List<RuntimeAgentWorkflowToolEntity> listWorkflowTools(String workflowId) {
        return workflowToolMapper.selectList(Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                .eq(RuntimeAgentWorkflowToolEntity::getWorkflowId, workflowId));
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
        LocalDateTime now = LocalDateTime.now().withNano(0);
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

    private void assertRevision(RuntimeWorkflowDefinitionEntity workflow,
                                String baseRevision,
                                LocalDateTime expectedRevision) {
        if (expectedRevision == null) {
            return;
        }
        LocalDateTime currentRevision = workflow == null ? null : workflow.getUpdatedAt();
        if (!expectedRevision.equals(currentRevision)) {
            throw revisionConflict(workflow == null ? null : workflow.getId(), baseRevision, currentRevision);
        }
    }

    private LocalDateTime parseBaseRevision(String baseRevision) {
        if (!StringUtils.hasText(baseRevision)) {
            return null;
        }
        try {
            return LocalDateTime.parse(baseRevision.trim());
        } catch (DateTimeParseException ex) {
            throw new RuntimeWorkflowRevisionFormatException(
                    "baseRevision must be an ISO-8601 workflow updatedAt value", ex);
        }
    }

    private LocalDateTime nextRevision(LocalDateTime currentRevision) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        if (currentRevision != null && !now.isAfter(currentRevision)) {
            return currentRevision.plusSeconds(1).withNano(0);
        }
        return now;
    }

    private RuntimeWorkflowRevisionConflictException revisionConflict(String workflowId,
                                                                      String baseRevision,
                                                                      LocalDateTime currentRevision) {
        return new RuntimeWorkflowRevisionConflictException(
                workflowId,
                baseRevision,
                currentRevision == null ? null : currentRevision.toString());
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

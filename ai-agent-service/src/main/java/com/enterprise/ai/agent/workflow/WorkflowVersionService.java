package com.enterprise.ai.agent.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkflowVersionService {

    private final WorkflowVersionMapper versionMapper;
    private final WorkflowDefinitionService workflowService;
    private final WorkflowReleaseValidationService releaseValidationService;
    private final ObjectMapper objectMapper;

    public List<WorkflowVersionEntity> listVersions(String workflowId) {
        return versionMapper.listByWorkflow(workflowId);
    }

    public WorkflowReleaseValidationResult validateRelease(String workflowId) {
        WorkflowDefinitionEntity workflow = workflowService.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
        return releaseValidationService.validate(workflow);
    }

    @Transactional
    public WorkflowVersionEntity publish(String workflowId, WorkflowPublishRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("workflow publish request is required");
        }
        return publish(workflowId,
                request.version(),
                request.rolloutPercent() == null ? 100 : request.rolloutPercent(),
                request.note(),
                request.publishedBy());
    }

    @Transactional
    public WorkflowVersionEntity publish(String workflowId,
                                         String version,
                                         int rolloutPercent,
                                         String note,
                                         String publishedBy) {
        WorkflowDefinitionEntity workflow = workflowService.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
        if (!StringUtils.hasText(version)) {
            throw new IllegalArgumentException("version is required");
        }
        if (rolloutPercent < 0 || rolloutPercent > 100) {
            throw new IllegalArgumentException("rolloutPercent must be between 0 and 100");
        }
        WorkflowReleaseValidationResult validation = releaseValidationService.validate(workflow);
        if (!validation.valid()) {
            String code = validation.errors().isEmpty() ? "UNKNOWN" : validation.errors().get(0).code();
            throw new IllegalArgumentException("workflow release validation failed: " + code);
        }
        WorkflowVersionEntity duplicate = versionMapper.selectOne(Wrappers.<WorkflowVersionEntity>lambdaQuery()
                .eq(WorkflowVersionEntity::getWorkflowId, workflowId)
                .eq(WorkflowVersionEntity::getVersion, version.trim()));
        if (duplicate != null) {
            throw new IllegalArgumentException("workflow version already exists: " + version);
        }
        if (rolloutPercent == 100) {
            retireActiveVersions(workflowId);
        }

        WorkflowVersionEntity entity = new WorkflowVersionEntity();
        entity.setWorkflowId(workflowId);
        entity.setVersion(version.trim());
        entity.setSnapshotJson(writeSnapshot(workflow));
        entity.setGraphSpecSnapshotJson(workflow.getGraphSpecJson());
        entity.setCanvasSnapshotJson(workflow.getCanvasJson());
        entity.setRolloutPercent(rolloutPercent);
        entity.setStatus("ACTIVE");
        entity.setPublishedBy(publishedBy);
        entity.setPublishedAt(LocalDateTime.now());
        entity.setNote(note);
        entity.setCreatedAt(LocalDateTime.now());
        versionMapper.insert(entity);
        WorkflowDefinitionEntity update = new WorkflowDefinitionEntity();
        update.setStatus("ACTIVE");
        workflowService.update(workflowId, update);
        return entity;
    }

    @Transactional
    public WorkflowVersionEntity rollback(String workflowId, Long versionId, String operator) {
        WorkflowVersionEntity target = versionMapper.selectById(versionId);
        if (target == null || !workflowId.equals(target.getWorkflowId())) {
            throw new IllegalArgumentException("workflow version not found: " + versionId);
        }
        retireActiveVersions(workflowId);
        target.setStatus("ACTIVE");
        target.setRolloutPercent(100);
        target.setPublishedBy(operator);
        target.setPublishedAt(LocalDateTime.now());
        versionMapper.updateById(target);
        WorkflowDefinitionEntity update = new WorkflowDefinitionEntity();
        update.setGraphSpecJson(target.getGraphSpecSnapshotJson());
        update.setCanvasJson(target.getCanvasSnapshotJson());
        update.setStatus("ACTIVE");
        workflowService.update(workflowId, update);
        return target;
    }

    public WorkflowVersionEntity resolveActive(String workflowId) {
        List<WorkflowVersionEntity> active = versionMapper.listActive(workflowId);
        return active.isEmpty() ? null : active.get(0);
    }

    private void retireActiveVersions(String workflowId) {
        for (WorkflowVersionEntity active : versionMapper.listActive(workflowId)) {
            active.setStatus("RETIRED");
            versionMapper.updateById(active);
        }
    }

    private String writeSnapshot(WorkflowDefinitionEntity workflow) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", workflow.getId());
        snapshot.put("keySlug", workflow.getKeySlug());
        snapshot.put("name", workflow.getName());
        snapshot.put("workflowType", workflow.getWorkflowType());
        snapshot.put("runtimeType", workflow.getRuntimeType());
        snapshot.put("graphSpec", workflow.getGraphSpecJson());
        snapshot.put("canvas", workflow.getCanvasJson());
        snapshot.put("status", workflow.getStatus());
        snapshot.put("managedBy", workflow.getManagedBy());
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception ex) {
            throw new IllegalStateException("workflow snapshot serialize failed: " + ex.getMessage(), ex);
        }
    }

    public record WorkflowPublishRequest(String version,
                                         Integer rolloutPercent,
                                         String note,
                                         String publishedBy) {
    }
}

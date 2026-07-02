package com.enterprise.ai.runtime.workflow;

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
public class RuntimeWorkflowVersionService {

    private final RuntimeWorkflowVersionMapper versionMapper;
    private final RuntimeWorkflowDefinitionService workflowService;
    private final RuntimeWorkflowReleaseValidationService validationService;
    private final ObjectMapper objectMapper;

    public List<RuntimeWorkflowVersionEntity> listVersions(String workflowId) {
        return versionMapper.listByWorkflow(workflowId);
    }

    public RuntimeWorkflowReleaseValidationResult validateRelease(String workflowId) {
        RuntimeWorkflowDefinitionEntity workflow = workflowService.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
        return validationService.validate(workflow);
    }

    @Transactional
    public RuntimeWorkflowVersionEntity publish(String workflowId,
                                                String version,
                                                int rolloutPercent,
                                                String note,
                                                String publishedBy) {
        RuntimeWorkflowDefinitionEntity workflow = workflowService.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
        if (!StringUtils.hasText(version)) {
            throw new IllegalArgumentException("version is required");
        }
        if (rolloutPercent < 0 || rolloutPercent > 100) {
            throw new IllegalArgumentException("rolloutPercent must be between 0 and 100");
        }
        RuntimeWorkflowReleaseValidationResult validation = validationService.validate(workflow);
        if (!validation.valid()) {
            String code = validation.errors().isEmpty() ? "UNKNOWN" : validation.errors().get(0).code();
            throw new IllegalArgumentException("workflow release validation failed: " + code);
        }
        RuntimeWorkflowVersionEntity duplicate = versionMapper.selectOne(Wrappers.<RuntimeWorkflowVersionEntity>lambdaQuery()
                .eq(RuntimeWorkflowVersionEntity::getWorkflowId, workflowId)
                .eq(RuntimeWorkflowVersionEntity::getVersion, version.trim()));
        if (duplicate != null) {
            throw new IllegalArgumentException("workflow version already exists: " + version);
        }
        if (rolloutPercent == 100) {
            retireActiveVersions(workflowId);
        }

        LocalDateTime now = LocalDateTime.now();
        RuntimeWorkflowVersionEntity entity = new RuntimeWorkflowVersionEntity();
        entity.setWorkflowId(workflowId);
        entity.setVersion(version.trim());
        entity.setSnapshotJson(writeSnapshot(workflow));
        entity.setGraphSpecSnapshotJson(workflow.getGraphSpecJson());
        entity.setCanvasSnapshotJson(workflow.getCanvasJson());
        entity.setRolloutPercent(rolloutPercent);
        entity.setStatus("ACTIVE");
        entity.setPublishedBy(publishedBy);
        entity.setPublishedAt(now);
        entity.setNote(note);
        entity.setCreatedAt(now);
        versionMapper.insert(entity);

        RuntimeWorkflowDefinitionEntity update = new RuntimeWorkflowDefinitionEntity();
        update.setStatus("ACTIVE");
        workflowService.update(workflowId, update);
        return entity;
    }

    @Transactional
    public RuntimeWorkflowVersionEntity rollback(String workflowId, Long versionId, String operator) {
        RuntimeWorkflowVersionEntity target = versionMapper.selectById(versionId);
        if (target == null || !workflowId.equals(target.getWorkflowId())) {
            throw new IllegalArgumentException("workflow version not found: " + versionId);
        }
        retireActiveVersions(workflowId);
        target.setStatus("ACTIVE");
        target.setRolloutPercent(100);
        target.setPublishedBy(operator);
        target.setPublishedAt(LocalDateTime.now());
        versionMapper.updateById(target);

        RuntimeWorkflowDefinitionEntity update = new RuntimeWorkflowDefinitionEntity();
        update.setGraphSpecJson(target.getGraphSpecSnapshotJson());
        update.setCanvasJson(target.getCanvasSnapshotJson());
        update.setStatus("ACTIVE");
        workflowService.update(workflowId, update);
        return target;
    }

    public RuntimeWorkflowVersionEntity resolveActive(String workflowId) {
        List<RuntimeWorkflowVersionEntity> active = versionMapper.listActive(workflowId);
        return active.isEmpty() ? null : active.get(0);
    }

    private void retireActiveVersions(String workflowId) {
        for (RuntimeWorkflowVersionEntity active : versionMapper.listActive(workflowId)) {
            active.setStatus("RETIRED");
            versionMapper.updateById(active);
        }
    }

    private String writeSnapshot(RuntimeWorkflowDefinitionEntity workflow) {
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
}

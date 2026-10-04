package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.graph.GraphSpec;
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
    private final RuntimeCapabilityContractPins capabilityContractPins;
    private final RuntimeWorkflowReleaseEventMapper releaseEvents;
    private final RuntimeWorkflowReferenceIndex referenceIndex;

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
                                                 String publishedBy,
                                                 String baseRevision) {
        requireActor(publishedBy);
        RuntimeWorkflowDefinitionEntity workflow = workflowService.lockForRelease(workflowId, baseRevision);
        if (!StringUtils.hasText(version)) {
            throw new IllegalArgumentException("version is required");
        }
        if (rolloutPercent != 100) {
            throw new IllegalArgumentException(
                    "rolloutPercent must be 100 until deterministic workflow version routing is implemented");
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
        String publishedGraph = capabilityContractPins.pin(workflow.getGraphSpecJson(), workflow);
        String httpApiRiskFloor = capabilityContractPins.httpApiRiskFloor(publishedGraph);
        Long previousVersionId = retireActiveVersions(workflowId);

        LocalDateTime now = LocalDateTime.now();
        RuntimeWorkflowVersionEntity entity = new RuntimeWorkflowVersionEntity();
        entity.setWorkflowId(workflowId);
        entity.setVersion(version.trim());
        entity.setSnapshotJson(writeSnapshot(workflow, publishedGraph, httpApiRiskFloor));
        entity.setGraphSpecSnapshotJson(publishedGraph);
        entity.setCanvasSnapshotJson(workflow.getCanvasJson());
        entity.setRolloutPercent(rolloutPercent);
        entity.setStatus("ACTIVE");
        entity.setPublishedBy(publishedBy);
        entity.setPublishedAt(now);
        entity.setNote(note);
        entity.setCreatedAt(now);
        versionMapper.insert(entity);
        capabilityContractPins.bindPublishedGraph(publishedGraph, entity.getId());
        referenceIndex.indexVersion(entity);

        RuntimeWorkflowDefinitionEntity update = new RuntimeWorkflowDefinitionEntity();
        update.setStatus("ACTIVE");
        workflowService.update(workflowId, update, baseRevision);
        recordRelease(workflowId, "PUBLISH", previousVersionId, entity.getId(), publishedBy, baseRevision);
        return entity;
    }

    @Transactional
    public RuntimeWorkflowVersionEntity rollback(String workflowId, Long versionId, String operator, String baseRevision) {
        requireActor(operator);
        workflowService.lockForRelease(workflowId, baseRevision);
        RuntimeWorkflowVersionEntity target = versionMapper.selectById(versionId);
        if (target == null || !workflowId.equals(target.getWorkflowId())) {
            throw new IllegalArgumentException("workflow version not found: " + versionId);
        }
        RuntimePublishedWorkflowSnapshot published = RuntimePublishedWorkflowSnapshot.read(target);
        // Validate historical snapshot against CURRENT publish policy before any DB writes.
        RuntimeWorkflowReleaseValidationResult.Builder report = RuntimeWorkflowReleaseValidationResult.builder();
        GraphSpec graph = validationService.readGraph(target.getGraphSpecSnapshotJson(), report);
        if (graph == null) {
            RuntimeWorkflowReleaseValidationResult invalid = report.build();
            String code = invalid.errors().isEmpty() ? "GRAPH_SPEC_INVALID" : invalid.errors().get(0).code();
            throw new IllegalArgumentException("workflow rollback validation failed: " + code);
        }
        RuntimeWorkflowReleaseValidationResult validation = validationService.validateProposed(
                published.validationDefinition(), graph);
        if (!validation.valid()) {
            String code = validation.errors().isEmpty() ? "UNKNOWN" : validation.errors().get(0).code();
            throw new IllegalArgumentException("workflow rollback validation failed: " + code);
        }

        capabilityContractPins.validatePinned(target.getGraphSpecSnapshotJson());
        Long previousVersionId = retireActiveVersions(workflowId);
        target.setStatus("ACTIVE");
        target.setRolloutPercent(100);
        versionMapper.updateById(target);

        // Rollback changes the active release. The working copy remains available for continued editing.
        RuntimeWorkflowDefinitionEntity update = new RuntimeWorkflowDefinitionEntity();
        update.setStatus("ACTIVE");
        workflowService.update(workflowId, update, baseRevision);
        recordRelease(workflowId, "ROLLBACK", previousVersionId, target.getId(), operator, baseRevision);
        return target;
    }

    public RuntimeWorkflowVersionEntity resolveActive(String workflowId) {
        List<RuntimeWorkflowVersionEntity> active = versionMapper.listActive(workflowId);
        if (active.size() > 1) throw new IllegalStateException("WORKFLOW_MULTIPLE_ACTIVE_RELEASES: " + workflowId);
        return active.isEmpty() ? null : active.get(0);
    }

    private Long retireActiveVersions(String workflowId) {
        RuntimeWorkflowVersionEntity active = resolveActive(workflowId);
        if (active != null) {
            active.setStatus("RETIRED");
            versionMapper.updateById(active);
        }
        return active == null ? null : active.getId();
    }

    private void requireActor(String actor) {
        if (!StringUtils.hasText(actor) || actor.length() > 64) {
            throw new IllegalArgumentException("WORKFLOW_RELEASE_ACTOR_REQUIRED: 缺少可信发布身份");
        }
    }

    private void recordRelease(String workflowId, String action, Long previousVersionId,
                               Long targetVersionId, String actor, String baseRevision) {
        var event = new RuntimeWorkflowReleaseEventEntity();
        event.setWorkflowId(workflowId);
        event.setAction(action);
        event.setPreviousVersionId(previousVersionId);
        event.setTargetVersionId(targetVersionId);
        event.setActor(actor);
        event.setBaseRevision(baseRevision);
        event.setOccurredAt(LocalDateTime.now());
        releaseEvents.insert(event);
    }

    private String writeSnapshot(RuntimeWorkflowDefinitionEntity workflow, String publishedGraph, String httpApiRiskFloor) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (httpApiRiskFloor != null) {
            snapshot.put("riskLevel", httpApiRiskFloor);
            snapshot.put("httpApiRiskFloor", httpApiRiskFloor);
        }
        snapshot.put("id", workflow.getId());
        snapshot.put("projectId", workflow.getProjectId());
        snapshot.put("projectCode", workflow.getProjectCode());
        snapshot.put("keySlug", workflow.getKeySlug());
        snapshot.put("name", workflow.getName());
        snapshot.put("description", workflow.getDescription());
        snapshot.put("workflowKind", workflow.getWorkflowKind());
        snapshot.put("executionEngine", workflow.getExecutionEngine());
        snapshot.put("graphSpec", publishedGraph);
        snapshot.put("draftGraphSpec", workflow.getGraphSpecJson());
        snapshot.put("canvas", workflow.getCanvasJson());
        snapshot.put("inputSchemaJson", workflow.getInputSchemaJson());
        snapshot.put("outputSchemaJson", workflow.getOutputSchemaJson());
        snapshot.put("defaultModelInstanceId", workflow.getDefaultModelInstanceId());
        snapshot.put("defaultResourceConfigJson", workflow.getDefaultResourceConfigJson());
        snapshot.put("status", workflow.getStatus());
        snapshot.put("definitionAuthority", workflow.getDefinitionAuthority());
        snapshot.put("creationChannel", workflow.getCreationChannel());
        snapshot.put("extraJson", workflow.getExtraJson());
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception ex) {
            throw new IllegalStateException("workflow snapshot serialize failed: " + ex.getMessage(), ex);
        }
    }
}

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
        return publishLocked(workflow, version, rolloutPercent, note, publishedBy, baseRevision, null);
    }

    @Transactional
    public PublicationPreview previewPublication(PublicationRequest request) {
        validatePublicationRequest(request);
        var workflow = workflowService.lockForRelease(request.workflowId(), request.baseRevision());
        requirePublicationOwner(workflow, request);
        requireValidRelease(workflow);
        String graph = capabilityContractPins.pin(workflow.getGraphSpecJson(), workflow);
        return publicationPreview(workflow, request.version(), request.note(), graph, capabilityContractPins.httpApiRiskFloor(graph),
                capabilityContractPins.businessMethodRiskFloor(graph));
    }

    @Transactional
    public RuntimeWorkflowVersionEntity publishApproved(PublicationRequest request, String actor) {
        validatePublicationRequest(request);
        requireActor(actor);
        if (request.expectedPublicationDigest() == null || !request.expectedPublicationDigest().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("WORKFLOW_PUBLICATION_DIGEST_REQUIRED");
        }
        var workflow = workflowService.lockForRelease(request.workflowId(), request.baseRevision());
        requirePublicationOwner(workflow, request);
        return publishLocked(workflow, request.version(), 100, request.note(), actor, request.baseRevision(), request.expectedPublicationDigest());
    }

    private RuntimeWorkflowVersionEntity publishLocked(RuntimeWorkflowDefinitionEntity workflow, String version, int rolloutPercent,
                                                        String note, String publishedBy, String baseRevision, String expectedDigest) {
        String workflowId = workflow.getId();
        if (!StringUtils.hasText(version)) {
            throw new IllegalArgumentException("version is required");
        }
        if (rolloutPercent != 100) {
            throw new IllegalArgumentException(
                    "rolloutPercent must be 100 until deterministic workflow version routing is implemented");
        }
        requireValidRelease(workflow);
        RuntimeWorkflowVersionEntity duplicate = versionMapper.selectOne(Wrappers.<RuntimeWorkflowVersionEntity>lambdaQuery()
                .eq(RuntimeWorkflowVersionEntity::getWorkflowId, workflowId)
                .eq(RuntimeWorkflowVersionEntity::getVersion, version.trim()));
        if (duplicate != null) {
            throw new IllegalArgumentException("workflow version already exists: " + version);
        }
        String publishedGraph = capabilityContractPins.pin(workflow.getGraphSpecJson(), workflow);
        String httpApiRiskFloor = capabilityContractPins.httpApiRiskFloor(publishedGraph);
        String businessMethodRiskFloor = capabilityContractPins.businessMethodRiskFloor(publishedGraph);
        if (expectedDigest != null && !expectedDigest.equals(publicationPreview(workflow, version, note, publishedGraph, httpApiRiskFloor, businessMethodRiskFloor).publicationDigest())) {
            throw new PublicationChanged();
        }
        Long previousVersionId = retireActiveVersions(workflowId);

        LocalDateTime now = LocalDateTime.now();
        RuntimeWorkflowVersionEntity entity = new RuntimeWorkflowVersionEntity();
        entity.setWorkflowId(workflowId);
        entity.setVersion(version.trim());
        entity.setSnapshotJson(writeSnapshot(workflow, publishedGraph, httpApiRiskFloor, businessMethodRiskFloor));
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

    private void requireValidRelease(RuntimeWorkflowDefinitionEntity workflow) {
        var validation = validationService.validate(workflow);
        if (!validation.valid()) {
            String code = validation.errors().isEmpty() ? "UNKNOWN" : validation.errors().get(0).code();
            throw new IllegalArgumentException("workflow release validation failed: " + code);
        }
    }

    private PublicationPreview publicationPreview(RuntimeWorkflowDefinitionEntity workflow, String version, String note, String publishedGraph, String riskFloor, String businessFloor) {
        Map<String, Object> definition;
        try {
            definition = objectMapper.readValue(writeSnapshot(workflow, publishedGraph, riskFloor, businessFloor),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception invalid) { throw new IllegalStateException("Workflow publication snapshot is unavailable", invalid); }
        var active = resolveActive(workflow.getId());
        Long activeId = active == null ? null : active.getId();
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("contractVersion", 1); material.put("definition", definition); material.put("publishedGraph", publishedGraph);
        material.put("activeVersionId", activeId); material.put("version", version.trim()); material.put("note", note);
        material.put("baseRevision", workflow.getUpdatedAt() == null ? null : workflow.getUpdatedAt().toString());
        try {
            byte[] bytes = objectMapper.copy().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .writeValueAsBytes(material);
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            return new PublicationPreview(1, workflow.getId(), workflow.getProjectId(), workflow.getProjectCode(),
                    workflow.getUpdatedAt() == null ? null : workflow.getUpdatedAt().toString(), version.trim(), note,
                    definition, publishedGraph, activeId, digest);
        } catch (Exception failure) { throw new IllegalStateException("Workflow publication snapshot is unavailable", failure); }
    }

    private void validatePublicationRequest(PublicationRequest request) {
        if (request == null || request.contractVersion() != 1 || request.workflowId() == null
                || !request.workflowId().matches("[A-Za-z0-9_-]{1,32}") || !StringUtils.hasText(request.baseRevision())
                || !StringUtils.hasText(request.version()) || request.version().length() > 64
                || request.note() != null && request.note().length() > 4000) throw new IllegalArgumentException("WORKFLOW_PUBLICATION_REQUEST_INVALID");
    }

    private void requirePublicationOwner(RuntimeWorkflowDefinitionEntity workflow, PublicationRequest request) {
        if (!java.util.Objects.equals(workflow.getProjectId(), request.projectId())
                || !java.util.Objects.equals(workflow.getProjectCode(), request.projectCode())) throw new PublicationChanged();
    }

    public record PublicationRequest(int contractVersion, String workflowId, Long projectId, String projectCode,
                                      String baseRevision, String version, String note, String expectedPublicationDigest) { }
    public record PublicationPreview(int contractVersion, String workflowId, Long projectId, String projectCode,
                                      String baseRevision, String version, String note, Map<String, Object> definition,
                                      String publishedGraph, Long activeVersionId, String publicationDigest) { }
    public static final class PublicationChanged extends IllegalArgumentException { }

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

    private String writeSnapshot(RuntimeWorkflowDefinitionEntity workflow, String publishedGraph, String httpApiRiskFloor, String businessMethodRiskFloor) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("httpApiRiskClassified", true);
        if (httpApiRiskFloor != null) {
            snapshot.put("riskLevel", httpApiRiskFloor);
            snapshot.put("httpApiRiskFloor", httpApiRiskFloor);
        }
        if (businessMethodRiskFloor != null) {
            snapshot.put("businessMethodRiskFloor", businessMethodRiskFloor);
            if ("WRITE".equals(businessMethodRiskFloor) || "IRREVERSIBLE".equals(businessMethodRiskFloor))
                snapshot.put("riskLevel", businessMethodRiskFloor);
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

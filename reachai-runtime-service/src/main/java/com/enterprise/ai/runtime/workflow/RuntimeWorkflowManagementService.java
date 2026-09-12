package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/** Workflow-owned manual editing policy and detached management queries. */
@Service
@RequiredArgsConstructor
public class RuntimeWorkflowManagementService {
    private final RuntimeWorkflowDefinitionService definitions;
    private final RuntimeWorkflowVersionService versions;
    private final RuntimeWorkflowReleaseValidationService validation;

    public List<RuntimeWorkflowDefinitionView> list(Long projectId, String projectCode, String workflowKind,
                                                   String definitionAuthority, String status) {
        return definitions.list(projectId, projectCode, workflowKind, definitionAuthority, status).stream()
                .map(RuntimeWorkflowDefinitionView::fromEntity).toList();
    }

    public RuntimeWorkflowSearchPage search(Long projectId, String projectCode, String workflowKind,
                                            String definitionAuthority, String status, String keyword, int current, int size) {
        return definitions.search(projectId, projectCode, workflowKind, definitionAuthority, status, keyword, current, size);
    }

    public List<RuntimeWorkflowResourceBindingService.BindingView> listResourceBindings(String workflowId) {
        return definitions.listResourceBindings(workflowId);
    }

    public Optional<RuntimeWorkflowDefinitionView> findById(String workflowId) {
        return definitions.findById(workflowId).map(RuntimeWorkflowDefinitionView::fromEntity);
    }

    @Transactional
    public RuntimeWorkflowDefinitionView create(RuntimeWorkflowWriteCommand command) {
        requireCommand(command);
        assertOwnedField("definitionAuthority", command.definitionAuthority(), "USER");
        assertOwnedField("creationChannel", command.creationChannel(), "STUDIO");
        assertOwnedField("status", command.status(), "DRAFT");
        RuntimeWorkflowDefinitionEntity draft = editableFields(command);
        draft.setDefinitionAuthority("USER");
        draft.setCreationChannel("STUDIO");
        draft.setStatus("DRAFT");
        return RuntimeWorkflowDefinitionView.fromEntity(definitions.create(draft));
    }

    @Transactional
    public RuntimeWorkflowDefinitionView update(String workflowId, RuntimeWorkflowWriteCommand command) {
        requireCommand(command);
        if (!StringUtils.hasText(command.baseRevision())) {
            throw new RuntimeWorkflowEditingException("WORKFLOW_REVISION_REQUIRED", "保存 Workflow 必须携带已读取的草稿修订");
        }
        RuntimeWorkflowDefinitionEntity current = definitions.lockForWrite(workflowId);
        if (!"USER".equals(current.getDefinitionAuthority()) || "AI_QUICK_ACCESS".equals(current.getCreationChannel())) {
            throw new RuntimeWorkflowEditingException("WORKFLOW_DEFINITION_READ_ONLY", "该 Workflow 由其他入口维护，请从其定义来源更新");
        }
        assertOwnedField("definitionAuthority", command.definitionAuthority(), current.getDefinitionAuthority());
        assertOwnedField("creationChannel", command.creationChannel(), current.getCreationChannel());
        assertOwnedField("status", command.status(), current.getStatus());
        // The same transaction retains the fresh-row lock through draft and reference-index writes.
        return RuntimeWorkflowDefinitionView.fromEntity(definitions.update(workflowId, editableFields(command), command.baseRevision()));
    }

    @Transactional
    public void delete(String workflowId) {
        definitions.delete(workflowId);
    }

    public RuntimeWorkflowReleaseValidationResult validateRuntime(String workflowId, String graphSpecJson,
                                                                  String executionEngine, String defaultModelInstanceId) {
        boolean proposed = StringUtils.hasText(graphSpecJson);
        GraphSpec graph = null;
        if (proposed) {
            var report = RuntimeWorkflowReleaseValidationResult.builder();
            graph = validation.readGraph(graphSpecJson, report);
            if (graph == null) return report.build();
        }
        RuntimeWorkflowDefinitionEntity workflow;
        if (!StringUtils.hasText(workflowId)) {
            workflow = new RuntimeWorkflowDefinitionEntity();
            workflow.setExecutionEngine(executionEngine);
            workflow.setDefaultModelInstanceId(defaultModelInstanceId);
        } else {
            workflow = definitions.findById(workflowId).orElse(null);
            if (workflow != null && proposed && defaultModelInstanceId != null) {
                var candidate = new RuntimeWorkflowDefinitionEntity();
                BeanUtils.copyProperties(workflow, candidate);
                candidate.setDefaultModelInstanceId(defaultModelInstanceId);
                workflow = candidate;
            }
        }
        return proposed ? validation.validateProposed(workflow, graph) : validation.validate(workflow);
    }

    public List<RuntimeWorkflowVersionView> listVersions(String workflowId) {
        return versions.listVersions(workflowId).stream().map(RuntimeWorkflowVersionView::fromEntity).toList();
    }

    public RuntimeWorkflowReleaseValidationResult validateRelease(String workflowId) {
        return versions.validateRelease(workflowId);
    }

    public RuntimeWorkflowVersionView publish(String workflowId, String version, int rolloutPercent,
                                               String note, String actor, String baseRevision) {
        return RuntimeWorkflowVersionView.fromEntity(versions.publish(workflowId, version, rolloutPercent, note, actor, baseRevision));
    }

    public RuntimeWorkflowVersionView rollback(String workflowId, Long versionId, String actor, String baseRevision) {
        return RuntimeWorkflowVersionView.fromEntity(versions.rollback(workflowId, versionId, actor, baseRevision));
    }

    private void requireCommand(RuntimeWorkflowWriteCommand command) {
        if (command == null) throw new IllegalArgumentException("workflow is required");
    }

    private void assertOwnedField(String field, String supplied, String expected) {
        if (StringUtils.hasText(supplied) && !supplied.trim().equalsIgnoreCase(expected)) {
            throw new RuntimeWorkflowEditingException("WORKFLOW_FIELD_OWNED", field + " 由 Workflow 维护，编辑请求不能改写");
        }
    }

    private RuntimeWorkflowDefinitionEntity editableFields(RuntimeWorkflowWriteCommand command) {
        var draft = new RuntimeWorkflowDefinitionEntity();
        draft.setProjectId(command.projectId()); draft.setProjectCode(command.projectCode());
        draft.setKeySlug(command.keySlug()); draft.setName(command.name()); draft.setDescription(command.description());
        draft.setWorkflowKind(command.workflowKind()); draft.setExecutionEngine(command.executionEngine());
        draft.setGraphSpecJson(command.graphSpecJson()); draft.setCanvasJson(command.canvasJson());
        draft.setInputSchemaJson(command.inputSchemaJson()); draft.setOutputSchemaJson(command.outputSchemaJson());
        draft.setDefaultModelInstanceId(command.defaultModelInstanceId());
        draft.setDefaultResourceConfigJson(command.defaultResourceConfigJson()); draft.setExtraJson(command.extraJson());
        return draft;
    }
}

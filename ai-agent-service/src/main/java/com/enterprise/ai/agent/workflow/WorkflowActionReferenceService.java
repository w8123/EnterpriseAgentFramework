package com.enterprise.ai.agent.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.identity.PageActionRegistryEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkflowActionReferenceService {

    private final WorkflowDefinitionMapper workflowMapper;
    private final WorkflowVersionMapper versionMapper;
    private final AgentWorkflowBindingMapper bindingMapper;
    private final AgentEntryMapper agentMapper;
    private final ObjectMapper objectMapper;

    public List<PageActionWorkflowReference> findReferences(PageActionRegistryEntity action) {
        if (action == null || !StringUtils.hasText(action.getProjectCode())
                || !StringUtils.hasText(action.getPageKey())
                || !StringUtils.hasText(action.getActionKey())) {
            return List.of();
        }
        List<WorkflowDefinitionEntity> workflows = workflowMapper.selectList(Wrappers.<WorkflowDefinitionEntity>lambdaQuery()
                .eq(WorkflowDefinitionEntity::getProjectCode, action.getProjectCode())
                .orderByDesc(WorkflowDefinitionEntity::getUpdatedAt)
                .last("LIMIT 500"));
        if (workflows == null || workflows.isEmpty()) {
            return List.of();
        }

        List<PageActionWorkflowReference> references = new ArrayList<>();
        for (WorkflowDefinitionEntity workflow : workflows) {
            references.addAll(findWorkflowReferences(action, workflow));
        }
        return List.copyOf(references);
    }

    private List<PageActionWorkflowReference> findWorkflowReferences(PageActionRegistryEntity action,
                                                                     WorkflowDefinitionEntity workflow) {
        if (workflow == null || !StringUtils.hasText(workflow.getId())) {
            return List.of();
        }
        List<PageActionWorkflowReference> references = new ArrayList<>();
        List<WorkflowVersionEntity> activeVersions = versionMapper.selectList(Wrappers.<WorkflowVersionEntity>lambdaQuery()
                .eq(WorkflowVersionEntity::getWorkflowId, workflow.getId())
                .eq(WorkflowVersionEntity::getStatus, "ACTIVE")
                .orderByDesc(WorkflowVersionEntity::getRolloutPercent)
                .orderByDesc(WorkflowVersionEntity::getId));
        if (activeVersions != null) {
            for (WorkflowVersionEntity version : activeVersions) {
                references.addAll(scanGraph(action, workflow, version, version.getGraphSpecSnapshotJson(), "ACTIVE_VERSION"));
            }
        }
        references.addAll(scanGraph(action, workflow, null, workflow.getGraphSpecJson(), "WORKFLOW_DRAFT"));
        return references;
    }

    private List<PageActionWorkflowReference> scanGraph(PageActionRegistryEntity action,
                                                        WorkflowDefinitionEntity workflow,
                                                        WorkflowVersionEntity version,
                                                        String graphSpecJson,
                                                        String graphSource) {
        if (!StringUtils.hasText(graphSpecJson)) {
            return List.of();
        }
        GraphSpec graphSpec;
        try {
            graphSpec = objectMapper.readValue(graphSpecJson, GraphSpec.class);
        } catch (Exception ignored) {
            return List.of();
        }
        List<GraphSpec.Node> nodes = graphSpec.getNodes() == null ? List.of() : graphSpec.getNodes();
        if (nodes.isEmpty()) {
            return List.of();
        }

        List<AgentWorkflowBindingEntity> bindings = bindingMapper.selectList(Wrappers.<AgentWorkflowBindingEntity>lambdaQuery()
                .eq(AgentWorkflowBindingEntity::getWorkflowId, workflow.getId())
                .orderByDesc(AgentWorkflowBindingEntity::getEnabled)
                .orderByDesc(AgentWorkflowBindingEntity::getPriority)
                .orderByDesc(AgentWorkflowBindingEntity::getId));
        if (bindings == null || bindings.isEmpty()) {
            bindings = List.of((AgentWorkflowBindingEntity) null);
        }

        List<PageActionWorkflowReference> references = new ArrayList<>();
        for (GraphSpec.Node node : nodes) {
            if (!"PAGE_ACTION".equals(AgentGraphNodeType.normalize(node.getType()))) {
                continue;
            }
            Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
            String projectCode = firstText(text(config.get("projectCode")), workflow.getProjectCode());
            String pageKey = text(config.get("pageKey"));
            String actionKey = text(config.get("actionKey"));
            if (!action.getProjectCode().equals(projectCode)
                    || !action.getPageKey().equals(pageKey)
                    || !action.getActionKey().equals(actionKey)) {
                continue;
            }
            for (AgentWorkflowBindingEntity binding : bindings) {
                references.add(toReference(action, workflow, version, graphSource, node, binding));
            }
        }
        return references;
    }

    private PageActionWorkflowReference toReference(PageActionRegistryEntity action,
                                                    WorkflowDefinitionEntity workflow,
                                                    WorkflowVersionEntity version,
                                                    String graphSource,
                                                    GraphSpec.Node node,
                                                    AgentWorkflowBindingEntity binding) {
        AgentEntryEntity agent = binding == null || !StringUtils.hasText(binding.getAgentId())
                ? null
                : agentMapper.selectById(binding.getAgentId());
        return new PageActionWorkflowReference(
                workflow.getId(),
                workflow.getKeySlug(),
                workflow.getName(),
                workflow.getProjectCode(),
                workflow.getStatus(),
                version == null ? null : version.getId(),
                version == null ? null : version.getVersion(),
                graphSource,
                node.getId(),
                node.getName(),
                action.getProjectCode(),
                action.getPageKey(),
                action.getActionKey(),
                agent == null ? null : agent.getId(),
                agent == null ? null : agent.getKeySlug(),
                agent == null ? null : agent.getName(),
                agent == null ? null : agent.getProjectCode(),
                agent == null ? null : agent.getEnabled(),
                binding == null ? null : binding.getId(),
                binding == null ? null : binding.getBindingType(),
                binding != null && Boolean.TRUE.equals(binding.getEnabled()));
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record PageActionWorkflowReference(
            String workflowId,
            String workflowKeySlug,
            String workflowName,
            String workflowProjectCode,
            String workflowStatus,
            Long workflowVersionId,
            String workflowVersion,
            String graphSource,
            String nodeId,
            String nodeName,
            String projectCode,
            String pageKey,
            String actionKey,
            String entryAgentId,
            String entryAgentKeySlug,
            String entryAgentName,
            String entryAgentProjectCode,
            Boolean entryAgentEnabled,
            Long bindingId,
            String bindingType,
            boolean bindingEnabled) {
    }
}

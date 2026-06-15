package com.enterprise.ai.agent.workflow;

import com.enterprise.ai.agent.identity.EmbedSessionEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EmbedWorkflowRuntimeService {

    private final AgentEntryService agentEntryService;
    private final AgentWorkflowResolver resolver;
    private final WorkflowDefinitionService workflowService;
    private final WorkflowVersionService versionService;
    private final WorkflowRuntimeGraphAdapter workflowRuntimeGraphAdapter;

    public Optional<WorkflowRuntimeGraphAdapter.RuntimeGraph> resolveRunnableGraph(EmbedSessionEntity session,
                                                                                      String intentType) {
        return resolveRunnableWorkflowContext(session, intentType)
                .map(context -> workflowRuntimeGraphAdapter.toRuntimeGraph(
                        context.agent(),
                        context.workflow(),
                        context.activeVersion(),
                        WorkflowRuntimeGraphAdapter.RuntimeContextOptions.builder()
                                .binding(context.binding())
                                .build()));
    }

    public Optional<RunnableWorkflowContext> resolveRunnableWorkflowContext(EmbedSessionEntity session, String intentType) {
        if (session == null || !StringUtils.hasText(session.getAgentId())) {
            return Optional.empty();
        }
        AgentEntryEntity agent = resolveAgentEntry(session.getAgentId()).orElse(null);
        if (agent == null || Boolean.FALSE.equals(agent.getEnabled())) {
            return Optional.empty();
        }
        AgentWorkflowResolveRequest request = new AgentWorkflowResolveRequest(
                agent.getId(),
                firstText(session.getProjectCode(), agent.getProjectCode()),
                session.getPageKey(),
                session.getRoute(),
                null,
                intentType);
        AgentWorkflowBindingEntity binding = resolver.resolve(request)
                .or(() -> resolver.resolve(new AgentWorkflowResolveRequest(
                        agent.getKeySlug(),
                        firstText(session.getProjectCode(), agent.getProjectCode()),
                        session.getPageKey(),
                        session.getRoute(),
                        null,
                        intentType)))
                .orElse(null);
        if (binding == null || !StringUtils.hasText(binding.getWorkflowId())) {
            return Optional.empty();
        }
        WorkflowDefinitionEntity workflow = workflowService.findById(binding.getWorkflowId())
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + binding.getWorkflowId()));
        WorkflowVersionEntity activeVersion = versionService.resolveActive(workflow.getId());
        return Optional.of(new RunnableWorkflowContext(agent, binding, workflow, activeVersion));
    }

    public Optional<AgentEntryEntity> resolveAgentEntry(String agentIdOrKeySlug) {
        return agentEntryService.findById(agentIdOrKeySlug)
                .or(() -> agentEntryService.findByKeySlug(agentIdOrKeySlug));
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    public record RunnableWorkflowContext(AgentEntryEntity agent,
                                          AgentWorkflowBindingEntity binding,
                                          WorkflowDefinitionEntity workflow,
                                          WorkflowVersionEntity activeVersion) {
    }
}

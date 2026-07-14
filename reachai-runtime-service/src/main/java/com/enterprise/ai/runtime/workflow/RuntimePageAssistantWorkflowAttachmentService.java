package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigService;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigDraftRequest;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigVersionView;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.WorkflowToolView;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RuntimePageAssistantWorkflowAttachmentService {

    private static final Pattern UNSAFE_KEY_CHARS = Pattern.compile("[^A-Za-z0-9_-]+");

    private final RuntimeCapabilityCatalogClient capabilityClient;
    private final RuntimeWorkflowDefinitionService workflowDefinitionService;
    private final RuntimeAgentMapper agentMapper;
    private final RuntimeAgentConfigService agentConfigService;
    private final ObjectMapper objectMapper;

    @Transactional
    public RuntimePageAssistantWorkflowAttachment attachPublishedPageWorkflow(
            String workflowId,
            RuntimePageAssistantWorkflowAttachRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (!StringUtils.hasText(workflowId)) {
            throw new IllegalArgumentException("workflow id is required");
        }
        ProjectRef project = resolveProject(request);
        RuntimeWorkflowDefinitionEntity workflow = workflowDefinitionService.findById(workflowId.trim())
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
        if (!"PAGE_ASSISTANT".equalsIgnoreCase(String.valueOf(workflow.getWorkflowType()))) {
            throw new IllegalArgumentException("workflow type must be PAGE_ASSISTANT, got: "
                    + workflow.getWorkflowType());
        }
        validateWorkflowProject(workflow, project);

        RuntimeAgentEntity agent = resolveAgent(project, request.agentId());
        String modelInstanceId = requireText(request.modelInstanceId(),
                "modelInstanceId is required to publish the page copilot Supervisor");
        agentConfigService.saveDraft(agent.getId(), new AgentConfigDraftRequest(
                "AGENTSCOPE",
                null,
                modelInstanceId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));
        AgentConfigVersionView draft = agentConfigService.ensureWorkflowToolInDraft(
                agent.getId(), workflow.getId(), false);
        AgentConfigVersionView published = agentConfigService.publish(
                agent.getId(), draft.id(), request.publishedBy());
        WorkflowToolView tool = published.tools().stream()
                .filter(item -> workflow.getId().equals(item.workflowId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "published Agent config is missing page Workflow Tool"));
        return new RuntimePageAssistantWorkflowAttachment(
                agent.getId(),
                agent.getKeySlug(),
                workflow.getId(),
                workflow.getKeySlug(),
                tool.toolName(),
                published.id(),
                published.versionNo(),
                published.status(),
                "ACTIVE".equalsIgnoreCase(published.status()));
    }

    private ProjectRef resolveProject(RuntimePageAssistantWorkflowAttachRequest request) {
        Map<String, Object> body;
        if (request.projectId() != null) {
            body = capabilityClient.getProjectById(request.projectId());
        } else if (StringUtils.hasText(request.projectCode())) {
            body = capabilityClient.getProject(request.projectCode().trim());
        } else {
            throw new IllegalArgumentException("projectId or projectCode is required");
        }
        Long projectId = longValue(body.get("projectId"));
        String projectCode = firstText(stringValue(body.get("projectCode")), request.projectCode());
        if (projectId == null || !StringUtils.hasText(projectCode)) {
            throw new IllegalArgumentException("Capability project lookup response is incomplete");
        }
        return new ProjectRef(projectId, projectCode, body.get("visibility"));
    }

    private RuntimeAgentEntity resolveAgent(ProjectRef project, String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return findOrCreatePageCopilotAgent(project);
        }
        RuntimeAgentEntity agent = agentMapper.selectById(agentId.trim());
        if (agent == null) {
            throw new IllegalArgumentException("agent not found: " + agentId);
        }
        validateAgentProject(agent, project);
        return agent;
    }

    private RuntimeAgentEntity findOrCreatePageCopilotAgent(ProjectRef project) {
        String keySlug = pageCopilotKeySlug(project.projectCode());
        RuntimeAgentEntity existing = agentMapper.selectOne(Wrappers.<RuntimeAgentEntity>lambdaQuery()
                .eq(RuntimeAgentEntity::getKeySlug, keySlug)
                .last("LIMIT 1"));
        if (existing != null) {
            validateAgentProject(existing, project);
            return existing;
        }
        RuntimeAgentEntity entity = new RuntimeAgentEntity();
        entity.setId(newId());
        entity.setProjectId(project.projectId());
        entity.setProjectCode(project.projectCode());
        entity.setKeySlug(keySlug);
        entity.setName(project.projectCode() + " Page Copilot");
        entity.setDescription("Project page copilot Agent for embedded chat, page understanding, and Workflow routing.");
        entity.setVisibility(firstText(stringValue(project.visibility()), "PROJECT"));
        entity.setEnabled(true);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        agentMapper.insert(entity);
        agentConfigService.createInitialDraft(
                entity.getId(),
                "You are the project's page copilot. Understand the user's intent and select the permitted Workflows as tools.",
                null,
                writeJson(Map.of(
                        "source", "page-assistant-wizard",
                        "purpose", "page-copilot",
                        "routing", "supervisor-workflow-tools"
                )));
        return entity;
    }

    private void validateWorkflowProject(RuntimeWorkflowDefinitionEntity workflow, ProjectRef project) {
        if (workflow.getProjectId() != null && !Objects.equals(workflow.getProjectId(), project.projectId())) {
            throw new IllegalArgumentException("workflow project mismatch");
        }
        if (StringUtils.hasText(workflow.getProjectCode())
                && !project.projectCode().equalsIgnoreCase(workflow.getProjectCode().trim())) {
            throw new IllegalArgumentException("workflow project code mismatch");
        }
    }

    private void validateAgentProject(RuntimeAgentEntity agent, ProjectRef project) {
        if (agent.getProjectId() != null && !Objects.equals(agent.getProjectId(), project.projectId())) {
            throw new IllegalArgumentException("agent project mismatch");
        }
        if (StringUtils.hasText(agent.getProjectCode())
                && !project.projectCode().equalsIgnoreCase(agent.getProjectCode().trim())) {
            throw new IllegalArgumentException("agent project code mismatch");
        }
    }

    private String pageCopilotKeySlug(String projectCode) {
        return limitKey(safeKey(requireText(projectCode, "project code is required")) + "-page-copilot");
    }

    private String safeKey(String value) {
        String normalized = UNSAFE_KEY_CHARS.matcher(value.trim().replace('.', '_')).replaceAll("-");
        normalized = normalized.replaceAll("[-_]{2,}", "-");
        normalized = normalized.replaceAll("^[^A-Za-z0-9]+", "");
        normalized = normalized.replaceAll("[^A-Za-z0-9]+$", "");
        if (normalized.length() < 2) {
            normalized = "agent-" + normalized;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String limitKey(String key) {
        return key.length() <= 128 ? key : key.substring(0, 128);
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return Long.parseLong(text.trim());
        }
        return null;
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("failed to serialize page assistant workflow metadata", ex);
        }
    }

    private record ProjectRef(Long projectId, String projectCode, Object visibility) {
    }
}

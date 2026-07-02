package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.compat.RuntimePageAssistantWorkflowBindRequest;
import com.enterprise.ai.runtime.compat.RuntimePageAssistantWorkflowBinding;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RuntimePageAssistantWorkflowBindingService {

    private static final String PAGE_COPILOT_KIND = "PAGE_COPILOT";
    private static final Pattern UNSAFE_KEY_CHARS = Pattern.compile("[^A-Za-z0-9_-]+");

    private final RuntimeCapabilityCatalogClient capabilityClient;
    private final RuntimeWorkflowDefinitionService workflowDefinitionService;
    private final RuntimeAgentEntryMapper agentMapper;
    private final RuntimeAgentWorkflowBindingMapper bindingMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public RuntimePageAssistantWorkflowBinding bindExistingPageWorkflow(
            String workflowId,
            RuntimePageAssistantWorkflowBindRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (!StringUtils.hasText(workflowId)) {
            throw new IllegalArgumentException("workflow id is required");
        }
        ProjectRef project = resolveProject(request);
        String normalizedPageKey = requireText(request.pageKey(), "page key is required");
        List<String> actionKeys = normalizeActionKeys(request.actionKeys());
        RuntimeWorkflowDefinitionEntity workflow = workflowDefinitionService.findById(workflowId.trim())
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
        if (!"PAGE_ASSISTANT".equalsIgnoreCase(String.valueOf(workflow.getWorkflowType()))) {
            throw new IllegalArgumentException("workflow type must be PAGE_ASSISTANT, got: "
                    + workflow.getWorkflowType());
        }
        validateWorkflowProject(workflow, project);

        RuntimeAgentEntryEntity agent = resolveAgent(project, request.agentId());
        RuntimeAgentWorkflowBindingEntity binding = findOrCreatePageBinding(
                agent,
                workflow,
                project.projectCode(),
                normalizedPageKey,
                request.routePattern(),
                actionKeys);
        return new RuntimePageAssistantWorkflowBinding(
                agent.getId(),
                agent.getKeySlug(),
                workflow.getId(),
                workflow.getKeySlug(),
                binding.getId());
    }

    private ProjectRef resolveProject(RuntimePageAssistantWorkflowBindRequest request) {
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

    private RuntimeAgentEntryEntity resolveAgent(ProjectRef project, String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return findOrCreatePageCopilotAgent(project);
        }
        RuntimeAgentEntryEntity agent = agentMapper.selectById(agentId.trim());
        if (agent == null) {
            throw new IllegalArgumentException("agent not found: " + agentId);
        }
        validateAgentProject(agent, project);
        return agent;
    }

    private RuntimeAgentEntryEntity findOrCreatePageCopilotAgent(ProjectRef project) {
        String keySlug = pageCopilotKeySlug(project.projectCode());
        RuntimeAgentEntryEntity existing = agentMapper.selectOne(Wrappers.<RuntimeAgentEntryEntity>lambdaQuery()
                .eq(RuntimeAgentEntryEntity::getKeySlug, keySlug)
                .last("LIMIT 1"));
        if (existing != null) {
            validateAgentProject(existing, project);
            return existing;
        }
        RuntimeAgentEntryEntity entity = new RuntimeAgentEntryEntity();
        entity.setId(newId());
        entity.setProjectId(project.projectId());
        entity.setProjectCode(project.projectCode());
        entity.setKeySlug(keySlug);
        entity.setName(project.projectCode() + " Page Copilot");
        entity.setDescription("Project page copilot Agent for embedded chat, page understanding, and Workflow routing.");
        entity.setAgentKind(PAGE_COPILOT_KIND);
        entity.setVisibility(firstText(stringValue(project.visibility()), "PROJECT"));
        entity.setEnabled(true);
        entity.setSystemPrompt("You are the project's page copilot. Understand the current business page and route executable work to bound Workflows.");
        entity.setEntryConfigJson(writeJson(Map.of(
                "source", "page-assistant-wizard",
                "purpose", "page-copilot",
                "routing", "agent-workflow-binding"
        )));
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        agentMapper.insert(entity);
        return entity;
    }

    private RuntimeAgentWorkflowBindingEntity findOrCreatePageBinding(
            RuntimeAgentEntryEntity agent,
            RuntimeWorkflowDefinitionEntity workflow,
            String projectCode,
            String pageKey,
            String routePattern,
            List<String> actionKeys) {
        List<RuntimeAgentWorkflowBindingEntity> pageBindings = bindingMapper.selectList(
                        Wrappers.<RuntimeAgentWorkflowBindingEntity>lambdaQuery()
                                .eq(RuntimeAgentWorkflowBindingEntity::getAgentId, agent.getId())
                                .eq(RuntimeAgentWorkflowBindingEntity::getBindingType, "PAGE")
                                .eq(RuntimeAgentWorkflowBindingEntity::getPageKey, pageKey)
                                .orderByDesc(RuntimeAgentWorkflowBindingEntity::getPriority)
                                .orderByDesc(RuntimeAgentWorkflowBindingEntity::getUpdatedAt))
                .stream()
                .filter(binding -> "PAGE".equalsIgnoreCase(binding.getBindingType()))
                .filter(binding -> Objects.equals(pageKey, binding.getPageKey()))
                .toList();
        RuntimeAgentWorkflowBindingEntity selected = pageBindings.stream()
                .filter(binding -> Objects.equals(workflow.getId(), binding.getWorkflowId()))
                .findFirst()
                .orElse(pageBindings.isEmpty() ? null : pageBindings.get(0));
        if (selected != null) {
            RuntimeAgentWorkflowBindingEntity current = bindingMapper.selectById(selected.getId());
            if (current == null) {
                throw new IllegalArgumentException("binding not found: " + selected.getId());
            }
            applyPageBindingUpdate(current, workflow, projectCode, pageKey, routePattern, actionKeys);
            bindingMapper.updateById(current);
            return current;
        }

        RuntimeAgentWorkflowBindingEntity entity = new RuntimeAgentWorkflowBindingEntity();
        entity.setAgentId(agent.getId());
        applyPageBindingUpdate(entity, workflow, projectCode, pageKey, routePattern, actionKeys);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        bindingMapper.insert(entity);
        return entity;
    }

    private void applyPageBindingUpdate(RuntimeAgentWorkflowBindingEntity entity,
                                        RuntimeWorkflowDefinitionEntity workflow,
                                        String projectCode,
                                        String pageKey,
                                        String routePattern,
                                        List<String> actionKeys) {
        entity.setWorkflowId(workflow.getId());
        entity.setProjectCode(projectCode);
        entity.setBindingType("PAGE");
        entity.setPageKey(pageKey);
        if (StringUtils.hasText(routePattern)) {
            entity.setRoutePattern(routePattern.trim());
        }
        entity.setPriority(100);
        entity.setEnabled(true);
        entity.setMetadataJson(writeJson(Map.of(
                "source", "page-assistant-wizard",
                "workflowKeySlug", workflow.getKeySlug(),
                "actionKeys", actionKeys
        )));
        entity.setUpdatedAt(LocalDateTime.now());
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

    private void validateAgentProject(RuntimeAgentEntryEntity agent, ProjectRef project) {
        if (agent.getProjectId() != null && !Objects.equals(agent.getProjectId(), project.projectId())) {
            throw new IllegalArgumentException("agent project mismatch");
        }
        if (StringUtils.hasText(agent.getProjectCode())
                && !project.projectCode().equalsIgnoreCase(agent.getProjectCode().trim())) {
            throw new IllegalArgumentException("agent project code mismatch");
        }
    }

    private List<String> normalizeActionKeys(List<String> actionKeys) {
        if (actionKeys == null) {
            return List.of();
        }
        return actionKeys.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
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

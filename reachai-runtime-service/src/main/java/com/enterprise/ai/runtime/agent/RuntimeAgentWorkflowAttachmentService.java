package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.*;
import com.enterprise.ai.runtime.client.model.RuntimeModelCatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/** Agent-owned target provisioning and automatic Workflow attachment publication. */
@Service
@RequiredArgsConstructor
public class RuntimeAgentWorkflowAttachmentService {
    private static final Pattern UNSAFE_KEY_CHARS = Pattern.compile("[^A-Za-z0-9_-]+");
    private static final String DEFAULT_PAGE_COPILOT_DESCRIPTION =
            "项目页面副驾驶 Agent，用于嵌入式对话、页面理解和 Workflow 路由。";
    private static final String DEFAULT_PAGE_COPILOT_SYSTEM_PROMPT =
            "你是当前项目的页面副驾驶 Supervisor。理解用户请求并制定计划，"
                    + "从允许的 Workflow 中选择一个或多个作为 Tool 执行。"
                    + "当前页面的数据、列表、筛选、统计、可见行和详情都属于页面请求；"
                    + "即使用户只说查、查询、统计、多少、哪些或筛选，也应使用匹配的页面 Workflow，"
                    + "不能因为用户没有说打开或操作页面就拒绝。"
                    + "名称、说明和回复默认使用简体中文；Token、MCP、AI、Agent、"
                    + "Supervisor、Workflow、Tool、API、SDK 等熟知专业术语和技术标识可保留英文。";

    private final RuntimeAgentMapper agentMapper;
    private final RuntimeAgentConfigService agentConfigService;
    private final RuntimeModelCatalogClient modelCatalogClient;
    private final ObjectMapper objectMapper;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result attach(ProjectRef project, Command request) {
        RuntimeAgentEntity agent = resolveAgent(project, request.agentId(), request.agentKeySlug());
        // Refresh after the lock: project validation and model selection must use the same target as publication.
        agentMapper.lockById(agent.getId());
        agent = agentMapper.selectById(agent.getId());
        if (agent == null) throw new Failure("AGENT_NOT_FOUND", "Agent was removed before attachment", HttpStatus.NOT_FOUND);
        validateAgentProject(agent, project);
        String model = resolveModelInstanceId(agent, request.modelInstanceId());
        AgentConfigDraftRequest initial = agent.getKeySlug().equals(pageCopilotKeySlug(project.projectCode()))
                ? new AgentConfigDraftRequest("AGENTSCOPE", DEFAULT_PAGE_COPILOT_SYSTEM_PROMPT, model,
                        null, null, null, null, null, null, null, null, null,
                        writeJson(Map.of("source", "ai-coding-attach", "purpose", "page-copilot",
                                "routing", "supervisor-workflow-tools")), null)
                : null;
        var publication = agentConfigService.publishWorkflowAttachment(agent.getId(), request.tool(),
                request.replaceWorkflowId(), model, request.publishedBy(), request.preserveToolConfiguration(), initial);
        return new Result(agent.getId(), agent.getKeySlug(), publication.config(), publication.reused());
    }

    private RuntimeAgentEntity resolveAgent(ProjectRef project, String agentId, String agentKeySlug) {
        if (StringUtils.hasText(agentId)) {
            RuntimeAgentEntity agent = agentMapper.selectById(agentId.trim());
            if (agent == null) {
                throw new Failure(
                        "AGENT_NOT_FOUND", "agent not found by agentId: " + agentId, HttpStatus.NOT_FOUND);
            }
            validateAgentProject(agent, project);
            return agent;
        }
        if (StringUtils.hasText(agentKeySlug)) {
            RuntimeAgentEntity agent = agentMapper.selectOne(Wrappers.<RuntimeAgentEntity>lambdaQuery()
                    .eq(RuntimeAgentEntity::getKeySlug, agentKeySlug.trim())
                    .last("LIMIT 1"));
            if (agent == null) {
                throw new Failure(
                        "AGENT_NOT_FOUND", "agent not found by agentKeySlug: " + agentKeySlug, HttpStatus.NOT_FOUND);
            }
            validateAgentProject(agent, project);
            return agent;
        }
        return findOrCreatePageCopilotAgent(project);
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
        entity.setName(project.projectCode() + " 页面副驾驶 Agent");
        entity.setDescription(DEFAULT_PAGE_COPILOT_DESCRIPTION);
        entity.setVisibility(firstText(stringValue(project.visibility()), "PROJECT"));
        entity.setEnabled(true);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            agentMapper.insert(entity);
            return entity;
        } catch (DuplicateKeyException duplicate) {
            RuntimeAgentEntity winner = agentMapper.selectOne(Wrappers.<RuntimeAgentEntity>lambdaQuery()
                    .eq(RuntimeAgentEntity::getKeySlug, keySlug).last("LIMIT 1 FOR UPDATE"));
            if (winner == null) throw duplicate;
            validateAgentProject(winner, project);
            return winner;
        }
    }

    private String resolveModelInstanceId(RuntimeAgentEntity agent, String requested) {
        try {
            if (StringUtils.hasText(requested)) {
                String modelId = requested.trim();
                if (!modelCatalogClient.isActiveLlm(modelId)) {
                    throw new Failure(
                            "MODEL_INSTANCE_NOT_ACTIVE",
                            "modelInstanceId is missing or not an ACTIVE LLM: " + modelId);
                }
                return modelId;
            }
            Optional<RuntimeAgentConfigVersionEntity> active =
                    agentConfigService.resolveActive(agent.getId());
            if (active.isPresent() && StringUtils.hasText(active.get().getModelInstanceId())) {
                String existing = active.get().getModelInstanceId().trim();
                if (modelCatalogClient.isActiveLlm(existing)) {
                    return existing;
                }
            }
            String selected = modelCatalogClient.firstActiveLlmId();
            if (!StringUtils.hasText(selected)) {
                throw new Failure(
                        "NO_ACTIVE_LLM",
                        "No ACTIVE LLM model instance is available for Supervisor publish.");
            }
            return selected;
        } catch (Failure ex) {
            throw ex;
        } catch (Exception ex) {
            throw new Failure(
                    "RUNTIME_DEPENDENCY_UNAVAILABLE",
                    "Model catalog lookup failed: " + ex.getMessage(),
                    HttpStatus.BAD_GATEWAY);
        }
    }

    private void validateAgentProject(RuntimeAgentEntity agent, ProjectRef project) {
        if (agent.getProjectId() != null && !Objects.equals(agent.getProjectId(), project.projectId())) {
            throw new Failure(
                    "AGENT_PROJECT_MISMATCH", "agent does not belong to the current project");
        }
        if (StringUtils.hasText(agent.getProjectCode())
                && !project.projectCode().equalsIgnoreCase(agent.getProjectCode().trim())) {
            throw new Failure(
                    "AGENT_PROJECT_MISMATCH", "agent project code mismatch");
        }
    }

    private String pageCopilotKeySlug(String projectCode) {
        return limitKey(safeKey(requireText(projectCode, "WORKFLOW_PROJECT_MISSING", "project code is required"))
                + "-page-copilot");
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

    private String requireText(String value, String code, String message) {
        if (!StringUtils.hasText(value)) {
            throw new Failure(code, message);
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


    private String newId() { return UUID.randomUUID().toString().replace("-", ""); }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ex) { throw new Failure("ATTACHMENT_PUBLISH_FAILED", "failed to serialize attachment metadata"); }
    }

    public record ProjectRef(Long projectId, String projectCode, Object visibility) { }
    public record Command(String agentId, String agentKeySlug, String modelInstanceId, String publishedBy,
                          WorkflowToolRequest tool, String replaceWorkflowId, boolean preserveToolConfiguration) { }
    public record Result(String agentId, String agentKeySlug, AgentConfigVersionView config, boolean reused) { }
    public static final class Failure extends RuntimeException {
        private final String code;
        private final HttpStatus status;
        public Failure(String code, String message) { this(code, message, HttpStatus.BAD_REQUEST); }
        public Failure(String code, String message, HttpStatus status) { super(message); this.code = code; this.status = status; }
        public String code() { return code; }
        public HttpStatus status() { return status; }
    }
}

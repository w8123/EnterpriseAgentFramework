package com.enterprise.ai.control.aicoding.api;

import com.enterprise.ai.control.aicoding.api.AiCodingTaskConsoleController;
import com.enterprise.ai.control.aicoding.application.ProjectOnboardingCreationLock;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CreateTaskCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import com.enterprise.ai.control.aicoding.security.AiCodingTaskTokenGuard;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAiCodingPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformProjectOnboardingPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformProjectAgentProvisioningPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Starts the existing project onboarding task through the authenticated platform connection. */
@Service
@RequiredArgsConstructor
public class ProjectOnboardingMcpAdapter implements McpPlatformProjectOnboardingPort {
    private static final Set<String> LANGUAGES = Set.of("java", "csharp", "python", "node", "go");
    private static final Set<String> PROVIDERS = Set.of("CODEX", "CLAUDE_CODE", "CURSOR", "TRAE", "OTHER");
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED");
    private final AiCodingTaskConsoleController console;
    private final CapabilityProjectOnboardingClient projects;
    private final PlatformDelegatedRequestScope scope;
    private final ObjectProvider<McpPlatformAiCodingPort> claims;
    private final ObjectMapper json;
    private final ProjectOnboardingCreationLock onboardingLock;
    private final McpPlatformProjectAgentProvisioningPort projectAgentProvisioning;

    @Transactional
    @Override
    public ResponseEntity<?> start(ConnectionIdentity actor, String projectCode, String sdkLanguage, String executorProvider) {
        String code = projectCode.trim();
        if (!LANGUAGES.contains(sdkLanguage) || !PROVIDERS.contains(executorProvider)) {
            throw new IllegalArgumentException("不支持的 SDK 技术栈或 AI 编程工具");
        }
        if (!actor.session().hasResourcePermission(PlatformPermissions.PLATFORM_WRITE, "PROJECT", null, code)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "缺少项目写入权限");
        }
        onboardingLock.acquire(code);
        Map<String, Object> project = projects.getProjectByCode(code);
        Object rawId = project == null ? null : project.get("id");
        if (!(rawId instanceof Number projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目尚未注册，请先完成首次注册授权和 SDK 注册");
        }
        ResponseEntity<?> response = scope.run(actor.session(), request ->
                console.list(projectId.longValue(), code, "PROJECT_ONBOARDING", null, 200, request));
        if (response.getBody() instanceof List<?> existing) {
            for (Object row : existing) {
                if (row instanceof TaskView task && !TERMINAL.contains(task.executionStatus())) {
                    return resume(actor, task, sdkLanguage, executorProvider);
                }
            }
        }
        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("projectId", projectId.longValue());
        snapshot.put("sdkLanguage", sdkLanguage);
        snapshot.put("startedFrom", "PLATFORM_MCP");
        CreateTaskCommand command = new CreateTaskCommand(projectId.longValue(), code, "PROJECT_ONBOARDING", executorProvider,
                "接入 ReachAI：" + code, "在当前业务仓库完成 ReachAI SDK、网关、业务能力、Embed Token 和真实端到端验证。", null,
                List.of(new TaskTargetCommand("PROJECT", code, "PRIMARY", "READ_WRITE", snapshot)));
        ResponseEntity<?> created = scope.run(actor.session(), request -> console.create(command, request));
        if (!(created.getBody() instanceof TaskView task)) return created;
        Map<String, Object> provisioning = provisionProjectAgent(actor, projectId.longValue());
        ResponseEntity<?> claimed = claim(actor, task);
        if (!claimed.getStatusCode().is2xxSuccessful() || !(claimed.getBody() instanceof Map<?, ?> result)) return claimed;
        Map<String, Object> body = new LinkedHashMap<>();
        result.forEach((key, value) -> body.put(String.valueOf(key), value));
        body.put("projectAgentProvisioning", provisioning);
        return ResponseEntity.status(claimed.getStatusCode()).body(body);
    }

    private Map<String, Object> provisionProjectAgent(ConnectionIdentity actor, long projectId) {
        try {
            ResponseEntity<?> response = projectAgentProvisioning.provision(actor.session(), projectId);
            if (response != null && response.getStatusCode().is2xxSuccessful()) {
                return Map.of("status", "READY", "message", "项目页面助手已就绪。");
            }
            if (response != null && response.getStatusCode().is4xxClientError()) {
                return Map.of("status", "NEEDS_ATTENTION", "message", "项目页面助手尚未就绪，请检查项目权限和模型配置。");
            }
        } catch (ResponseStatusException denied) {
            if (denied.getStatusCode().is4xxClientError()) {
                return Map.of("status", "NEEDS_ATTENTION", "message", "项目页面助手尚未就绪，请检查项目权限和模型配置。");
            }
        } catch (RuntimeException failure) {
            // A lost response may follow a successful write; do not retry automatically.
        }
        return Map.of("status", "UNKNOWN", "message", "项目页面助手配置结果未知，请先检查项目助手状态，不要自动重试。");
    }

    private ResponseEntity<?> resume(ConnectionIdentity actor, TaskView task, String language, String provider) {
        if ("ACCEPTANCE_READY".equals(task.executionStatus())) {
            return result(task, "任务等待用户在项目接入工作台验收。", false);
        }
        if (!provider.equals(task.executorProvider())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有其他 AI 工具的接入任务，请在工作台处理后再继续");
        }
        String selected = task.targets().stream().filter(target -> target.snapshot() != null)
                .map(target -> target.snapshot().path("sdkLanguage").asText("")).filter(value -> !value.isBlank())
                .findFirst().orElse("java");
        if (!language.equals(selected)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "现有接入任务使用其他 SDK 技术栈，请在工作台核对");
        }
        String ref = AiCodingTaskTokenGuard.connectionSessionRef(actor.connectionId());
        if (task.connection() != null && ref.equals(task.connection().clientSessionRef())
                && task.connection().tokenExpiresAt() != null && task.connection().tokenExpiresAt().isAfter(java.time.LocalDateTime.now())) {
            return result(task, "任务已绑定当前连接，调用 reachai_ai_coding_task_context 继续。", true);
        }
        if (!actor.session().user().getUsername().equals(task.createdBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有其他人创建的接入任务，请在工作台处理");
        }
        return claim(actor, task);
    }

    private ResponseEntity<?> claim(ConnectionIdentity actor, TaskView task) {
        McpPlatformAiCodingPort port = claims.getIfAvailable();
        if (port == null) throw new IllegalStateException("任务领取服务不可用");
        ResponseEntity<?> claimed = port.claimAsCreator(actor, task.taskId());
        if (!claimed.getStatusCode().is2xxSuccessful()) return claimed;
        return result(task, "任务已绑定当前连接，调用 reachai_ai_coding_task_context 读取接入要求。", true);
    }

    private static ResponseEntity<?> result(TaskView task, String next, boolean claimed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", task.taskId());
        body.put("projectCode", task.projectCode());
        body.put("executionStatus", task.executionStatus());
        body.put("claimed", claimed);
        body.put("next", next);
        return ResponseEntity.ok(body);
    }

}

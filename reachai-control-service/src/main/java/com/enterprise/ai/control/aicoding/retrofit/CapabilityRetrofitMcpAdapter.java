package com.enterprise.ai.control.aicoding.retrofit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.aicoding.api.AiCodingTaskConsoleController;
import com.enterprise.ai.control.aicoding.application.AiCodingTaskApplicationService;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.AskQuestionCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.CreateTaskCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.QuestionView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDetailView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetCommand;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskValues.ActivationStatus;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskHandoffEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskHandoffMapper;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.aicoding.security.AiCodingTaskTokenGuard;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAiCodingPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCapabilityRetrofitPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Runs the two retrofit MCP tools through the console's own task authorization. */
@Component
class CapabilityRetrofitMcpAdapter implements McpPlatformCapabilityRetrofitPort {

    private static final Set<String> PROVIDERS = Set.of("CODEX", "CLAUDE_CODE", "CURSOR", "TRAE");

    private final AiCodingTaskConsoleController console;
    private final AiCodingTaskApplicationService tasks;
    private final AiCodingTaskHandoffMapper handoffs;
    private final CapabilityProjectOnboardingClient capabilityClient;
    private final PlatformDelegatedRequestScope scope;
    private final ObjectProvider<McpPlatformAiCodingPort> claims;
    private final ObjectMapper json;

    CapabilityRetrofitMcpAdapter(AiCodingTaskConsoleController console,
                                 AiCodingTaskApplicationService tasks,
                                 AiCodingTaskHandoffMapper handoffs,
                                 CapabilityProjectOnboardingClient capabilityClient,
                                 PlatformDelegatedRequestScope scope,
                                 ObjectProvider<McpPlatformAiCodingPort> claims,
                                 ObjectMapper json) {
        this.console = console;
        this.tasks = tasks;
        this.handoffs = handoffs;
        this.capabilityClient = capabilityClient;
        this.scope = scope;
        this.claims = claims;
        this.json = json;
    }

    @Override
    public ResponseEntity<?> start(ConnectionIdentity actor, String projectCode, String objective, String executorProvider) {
        String provider = StringUtils.hasText(executorProvider) ? executorProvider.trim().toUpperCase(Locale.ROOT) : "CODEX";
        if (!PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("executorProvider 只能是 CODEX、CLAUDE_CODE、CURSOR 或 TRAE");
        }
        Map<String, Object> project = capabilityClient.getProjectByCode(projectCode);
        Object rawId = project == null ? null : project.get("id");
        if (!(rawId instanceof Number projectId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "没有找到项目 " + projectCode));
        }
        String goal = objective.trim();
        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("projectId", projectId.longValue());
        snapshot.put("startedFrom", "PLATFORM_MCP");
        CreateTaskCommand command = new CreateTaskCommand(projectId.longValue(), projectCode,
                CapabilityRetrofitProtocol.TASK_KIND, provider,
                "业务能力改造：" + (goal.length() > 60 ? goal.substring(0, 60) : goal), goal, null,
                List.of(new TaskTargetCommand("PROJECT", projectCode, "PRIMARY", "READ_WRITE", snapshot)));
        ResponseEntity<?> created = scope.run(actor.session(), request -> console.create(command, request));
        if (!created.getStatusCode().is2xxSuccessful() || !(created.getBody() instanceof TaskView task)) {
            return created;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", task.taskId());
        body.put("projectCode", task.projectCode());
        body.put("executionStatus", task.executionStatus());
        // Lazily resolved: the AI coding MCP adapter and this one are both injected into the platform MCP service.
        McpPlatformAiCodingPort port = claims.getIfAvailable();
        ResponseEntity<?> claimed = port == null ? null : port.claimAsCreator(actor, task.taskId());
        if (claimed != null && claimed.getStatusCode().is2xxSuccessful()) {
            body.put("claim", claimed.getBody());
            body.put("next", "任务已领取到当前连接。先用 reachai_ai_coding_task_context 读取上下文，下载 skillPackageUrl 里的 reachai-capability-retrofit Skill，"
                    + "扫描仓库后用 reachai_retrofit_propose 提交候选清单。");
        } else {
            body.put("claim", null);
            body.put("claimError", claimed == null ? null : claimed.getBody());
            body.put("next", "任务已建好，但没能领取到当前连接。请开发者在项目「用 AI 改造业务能力」页面打开这个任务，用「交给我的 AI」生成领取码后再用 reachai_ai_coding_task_claim 领取。");
        }
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<?> propose(ConnectionIdentity actor, String taskId, String summary, List<Map<String, Object>> candidates) {
        ResponseEntity<?> read = scope.run(actor.session(), request -> console.detail(taskId, request));
        if (!read.getStatusCode().is2xxSuccessful() || !(read.getBody() instanceof TaskDetailView detail)) {
            return read;
        }
        TaskView task = detail.task();
        if (!CapabilityRetrofitProtocol.TASK_KIND.equals(task.taskKind())) {
            throw new IllegalArgumentException("只能给业务能力改造任务提交候选清单");
        }
        String username = actor.session().user().getUsername();
        if (!StringUtils.hasText(username) || !username.equals(task.createdBy())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "只能操作你自己建的改造任务"));
        }
        if (!heldBy(taskId, actor)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message",
                    "这个任务没有被当前连接领取。先用 reachai_retrofit_start 新建（会自动领取），或用页面上的领取码领取。"));
        }
        ObjectNode source = json.createObjectNode();
        source.put("summary", summary == null ? "" : summary);
        source.set("candidates", json.valueToTree(candidates));
        ObjectNode document = CapabilityRetrofitProtocol.normalizeCandidates(json, source);
        String body = document.toString();
        String digest = sha256(taskId + "\n" + body).substring(0, 20);
        String questionId = CapabilityRetrofitProtocol.SELECTION_QUESTION_PREFIX + digest;
        QuestionView question = tasks.askQuestion(taskId, new AskQuestionCommand(
                AiCodingTaskValues.QUESTION_SCHEMA,
                "retrofit-propose-" + digest,
                questionId,
                CapabilityRetrofitProtocol.SELECTION_QUESTION_TITLE,
                body,
                CapabilityRetrofitProtocol.candidateKeys(document),
                null));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("questionId", question.questionId());
        result.put("status", question.status());
        result.put("candidateCount", document.path("candidates").size());
        result.put("next", "等开发者在 ReachAI 页面勾选，或在对话里直接告诉你选哪些。用 reachai_ai_coding_task_questions 读取回答；"
                + "开发者在对话里明确说了时，可以用 reachai_ai_coding_question_answer 代为回答。");
        return ResponseEntity.ok(result);
    }

    boolean heldBy(String taskId, ConnectionIdentity actor) {
        String ref = AiCodingTaskTokenGuard.connectionSessionRef(actor.connectionId());
        List<AiCodingTaskHandoffEntity> active = handoffs.selectList(Wrappers.<AiCodingTaskHandoffEntity>query()
                .eq("task_id", taskId)
                .eq("activation_status", ActivationStatus.ACTIVATED.name())
                .isNull("closed_at"));
        return active.stream().anyMatch(handoff -> ref.equals(handoff.getClientSessionRef()));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

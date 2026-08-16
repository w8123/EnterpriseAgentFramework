package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.HandoffPackageView;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskHandoffEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiCodingHandoffPromptFactory {

    public static final String HANDOFF_SCHEMA = "reachai.ai-coding.handoff.v1";
    public static final int COMPACT_PROMPT_SAFE_LIMIT = 5_000;
    public static final int TRAE_INPUT_CHARACTER_LIMIT = 6_000;

    private final AiCodingPowerShellBootstrapFactory powerShellBootstrapFactory;

    public HandoffPackageView build(
            String publicBaseUrl,
            AiCodingTaskEntity task,
            AiCodingTaskHandoffEntity handoff,
            String activationCode) {
        String baseUrl = trimTrailingSlash(publicBaseUrl);
        String activationUrl = baseUrl + "/api/ai-coding/handoffs/"
                + handoff.getHandoffId() + "/activate";
        String powerShellBootstrap = powerShellBootstrapFactory.build(
                task.getTaskId(),
                handoff.getHandoffId(),
                task.getExecutorProvider(),
                activationUrl,
                activationCode);
        String prompt = """
                接手 ReachAI AI Coding 任务 `%s`。

                客户端：%s
                交接码有效至：%s

                第一步必须先连接 ReachAI，再读取或修改仓库。不要先扫描代码。Windows PowerShell 执行：

                %s

                连接成功后会从激活响应加载经 SHA-256 校验的 `clientSetup`，并取得 context、UTF-8 写回函数和当前 Windows 用户的 DPAPI 加密恢复能力。任务目标、范围和 Artifact 契约均以 context 为准。凡是写入 ReachAI 或展示给用户的名称、标题、描述、说明、System Prompt、进度、问题、结果和验收材料，默认使用清晰的简体中文；不要仅因 API、Schema 或字段名为英文就生成英文内容。Token、MCP、AI、Agent、Supervisor、Workflow、Tool、API、SDK 等熟知专业术语，以及 keySlug、toolName、代码、路径、协议字段和技术标识可保留英文，必要时使用“中文名称（英文术语）”。不要输出 `$reachAiSession`、`$reachAiHeaders` 或 `taskToken`。

                在读取或修改仓库前，先读取 context 的 `requiredResources`。其中 `requiredBeforeEditing=true` 的资源必须下载并打开其 `entrypoint`；不要因为本地尚未安装 Skill 而跳过网关、SDK 或验证契约。

                获取 context 后按服务端任务状态恢复，不能自行推进状态：

                - `READY` / `RUNNING`：写回 `STARTED`。
                - `WAITING_USER`：用 `Get-ReachAiQuestions` 获取问题；仍有 `OPEN` 问题时不要写 `STARTED` 或 `RESUMED`。可以继续不依赖回答的诊断并写回真实 `PROGRESS`，但这不会回答问题、不会恢复任务，状态仍保持 `WAITING_USER`；全部问题已回答且客户端确实读取后才写回 `RESUMED`。
                - `RESULT_APPLIED`：不要写 `STARTED`；结果已应用但仍待平台 Runtime/E2E 事实，完成缺失的真实链路后请用户在 ReachAI 重新验证。

                执行中用 `Send-ReachAiEvent` 写回真实进度；有业务歧义用 `Send-ReachAiQuestion`。持续执行超过 2 分钟时，在长时间构建/测试前后并至少每 2 分钟调用 `Send-ReachAiHeartbeat`，让 ReachAI 如实显示连接状态。严格遵守 context 的 scope、`protocolGuide` 和 Artifact Schema，完成后调用 `Send-ReachAiArtifact`。同一内容的网络重试必须保持 artifactKey 不变；校验拒绝后修正内容时按 `artifactIdempotencyPolicy` 使用新的 revision key。只有 Artifact 被成功校验并应用，任务才可能完成。

                上下文压缩、换 Shell 或新会话时，先恢复当前任务：

                   . "$env:LOCALAPPDATA\\ReachAI\\ai-coding-sessions\\%s.restore.ps1"

                不要重新激活已消费的交接码；恢复失败时让用户“重新生成交接包”。不扫描无关模块；不安装 ReachAI Runner、常驻程序、后台进程、额外 npm 包或系统软件；保留已有改动。

                完成任务要求的测试和真实浏览器自检，只报告真实证据。ReachAI 无法在本地会话停止后自动唤醒你，停止前先写回真实进度和问题。

                `Send-ReachAiArtifact` 返回 `ACCEPTANCE_READY` 或 `COMPLETED` 时，明确告诉用户：“现在可以回到 ReachAI，在浏览器里验收。”返回 `RESULT_APPLIED` 时只能说明结果已回传、仍待 ReachAI 的 Runtime/E2E 平台验证；返回 `RUNNING` 时按校验消息继续修正，返回 `FAILED` 时明确说明检查未通过。其他状态不得声称可验收或已完成。
                """.formatted(
                task.getTaskId(),
                task.getExecutorProvider(),
                handoff.getActivationExpiresAt(),
                indent(powerShellBootstrap, 3),
                task.getTaskId());
        if (prompt.length() > COMPACT_PROMPT_SAFE_LIMIT) {
            throw new IllegalStateException(
                    "AI Coding handoff prompt exceeds compact safe limit: "
                            + prompt.length());
        }
        Integer promptCharacterLimit = "TRAE".equals(task.getExecutorProvider())
                ? TRAE_INPUT_CHARACTER_LIMIT
                : null;
        return new HandoffPackageView(
                HANDOFF_SCHEMA,
                task.getTaskId(),
                handoff.getHandoffId(),
                task.getProtocolVersion(),
                task.getExecutorProvider(),
                activationUrl,
                activationCode,
                handoff.getActivationExpiresAt(),
                prompt,
                prompt.length(),
                promptCharacterLimit);
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("publicBaseUrl is required");
        }
        String normalized = value.trim();
        return normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    private static String indent(String value, int spaces) {
        String prefix = " ".repeat(spaces);
        return prefix + value.replace("\n", "\n" + prefix);
    }
}

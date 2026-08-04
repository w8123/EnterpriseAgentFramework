package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskEntity;
import com.enterprise.ai.control.aicoding.persistence.AiCodingTaskHandoffEntity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCodingHandoffPromptFactoryTest {

    @Test
    void createsOneTimeTaskScopedZeroInstallPrompt() throws Exception {
        AiCodingTaskEntity task = new AiCodingTaskEntity();
        task.setTaskId("ait_orders");
        task.setObjective("建立订单页面地图");
        task.setExecutorProvider("TRAE");
        task.setProtocolVersion("v1");
        AiCodingTaskHandoffEntity handoff =
                new AiCodingTaskHandoffEntity();
        handoff.setHandoffId("handoff_orders");
        handoff.setActivationExpiresAt(
                LocalDateTime.of(2026, 7, 25, 12, 15));

        AiCodingPowerShellBootstrapFactory bootstrapFactory =
                new AiCodingPowerShellBootstrapFactory();
        var handoffPackage = new AiCodingHandoffPromptFactory(bootstrapFactory)
                .build(
                        "https://reachai.example.com/",
                        task,
                        handoff,
                        "rhc_one_time");
        String prompt = handoffPackage.prompt();

        assertTrue(prompt.contains(
                "https://reachai.example.com/api/ai-coding/handoffs/handoff_orders/activate"));
        assertTrue(prompt.contains("rhc_one_time"));
        assertTrue(prompt.contains("$reachAiSession"));
        assertTrue(prompt.contains("$reachAiHeaders"));
        assertTrue(prompt.contains("clientSetup"));
        assertTrue(prompt.contains("SHA-256"));
        assertTrue(prompt.contains("application/json; charset=utf-8"));
        assertTrue(prompt.contains(
                "名称、标题、描述、说明、System Prompt、进度、问题、结果和验收材料"));
        assertTrue(prompt.contains(
                "不要仅因 API、Schema 或字段名为英文就生成英文内容"));
        assertTrue(prompt.contains(
                "Token、MCP、AI、Agent、Supervisor、Workflow、Tool、API、SDK"));
        assertTrue(prompt.contains(
                "必要时使用“中文名称（英文术语）”"));
        assertTrue(prompt.contains("`READY` / `RUNNING`：写回 `STARTED`"));
        assertTrue(prompt.contains("全部回答后才写回 `RESUMED`"));
        assertTrue(prompt.contains("`RESULT_APPLIED`：不要写 `STARTED`"));
        assertTrue(prompt.contains("artifactIdempotencyPolicy"));
        assertTrue(prompt.contains("至少每 2 分钟调用 `Send-ReachAiHeartbeat`"));
        assertTrue(prompt.contains("Send-ReachAiArtifact"));
        assertTrue(prompt.contains("ait_orders.restore.ps1"));
        assertTrue(prompt.contains("上下文压缩、换 Shell 或新会话"));
        assertTrue(prompt.contains("不要重新激活已消费的交接码"));
        assertTrue(prompt.contains("重新生成交接包"));
        assertTrue(prompt.contains("只有 Artifact 被成功校验并应用"));
        assertTrue(prompt.contains("真实浏览器自检"));
        assertTrue(prompt.contains("无法在本地会话停止后自动唤醒"));
        assertTrue(prompt.contains("不安装 ReachAI Runner"));
        assertTrue(prompt.contains("不要输出 `$reachAiSession`"));
        assertFalse(prompt.contains("X-ReachAI-AiCoding-Key"));
        assertFalse(prompt.contains("npm install"));
        assertFalse(prompt.contains("System.Security.Cryptography.ProtectedData"));
        assertFalse(prompt.contains("function Send-ReachAiArtifact"));
        assertEquals(prompt.length(), handoffPackage.promptCharacters());
        assertEquals(6_000, handoffPackage.promptCharacterLimit());
        assertTrue(prompt.length()
                <= AiCodingHandoffPromptFactory.COMPACT_PROMPT_SAFE_LIMIT);

        var clientSetup = bootstrapFactory.clientSetup(
                "ait_orders",
                "handoff_orders",
                "TRAE");
        byte[] clientSetupBytes = Base64.getDecoder().decode(
                clientSetup.payload());
        String clientSetupSource = new String(
                clientSetupBytes,
                StandardCharsets.UTF_8);
        assertEquals(
                HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(clientSetupBytes)),
                clientSetup.sha256());
        assertTrue(clientSetupSource.contains(
                "Security.Cryptography.ProtectedData"));
        assertTrue(clientSetupSource.contains(
                "DataProtectionScope]::CurrentUser"));
        assertTrue(clientSetupSource.contains(
                "reachai.ai-coding.session-cache.v1"));
        assertFalse(clientSetupSource.contains("rhc_one_time"));

        String restoreScript = bootstrapFactory.restoreScript(
                "ait_orders",
                "handoff_orders",
                "TRAE");
        assertTrue(restoreScript.contains("Send-ReachAiQuestion"));
        assertTrue(restoreScript.contains("Get-ReachAiQuestions"));
        assertTrue(restoreScript.contains("Send-ReachAiArtifact"));
        assertTrue(restoreScript.contains("reachai.ai-coding.event.v1"));
        assertTrue(restoreScript.contains("application/json; charset=utf-8"));
        assertEquals(1, count(restoreScript, "$reachAiSession = Import-ReachAiTaskSession"));
        assertFalse(restoreScript.contains(
                "[datetime]$cacheEnvelope.session.tokenExpiresAt -le (Get-Date)"));
        assertTrue(restoreScript.contains("$reachAiStatusCode -eq 401"));
        assertTrue(restoreScript.contains("$reachAiStatusCode -eq 409"));
        assertFalse(restoreScript.contains("rhc_one_time"));
        assertFalse(restoreScript.contains("activationCode"));
    }

    private static int count(String value, String needle) {
        return (value.length() - value.replace(needle, "").length())
                / needle.length();
    }
}

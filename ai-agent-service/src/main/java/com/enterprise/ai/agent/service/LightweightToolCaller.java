package com.enterprise.ai.agent.service;

import com.enterprise.ai.skill.AiTool;
import com.enterprise.ai.agent.tools.ToolRegistry;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量级 Tool Calling 引擎
 * <p>
 * 为 /api/chat 路径提供简化的工具调用能力。
 * 不依赖完整的 AgentScope ReAct 循环，通过 LLM 输出中的特殊标记识别工具调用意图。
 * <p>
 * 支持的内置工具白名单（完整 Agent 编排走 /api/agent/execute）：
 * - search_knowledge：知识库检索
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LightweightToolCaller {

    private final ToolRegistry toolRegistry;
    private final ToolDefinitionService toolDefinitionService;

    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
            "\\[TOOL_CALL\\]\\s*\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"args\"\\s*:\\s*\\{([^}]*)\\}\\s*\\}",
            Pattern.DOTALL
    );

    /**
     * 构建工具描述文本，嵌入到 system prompt 中
     */
    public String buildToolDescriptions() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n你可以使用以下工具来帮助回答用户问题。如果需要使用工具，请严格使用以下格式输出（不要输出其他内容）：\n");
        sb.append("[TOOL_CALL]{\"name\": \"工具名\", \"args\": {\"参数名\": \"参数值\"}}\n\n");
        sb.append("可用工具：\n");

        for (String toolName : toolDefinitionService.listLightweightEnabledToolNames()) {
            if (toolRegistry.contains(toolName)) {
                AiTool tool = toolRegistry.get(toolName);
                sb.append("- ").append(tool.name()).append("：").append(tool.description()).append("\n");
            }
        }

        sb.append("\n如果不需要使用工具，请直接回答用户问题。");
        return sb.toString();
    }

    /**
     * 尝试从 LLM 的回复中解析工具调用
     *
     * @return 解析结果，若无工具调用则返回 empty
     */
    public Optional<ToolCallResult> parseAndExecute(String llmResponse) {
        Matcher matcher = TOOL_CALL_PATTERN.matcher(llmResponse);
        if (!matcher.find()) {
            return Optional.empty();
        }

        String toolName = matcher.group(1).trim();
        String argsRaw = matcher.group(2).trim();

        if (!toolDefinitionService.isLightweightCallable(toolName)) {
            log.warn("[LightweightTool] 工具不在白名单: {}", toolName);
            return Optional.empty();
        }

        if (!toolRegistry.contains(toolName)) {
            log.warn("[LightweightTool] 工具未注册: {}", toolName);
            return Optional.empty();
        }

        Map<String, Object> args = parseSimpleArgs(argsRaw);
        log.info("[LightweightTool] 执行工具: name={}, args={}", toolName, args);

        try {
            Object result = toolRegistry.execute(toolName, args);
            return Optional.of(new ToolCallResult(toolName, args, String.valueOf(result)));
        } catch (Exception e) {
            log.error("[LightweightTool] 工具执行失败: {}", toolName, e);
            return Optional.of(new ToolCallResult(toolName, args, "工具执行出错: " + e.getMessage()));
        }
    }

    /**
     * 解析简单的 key-value 参数（"key": "value" 格式）
     */
    private Map<String, Object> parseSimpleArgs(String argsRaw) {
        Map<String, Object> args = new LinkedHashMap<>();
        Pattern kvPattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");
        Matcher kvMatcher = kvPattern.matcher(argsRaw);
        while (kvMatcher.find()) {
            args.put(kvMatcher.group(1), kvMatcher.group(2));
        }
        return args;
    }

    public record ToolCallResult(String toolName, Map<String, Object> args, String result) {}
}

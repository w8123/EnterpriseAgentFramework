package com.enterprise.ai.agent.agent;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent执行上下文，贯穿整个工作流生命周期
 * <p>
 * 包含用户输入、识别的意图、中间推理结果、工具调用记录等。
 * 每次Agent执行会创建一个新的上下文实例。
 */
@Data
@Builder
public class AgentContext {

    private String sessionId;
    private String userId;
    private String userMessage;
    private String intentType;

    /** 当前执行到第几步 */
    @Builder.Default
    private int currentStep = 0;

    @Builder.Default
    private int maxSteps = 5;

    /** 工作流各步骤的输出 */
    @Builder.Default
    private List<String> stepOutputs = new ArrayList<>();

    /** 工具调用记录 */
    @Builder.Default
    private List<String> toolCallHistory = new ArrayList<>();

    /** 可在步骤间传递的临时数据 */
    @Builder.Default
    private Map<String, Object> variables = new HashMap<>();

    public boolean hasRemainingSteps() {
        return currentStep < maxSteps;
    }

    public void advanceStep(String output) {
        stepOutputs.add(output);
        currentStep++;
    }

    public void recordToolCall(String toolName) {
        toolCallHistory.add(toolName);
    }
}

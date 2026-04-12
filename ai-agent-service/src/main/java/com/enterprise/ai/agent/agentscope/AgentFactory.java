package com.enterprise.ai.agent.agentscope;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.agentscope.adapter.ToolRegistryAdapter;
import com.enterprise.ai.agent.config.LLMConfig;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 工厂 — 根据 AgentDefinition 配置创建 AgentScope ReActAgent 实例
 * <p>
 * AgentScope 的 ReActAgent 和 Toolkit 均为有状态对象，不可跨请求共享。
 * 每次请求需通过本工厂创建独立实例。
 * <p>
 * 核心方法 {@link #buildFromDefinition(AgentDefinition)} 根据配置动态构建 Agent，
 * 实现"定义即路由"，新增领域 Agent 无需改动 Java 代码。
 */
@Slf4j
@Component
public class AgentFactory {

    private final Model singleAgentModel;
    private final Model multiAgentModel;
    private final ToolRegistryAdapter toolRegistryAdapter;
    private final int defaultMaxSteps;

    public AgentFactory(
            @Qualifier("agentScopeChatModel") Model singleAgentModel,
            @Qualifier("agentScopeMultiAgentModel") Model multiAgentModel,
            ToolRegistryAdapter toolRegistryAdapter,
            LLMConfig llmConfig) {
        this.singleAgentModel = singleAgentModel;
        this.multiAgentModel = multiAgentModel;
        this.toolRegistryAdapter = toolRegistryAdapter;
        this.defaultMaxSteps = llmConfig.getMaxSteps();
        log.info("[AgentFactory] 初始化完成: defaultMaxSteps={}", defaultMaxSteps);
    }

    /**
     * 根据 AgentDefinition 配置构建 ReActAgent
     * <p>
     * 根据 definition 中的 tools 列表决定是否挂载 Toolkit（空列表则不挂载），
     * 根据 useMultiAgentModel 选择单 Agent 模型或多 Agent 协作模型。
     */
    public ReActAgent buildFromDefinition(AgentDefinition definition) {
        Model model = definition.isUseMultiAgentModel() ? multiAgentModel : singleAgentModel;
        int maxSteps = definition.getMaxSteps() > 0 ? definition.getMaxSteps() : defaultMaxSteps;

        List<String> toolNames = definition.getTools();
        boolean hasTools = toolNames != null && !toolNames.isEmpty();

        var builder = ReActAgent.builder()
                .name(definition.getName())
                .sysPrompt(definition.getSystemPrompt())
                .model(model)
                .maxIters(maxSteps);

        if (hasTools) {
            builder.toolkit(createToolkit());
        }

        log.debug("[AgentFactory] 构建 Agent: name={}, tools={}, model={}, maxSteps={}",
                definition.getName(),
                hasTools ? toolNames : "none",
                definition.isUseMultiAgentModel() ? "multi-agent" : "single-agent",
                maxSteps);

        return builder.build();
    }

    /**
     * 创建 Toolkit 并注册 ToolRegistryAdapter（AgentScope 工具桥接的唯一入口）
     */
    private Toolkit createToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(toolRegistryAdapter);
        return toolkit;
    }
}

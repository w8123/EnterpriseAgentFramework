package com.jishi.ai.agent.agentscope;

import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope 框架配置
 * <p>
 * 复用 Spring AI DashScope 的 API Key 配置，为 AgentScope Agent 创建独立的模型实例。
 * 架构分层：AgentScope 负责 Agent 编排，Spring AI 负责简单对话路径的 LLM 调用。
 * <p>
 * 提供两种模型 Bean：
 * - agentScopeChatModel：单 Agent 场景
 * - agentScopeMultiAgentModel：Pipeline / MsgHub 多 Agent 协作场景
 */
@Slf4j
@Configuration
public class AgentScopeConfig {

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    @Value("${agentscope.model.name:qwen-max}")
    private String modelName;

    @Value("${agentscope.model.enable-thinking:false}")
    private boolean enableThinking;

    /**
     * 单 Agent 场景模型（默认 Formatter）
     */
    @Bean
    public DashScopeChatModel agentScopeChatModel() {
        log.info("[AgentScope] 初始化模型: model={}, thinking={}", modelName, enableThinking);
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .enableThinking(enableThinking)
                .build();
    }

    /**
     * 多 Agent 协作场景模型（MultiAgentFormatter）
     * <p>
     * 用于 SequentialPipeline / FanoutPipeline / MsgHub，
     * 会在历史消息中用 XML 标签区分不同 Agent 的发言。
     */
    @Bean
    public DashScopeChatModel agentScopeMultiAgentModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .enableThinking(enableThinking)
                .formatter(new DashScopeMultiAgentFormatter())
                .build();
    }
}

package com.enterprise.ai.agent.agentscope;

import io.agentscope.core.formatter.openai.OpenAIMultiAgentFormatter;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope 框架配置
 * <p>
 * 所有 LLM 调用统一通过 ai-model-service 的 OpenAI 兼容代理端点。
 * agent-service 不再直接持有 DashScope API Key。
 * <p>
 * 提供两种模型 Bean：
 * - agentScopeChatModel：单 Agent 场景
 * - agentScopeMultiAgentModel：Pipeline / MsgHub 多 Agent 协作场景
 */
@Slf4j
@Configuration
public class AgentScopeConfig {

    @Value("${services.model-service.url:http://localhost:8090}")
    private String modelServiceUrl;

    @Value("${agentscope.model.name:qwen-max}")
    private String modelName;

    /**
     * 单 Agent 场景模型（默认 Formatter）
     * <p>
     * 通过 OpenAIChatModel 指向 model-service 的 OpenAI 兼容代理，
     * model-service 再转发到 DashScope，实现 API Key 集中管理。
     */
    @Bean
    public Model agentScopeChatModel() {
        String baseUrl = modelServiceUrl + "/model/openai-proxy/v1";
        log.info("[AgentScope] 初始化模型: baseUrl={}, model={}", baseUrl, modelName);
        return OpenAIChatModel.builder()
                .apiKey("proxy-via-model-service")
                .baseUrl(baseUrl)
                .modelName(modelName)
                .build();
    }

    /**
     * 多 Agent 协作场景模型（MultiAgentFormatter）
     * <p>
     * 用于 SequentialPipeline / FanoutPipeline / MsgHub，
     * 会在历史消息中用 XML 标签区分不同 Agent 的发言。
     */
    @Bean
    public Model agentScopeMultiAgentModel() {
        String baseUrl = modelServiceUrl + "/model/openai-proxy/v1";
        return OpenAIChatModel.builder()
                .apiKey("proxy-via-model-service")
                .baseUrl(baseUrl)
                .modelName(modelName)
                .formatter(new OpenAIMultiAgentFormatter())
                .build();
    }
}

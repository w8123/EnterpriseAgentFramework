package com.jishi.ai.agent.config;

/**
 * 已废弃：Spring AI ChatClient 配置已移除。
 * <p>
 * LLM 调用统一通过 ai-model-service（Feign），不再直接依赖 Spring AI。
 * - 非 Agent 路径：LlmService → ModelServiceClient → ai-model-service /model/chat
 * - Agent 路径：AgentScope OpenAIChatModel → ai-model-service /model/openai-proxy/v1/chat/completions
 */
// @Configuration — 已移除，保留此文件作为架构迁移记录
public class SpringAIConfig {
    // 此类不再提供任何 Bean，可在后续清理中删除
}

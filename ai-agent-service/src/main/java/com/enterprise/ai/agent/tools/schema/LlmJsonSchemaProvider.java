package com.enterprise.ai.agent.tools.schema;

import java.util.Map;

/**
 * 为 LLM（OpenAI 风格 tool/function）提供完整 JSON Schema 的 {@code parameters} 根对象。
 * <p>
 * 默认由 {@link com.enterprise.ai.agent.agentscope.adapter.AiToolAgentAdapter} 根据
 * {@link com.enterprise.ai.skill.ToolParameter} 扁平生成；动态 HTTP 工具可实现本接口以展开嵌套字段。
 */
public interface LlmJsonSchemaProvider {

    /**
     * @return 符合 OpenAI tools 约定的根 schema，通常含 {@code type=object}、{@code properties}、{@code required}
     */
    Map<String, Object> llmParametersJsonSchema();
}

package com.enterprise.ai.skill;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 框架无关的 AI 工具接口
 * <p>
 * 所有业务工具统一实现此接口，不依赖任何 AI 框架（Spring AI / AgentScope / LangChain 等）。
 * 框架通过 Adapter 桥接本接口，实现工具层与编排层的彻底解耦。
 * <p>
 * 更换 Agent 框架时，只需替换 Adapter，Tool 实现零修改。
 */
public interface AiTool {

    /** 工具唯一标识，用于 ToolRegistry 查找和 Agent 调用 */
    String name();

    /** 工具功能描述，Agent/LLM 据此决策是否调用 */
    String description();

    /**
     * 执行工具逻辑
     *
     * @param args 调用参数，Key 为参数名，Value 为参数值
     * @return 执行结果（通常为 String 或可序列化对象）
     */
    Object execute(Map<String, Object> args);

    /**
     * 工具参数定义，用于生成 JSON Schema 供 LLM 理解参数结构。
     * 默认返回空列表（无参数或参数从 description 中推断）。
     */
    default List<ToolParameter> parameters() {
        return Collections.emptyList();
    }
}

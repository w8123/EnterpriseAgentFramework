package com.enterprise.ai.agent.agentscope.adapter;

import com.enterprise.ai.agent.tools.ToolRegistry;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AgentScope 工具桥接适配器
 * <p>
 * 这是项目中 AgentScope {@code @Tool} 注解存在的<b>唯一位置</b>。
 * 每个方法将 AgentScope 的工具调用委托给框架无关的 ToolRegistry。
 * <p>
 * 架构关系：
 * <pre>
 * AgentScope ReActAgent
 *      ↓
 * ToolRegistryAdapter (本类，AgentScope @Tool 注解)
 *      ↓
 * ToolRegistry
 *      ↓
 * AiTool 实现（KnowledgeSearchTool / DynamicHttpAiTool 等，零框架依赖）
 * </pre>
 * <p>
 * 更换 Agent 框架时，只需替换本类（或编写新 Adapter），Tool 实现零修改。
 * <p>
 * <b>新增 AgentScope 可见工具时</b>：1) 创建 AiTool 实现类  2) 在本类添加对应的桥接方法
 */
@Slf4j
@Deprecated
@Component
@RequiredArgsConstructor
public class ToolRegistryAdapter {

    private final ToolRegistry toolRegistry;

    @Tool(name = "search_knowledge",
          description = "搜索企业知识库。当用户询问公司制度、技术规范、操作流程等知识类问题时使用。")
    public String searchKnowledge(
            @ToolParam(name = "query", description = "搜索关键词或问题描述") String query) {
        return str(toolRegistry.execute("search_knowledge", java.util.Map.of("query", query)));
    }

    private static String str(Object result) {
        return result == null ? "" : String.valueOf(result);
    }
}

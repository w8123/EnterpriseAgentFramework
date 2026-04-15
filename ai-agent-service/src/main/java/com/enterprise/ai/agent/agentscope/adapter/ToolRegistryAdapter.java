package com.enterprise.ai.agent.agentscope.adapter;

import com.enterprise.ai.agent.tools.ToolRegistry;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

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
 * AiTool 实现（DatabaseQueryTool 等，零框架依赖）
 *      ↓
 * 业务系统 Client
 * </pre>
 * <p>
 * 更换 Agent 框架时，只需替换本类（或编写新 Adapter），Tool 实现零修改。
 * <p>
 * <b>新增工具时</b>：1) 创建 AiTool 实现类  2) 在本类添加对应的桥接方法
 */
@Slf4j
@Deprecated
@Component
@RequiredArgsConstructor
public class ToolRegistryAdapter {

    private final ToolRegistry toolRegistry;

    @Tool(name = "query_database",
          description = "执行数据库查询。当用户需要查询业务数据（班组得分、人员统计、月度报表等）时使用。输入为 SQL SELECT 语句，返回 JSON 格式查询结果。")
    public String queryDatabase(
            @ToolParam(name = "sql", description = "SQL SELECT 查询语句") String sql) {
        return str(toolRegistry.execute("query_database", Map.of("sql", sql)));
    }

    @Tool(name = "call_business_api",
          description = "调用业务系统 REST API。当需要获取或操作业务数据（人员信息、项目状态、审批记录等）时使用。")
    public String callBusinessApi(
            @ToolParam(name = "api_path", description = "业务 API 路径，如 /api/employee/query") String apiPath,
            @ToolParam(name = "params_json", description = "JSON 格式的查询参数") String paramsJson) {
        return str(toolRegistry.execute("call_business_api",
                Map.of("api_path", apiPath, "params_json", paramsJson)));
    }

    @Tool(name = "search_knowledge",
          description = "搜索企业知识库。当用户询问公司制度、技术规范、操作流程等知识类问题时使用。")
    public String searchKnowledge(
            @ToolParam(name = "query", description = "搜索关键词或问题描述") String query) {
        return str(toolRegistry.execute("search_knowledge", Map.of("query", query)));
    }

    @Tool(name = "query_user_profile",
          description = "查询当前登录用户的身份信息，包括姓名、年龄、性别、身高等基本资料。")
    public String queryUserProfile(
            @ToolParam(name = "user_id", description = "用户ID或工号，为空时查询当前登录人") String userId) {
        return str(toolRegistry.execute("query_user_profile", Map.of("user_id", userId)));
    }

    private static String str(Object result) {
        return result == null ? "" : String.valueOf(result);
    }
}

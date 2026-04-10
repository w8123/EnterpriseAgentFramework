package com.enterprise.ai.agent.agent;

import com.enterprise.ai.agent.llm.LlmService;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.rag.RagService;
import com.enterprise.ai.agent.tools.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Agent 工作流定义（降级路径）
 * <p>
 * 当 AgentScope 路径异常时，AgentOrchestrator 自动回退到本类执行。
 * 通过 LlmService 调用 LLM，通过 ToolRegistry 调用工具，不直接依赖任何 AI 框架 API。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWorkflow {

    private final LlmService llmService;
    private final ToolRegistry toolRegistry;
    private final RagService ragService;

    /**
     * 执行数据查询工作流（NL2SQL）
     */
    public AgentResult executeQueryDataFlow(AgentContext context) {
        AgentResult result = AgentResult.builder().build();

        result.addStep("NL2SQL", "将自然语言转换为SQL查询");
        String sqlResponse = llmService.chat(
                "你是一个SQL专家。请将用户的自然语言问题转换为SQL查询语句。只返回SQL，不要返回其他内容。",
                context.getUserMessage());
        context.advanceStep("SQL生成: " + sqlResponse);

        result.addStep("执行查询", "通过业务层执行SQL查询");
        Object queryResult = toolRegistry.execute("query_database", Map.of("sql", sqlResponse));
        context.advanceStep("查询结果获取完成");
        context.recordToolCall("query_database");
        result.getToolResults().put("queryResult", queryResult);

        result.addStep("结果总结", "LLM将查询结果转化为自然语言回答");
        String summary = llmService.chatWithContext(
                "请用通俗易懂的语言总结查询结果，回答用户的问题。",
                String.valueOf(queryResult),
                context.getUserMessage());
        context.advanceStep("回答生成完成");

        result.setSuccess(true);
        result.setAnswer(summary);
        return result;
    }

    /**
     * 执行知识问答工作流（RAG）
     */
    public AgentResult executeKnowledgeQAFlow(AgentContext context) {
        AgentResult result = AgentResult.builder().build();

        result.addStep("知识检索", "从企业知识库检索相关信息");
        String answer = ragService.answerWithKnowledge(context.getUserMessage(), context.getUserId());
        context.advanceStep("RAG回答生成完成");
        context.recordToolCall("search_knowledge");

        result.setSuccess(true);
        result.setAnswer(answer);
        return result;
    }

    /**
     * 执行业务操作工作流
     */
    public AgentResult executeBusinessOperationFlow(AgentContext context) {
        AgentResult result = AgentResult.builder().build();

        result.addStep("意图解析", "确定需要调用的业务API");
        String apiPlan = llmService.chat(
                "分析用户请求，确定需要调用的业务API路径和参数。以JSON格式返回：{\"apiPath\":\"/api/xxx\",\"params\":{...}}",
                context.getUserMessage());
        context.advanceStep("业务操作完成");
        context.recordToolCall("call_business_api");

        result.setSuccess(true);
        result.setAnswer(apiPlan);
        return result;
    }

    /**
     * 执行创意任务工作流（降级路径：信息采集 + 创作合并为单次 LLM 调用）
     */
    public AgentResult executeCreativeTaskFlow(AgentContext context) {
        AgentResult result = AgentResult.builder().build();

        result.addStep("信息采集", "查询用户身份信息");
        Object profile = toolRegistry.execute("query_user_profile", java.util.Map.of("user_id", context.getUserId()));
        context.advanceStep("用户信息获取完成");
        context.recordToolCall("query_user_profile");
        result.getToolResults().put("userProfile", profile);

        result.addStep("创意生成", "基于采集信息完成创意任务");
        String answer = llmService.chatWithContext(
                "你是创意助手小铁宝。基于以下用户信息完成用户的创意请求，给出多个选项并说明含义。",
                String.valueOf(profile),
                context.getUserMessage());
        context.advanceStep("创意生成完成");

        result.setSuccess(true);
        result.setAnswer(answer);
        return result;
    }

    /**
     * 执行通用对话工作流
     */
    public AgentResult executeGeneralChatFlow(AgentContext context) {
        AgentResult result = AgentResult.builder().build();

        result.addStep("通用对话", "直接LLM回答");
        String answer = llmService.chat(context.getUserMessage());
        context.advanceStep("对话完成");

        result.setSuccess(true);
        result.setAnswer(answer);
        return result;
    }
}

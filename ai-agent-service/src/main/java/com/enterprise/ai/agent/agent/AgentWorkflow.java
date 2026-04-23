package com.enterprise.ai.agent.agent;

import com.enterprise.ai.agent.llm.LlmService;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.rag.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
    private final RagService ragService;

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

package com.enterprise.ai.agent.agentscope;

import com.enterprise.ai.agent.agentscope.adapter.ToolRegistryAdapter;
import com.enterprise.ai.agent.config.LLMConfig;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Agent 工厂 — 按场景创建 AgentScope ReActAgent 实例
 * <p>
 * AgentScope 的 ReActAgent 和 Toolkit 均为有状态对象，不可跨请求共享。
 * 每次请求需通过本工厂创建独立实例。
 * <p>
 * 工具桥接关系：
 * <pre>
 * AgentFactory → Toolkit.registerTool(ToolRegistryAdapter)
 *                           ↓
 *                    ToolRegistry → AiTool 实现
 * </pre>
 */
@Slf4j
@Component
public class AgentFactory {

    private final Model singleAgentModel;
    private final Model multiAgentModel;
    private final ToolRegistryAdapter toolRegistryAdapter;
    private final int maxSteps;

    public AgentFactory(
            @Qualifier("agentScopeChatModel") Model singleAgentModel,
            @Qualifier("agentScopeMultiAgentModel") Model multiAgentModel,
            ToolRegistryAdapter toolRegistryAdapter,
            LLMConfig llmConfig) {
        this.singleAgentModel = singleAgentModel;
        this.multiAgentModel = multiAgentModel;
        this.toolRegistryAdapter = toolRegistryAdapter;
        this.maxSteps = llmConfig.getMaxSteps();
        log.info("[AgentFactory] 初始化完成: maxSteps={}", maxSteps);
    }

    /**
     * 数据查询 Agent（NL2SQL）
     */
    public ReActAgent createQueryDataAgent() {
        return ReActAgent.builder()
                .name("QueryDataAgent")
                .sysPrompt("""
                        你是青岛地铁的数据查询专家，名叫小铁宝。
                        你的核心职责是帮助用户查询业务数据。

                        工作流程：
                        1. 分析用户的自然语言问题，理解查询意图
                        2. 使用 query_database 工具生成并执行 SQL SELECT 查询
                        3. 将查询结果用通俗易懂的语言总结给用户

                        约束：
                        - 只生成 SELECT 查询，严禁 INSERT/UPDATE/DELETE
                        - 如果查询结果为空，告知用户并建议调整查询条件
                        - 数据涉及敏感信息时需提醒用户注意保密""")
                .model(singleAgentModel)
                .toolkit(createToolkit())
                .maxIters(maxSteps)
                .build();
    }

    /**
     * 知识问答 Agent（RAG）
     */
    public ReActAgent createKnowledgeQAAgent() {
        return ReActAgent.builder()
                .name("KnowledgeQAAgent")
                .sysPrompt("""
                        你是青岛地铁的知识问答专家，名叫小铁宝。
                        你的核心职责是回答企业制度、技术规范、操作流程等知识类问题。

                        工作流程：
                        1. 使用 search_knowledge 工具检索企业知识库
                        2. 基于检索结果生成准确、完整的回答
                        3. 如果知识库没有相关信息，诚实告知用户

                        约束：
                        - 回答必须基于知识库内容，不要编造信息
                        - 如引用制度条款，需标注出处
                        - 对不确定的内容明确标注""")
                .model(singleAgentModel)
                .toolkit(createToolkit())
                .maxIters(maxSteps)
                .build();
    }

    /**
     * 数据分析 Agent（多步推理）
     */
    public ReActAgent createAnalysisAgent() {
        return ReActAgent.builder()
                .name("AnalysisAgent")
                .sysPrompt("""
                        你是青岛地铁的数据分析专家，名叫小铁宝。
                        你的核心职责是对业务数据进行深度分析，提供有价值的洞察和建议。

                        工作流程：
                        1. 理解用户的分析需求，拆解为具体的数据查询步骤
                        2. 使用 query_database 工具查询所需数据
                        3. 如需要，使用 search_knowledge 获取相关制度或背景信息
                        4. 综合分析数据，给出有洞察力的结论和改进建议

                        约束：
                        - 可以进行多步查询，逐步深入分析
                        - 结论必须有数据支撑，标注数据来源
                        - 建议应具有可操作性""")
                .model(singleAgentModel)
                .toolkit(createToolkit())
                .maxIters(maxSteps + 3)
                .build();
    }

    /**
     * 业务操作 Agent
     */
    public ReActAgent createBusinessOperationAgent() {
        return ReActAgent.builder()
                .name("BusinessOperationAgent")
                .sysPrompt("""
                        你是青岛地铁的业务操作助手，名叫小铁宝。
                        你的核心职责是帮助用户执行业务操作。

                        工作流程：
                        1. 理解用户的业务操作需求
                        2. 确定需要调用的业务 API 路径和参数
                        3. 使用 call_business_api 工具执行操作
                        4. 将操作结果清晰反馈给用户

                        约束：
                        - 操作前需确认关键参数
                        - 操作失败时给出明确的错误说明和建议""")
                .model(singleAgentModel)
                .toolkit(createToolkit())
                .maxIters(maxSteps)
                .build();
    }

    /**
     * 通用对话 Agent（无工具）
     */
    public ReActAgent createGeneralChatAgent() {
        return ReActAgent.builder()
                .name("GeneralChatAgent")
                .sysPrompt("你是青岛地铁的智能助手，名叫小铁宝。请用专业且友好的语气与用户对话，帮助解答一般性问题。")
                .model(singleAgentModel)
                .maxIters(3)
                .build();
    }

    /** Pipeline 用：信息采集 Agent — CREATIVE_TASK Pipeline 第一步 */
    public ReActAgent createMultiAgentInfoGatherAgent() {
        return ReActAgent.builder()
                .name("InfoGatherAgent")
                .sysPrompt("""
                        你是信息采集专家。你的唯一任务是：理解用户需求，判断需要哪些背景信息，然后使用工具采集。

                        工作流程：
                        1. 分析用户的请求，判断完成该任务需要哪些基础信息
                           - 如果涉及"某人"的信息（如姓名、身份），使用 query_user_profile 查询
                           - 如果涉及业务数据，使用 query_database 查询
                           - 如果涉及制度/流程知识，使用 search_knowledge 检索
                        2. 调用合适的工具采集信息
                        3. 将采集到的所有信息整理成清晰的摘要，传递给下一个 Agent

                        约束：
                        - 只负责信息采集和整理，绝不自己完成最终任务
                        - 用结构化的格式整理信息（如：姓名=xxx, 姓氏=xxx）
                        - 如果采集不到信息，明确说明缺少哪些信息""")
                .model(multiAgentModel)
                .toolkit(createToolkit())
                .maxIters(maxSteps)
                .build();
    }

    /** Pipeline 用：创意 Agent — CREATIVE_TASK Pipeline 第二步 */
    public ReActAgent createMultiAgentCreativeAgent() {
        return ReActAgent.builder()
                .name("CreativeAgent")
                .sysPrompt("""
                        你是青岛地铁的创意助手，名叫小铁宝。
                        你的任务是基于上游 InfoGatherAgent 采集到的信息，完成用户的创意请求。

                        你擅长：
                        - 起名字：根据姓氏、寓意、文化背景给出多个候选名
                        - 写文案：公告、邮件、通知、总结
                        - 生成方案：工作计划、活动策划
                        - 其他创意输出

                        约束：
                        - 必须基于上游 Agent 提供的信息进行创作（如姓氏、人员信息等）
                        - 如果上游信息不足，先说明缺少什么，再尽力完成
                        - 给出多个选项供用户选择，并说明每个选项的含义/寓意""")
                .model(multiAgentModel)
                .maxIters(maxSteps)
                .build();
    }

    /** Pipeline 用：数据查询 Agent（MultiAgentFormatter） */
    public ReActAgent createMultiAgentQueryDataAgent() {
        return ReActAgent.builder()
                .name("QueryDataAgent")
                .sysPrompt("""
                        你是数据查询专家。负责将自然语言问题转换为 SQL 查询并执行。
                        只关注数据获取，不做分析。将查询到的原始数据传递给下一个 Agent。""")
                .model(multiAgentModel)
                .toolkit(createToolkit())
                .maxIters(maxSteps)
                .build();
    }

    /** Pipeline 用：数据分析 Agent（MultiAgentFormatter） */
    public ReActAgent createMultiAgentAnalysisAgent() {
        return ReActAgent.builder()
                .name("AnalysisAgent")
                .sysPrompt("""
                        你是数据分析专家。基于上游 Agent 提供的数据进行深度分析。
                        给出有洞察力的结论、趋势判断和改进建议。结论必须有数据支撑。""")
                .model(multiAgentModel)
                .toolkit(createToolkit())
                .maxIters(maxSteps)
                .build();
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

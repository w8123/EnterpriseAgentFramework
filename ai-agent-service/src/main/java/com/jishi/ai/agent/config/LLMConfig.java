package com.jishi.ai.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 相关外部化配置，绑定 application.yml 中 agent.* 前缀
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent")
public class LLMConfig {

    /** Agent最大推理步数，防止无限循环 */
    private int maxSteps = 5;

    /** 单次Agent执行超时(ms) */
    private long defaultTimeout = 60000;

    private IntentConfig intent = new IntentConfig();

    /** 各 Agent 类型的启用开关 */
    private AgentsToggle agents = new AgentsToggle();

    @Data
    public static class IntentConfig {
        private String systemPrompt;
    }

    /**
     * Agent 类型启用/禁用开关
     * <p>
     * 关闭某个 Agent 后：
     * 1. 意图识别 prompt 中不再出现该类别（LLM 不会识别出该意图）
     * 2. 即使前端通过 intentHint 强制指定，路由器也会降级到 GENERAL_CHAT
     * <p>
     * GENERAL_CHAT 作为兜底始终可用，其开关仅控制是否在意图识别 prompt 中显式列出。
     */
    @Data
    public static class AgentsToggle {
        /** 数据查询 Agent（NL2SQL） */
        private boolean queryData = true;
        /** 知识问答 Agent（RAG） */
        private boolean knowledgeQa = true;
        /** 业务操作 Agent */
        private boolean businessOperation = true;
        /** 数据分析 Agent（多步推理 Pipeline） */
        private boolean analysis = true;
        /** 创意任务 Agent（信息采集 → 创作 Pipeline） */
        private boolean creativeTask = true;
        /** 通用对话 Agent（兜底，开关仅控制是否出现在 prompt 中） */
        private boolean generalChat = true;

        /**
         * 判断指定意图类型对应的 Agent 是否启用
         */
        public boolean isEnabled(String intentType) {
            return switch (intentType) {
                case "QUERY_DATA" -> queryData;
                case "KNOWLEDGE_QA" -> knowledgeQa;
                case "BUSINESS_OPERATION" -> businessOperation;
                case "ANALYSIS" -> analysis;
                case "CREATIVE_TASK" -> creativeTask;
                case "GENERAL_CHAT" -> true;
                default -> true;
            };
        }
    }
}

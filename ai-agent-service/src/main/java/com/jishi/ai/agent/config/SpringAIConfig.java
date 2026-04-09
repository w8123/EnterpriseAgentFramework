package com.jishi.ai.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 核心 Bean 配置
 * <p>
 * ChatClient 基于 DashScope ChatModel 构建，是所有 LLM 交互的统一入口
 */
@Configuration
public class SpringAIConfig {

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是青岛地铁的企业级智能助手，名叫小铁宝。你能够帮助用户查询数据、搜索知识库、执行业务操作。请用专业且友好的语气回答问题。")
                .build();
    }
}

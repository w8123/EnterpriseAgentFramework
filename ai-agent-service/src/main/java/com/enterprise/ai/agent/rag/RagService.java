package com.enterprise.ai.agent.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * RAG 服务层
 * <p>
 * 通过 ai-text-service 的 RAG 引擎完成知识检索 + 增强生成全流程：
 * Embedding → Milvus 向量检索 → Prompt 组装 → LLM 生成回答。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final RagClient ragClient;

    /**
     * 基于 RAG 回答用户的知识类问题
     *
     * @param userQuery 用户原始问题
     * @param userId    用户标识（用于权限过滤）
     * @return 基于知识库生成的回答
     */
    public String answerWithKnowledge(String userQuery, String userId) {
        log.info("[RagService] RAG问答开始: query={}, userId={}", userQuery, userId);
        String answer = ragClient.retrieve(userQuery, userId);
        log.info("[RagService] RAG问答完成");
        return answer;
    }
}

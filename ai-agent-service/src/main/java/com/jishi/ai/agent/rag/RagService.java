package com.jishi.ai.agent.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * RAG服务层
 * <p>
 * 极视角智能体已内置RAG能力（知识检索+增强生成），
 * 此服务直接委托给极视角智能体完成全流程，无需本地拼装Prompt。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final RagClient ragClient;

    /**
     * 基于RAG回答用户的知识类问题
     * <p>
     * 底层调用极视角平台的知识问答智能体，由平台完成检索-增强-生成全流程。
     *
     * @param userQuery 用户原始问题
     * @param user      用户标识
     * @return 智能体基于知识库生成的回答
     */
    public String answerWithKnowledge(String userQuery, String user) {
        log.info("RAG问答开始: query={}, user={}", userQuery, user);
        String answer = ragClient.retrieve(userQuery, user);
        log.info("RAG问答完成");
        return answer;
    }
}

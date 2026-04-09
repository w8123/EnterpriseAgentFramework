package com.jishi.ai.agent.rag;

import com.jishi.ai.agent.client.JishiAgentClient;
import com.jishi.ai.agent.model.jishi.JishiChatResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RAG底层客户端
 * <p>
 * 通过极视角平台的对话智能体实现知识检索。
 * 使用的智能体由 rag.agent-key 配置指定，可随时切换到专用的知识问答智能体。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagClient {

    private final JishiAgentClient jishiAgentClient;

    /** 用于RAG检索的极视角智能体标识，对应 yml 中 jishi.platform.agents 的 Key */
    @Value("${rag.agent-key:default-chat}")
    private String ragAgentKey;

    /**
     * 通过极视角智能体检索知识
     *
     * @param query 用户查询
     * @param user  用户标识
     * @return 智能体返回的回答
     */
    public String retrieve(String query, String user) {
        log.debug("RAG检索: agentKey={}, query={}", ragAgentKey, query);
        JishiChatResult result = jishiAgentClient.chat(ragAgentKey, query, user, null);
        return result.getAnswer();
    }
}

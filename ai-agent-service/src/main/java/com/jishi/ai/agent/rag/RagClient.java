package com.jishi.ai.agent.rag;

import com.jishi.ai.agent.client.TextServiceClient;
import com.jishi.ai.agent.client.TextServiceClient.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RAG 底层客户端
 * <p>
 * 通过 ai-text-service 的 RAG 引擎进行知识检索与问答。
 * ai-text-service 内部完成：Embedding → Milvus 检索 → Prompt 组装 → LLM 生成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagClient {

    private final TextServiceClient textServiceClient;

    @Value("${rag.top-k:5}")
    private int topK;

    /**
     * 通过 ai-text-service RAG 引擎检索知识并生成回答
     *
     * @param query 用户查询
     * @param userId 用户标识（用于权限过滤）
     * @return RAG 生成的回答
     */
    public String retrieve(String query, String userId) {
        log.debug("[RagClient] RAG检索: query={}, userId={}", query, userId);

        RagQueryRequest request = RagQueryRequest.builder()
                .question(query)
                .userId(userId)
                .topK(topK)
                .build();

        try {
            RagResult result = textServiceClient.ragQuery(request);
            if (result.getData() != null && result.getData().getAnswer() != null) {
                log.debug("[RagClient] RAG检索成功, answerLength={}",
                        result.getData().getAnswer().length());
                return result.getData().getAnswer();
            }
            log.warn("[RagClient] ai-text-service 返回空结果: code={}, msg={}",
                    result.getCode(), result.getMessage());
            return "未找到相关知识库内容";
        } catch (Exception e) {
            log.error("[RagClient] ai-text-service 调用失败", e);
            return "知识检索服务暂不可用: " + e.getMessage();
        }
    }
}

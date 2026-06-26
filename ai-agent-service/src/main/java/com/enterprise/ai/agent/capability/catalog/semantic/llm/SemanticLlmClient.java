package com.enterprise.ai.agent.capability.catalog.semantic.llm;

import com.enterprise.ai.agent.client.ModelServiceClient;
import com.enterprise.ai.agent.client.ModelServiceClient.ModelChatData;
import com.enterprise.ai.agent.client.ModelServiceClient.ModelChatRequest;
import com.enterprise.ai.agent.client.ModelServiceClient.ModelChatResult;
import com.enterprise.ai.agent.client.ModelServiceClient.ModelUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 封装 ai-model-service 调用，返回语义生成需要的 (content, model, tokenUsage) 三元组。
 */
@Slf4j
@Component
public class SemanticLlmClient {

    private static final String SYSTEM_PROMPT = "你是一名企业 AI Agent 架构文档助手，严格按照用户指定的 Markdown 结构输出。";

    private static final String SENSITIVE_SYSTEM_PROMPT = """
            你是一名企业 API 数据安全分析助手。你必须只输出一个合法的 JSON 对象，键仅包含 types 与 summary。
            不要输出 Markdown、不要代码围栏、不要 JSON 以外的任何字符。""";

    private final ModelServiceClient modelServiceClient;

    public SemanticLlmClient(ModelServiceClient modelServiceClient) {
        this.modelServiceClient = modelServiceClient;
    }

    /**
     */
    public SemanticGenerationResult generate(String userPrompt, String modelInstanceId) {
        String modelToUse = resolveModelInstanceId(modelInstanceId);
        ModelChatRequest.ModelChatRequestBuilder b = ModelChatRequest.builder()
                .modelInstanceId(modelToUse)
                .messages(List.of(
                        ModelChatRequest.ChatMessage.builder().role("system").content(SYSTEM_PROMPT).build(),
                        ModelChatRequest.ChatMessage.builder().role("user").content(userPrompt).build()
                ));
        ModelChatRequest request = b.build();

        ModelChatResult result;
        try {
            result = modelServiceClient.chat(request);
        } catch (Exception ex) {
            log.error("[SemanticLlmClient] 调用 model-service 失败", ex);
            throw new IllegalStateException("LLM 调用失败: " + ex.getMessage(), ex);
        }
        if (result == null || result.getData() == null || result.getData().getContent() == null) {
            throw new IllegalStateException("LLM 返回内容为空: code=" + (result == null ? "null" : result.getCode()));
        }
        ModelChatData data = result.getData();
        ModelUsage usage = data.getUsage();
        int total = usage == null ? 0 : usage.getTotalTokens();
        String modelName = data.getModel() == null ? modelToUse : data.getModel();
        return new SemanticGenerationResult(data.getContent().trim(), modelName, total);
    }

    /**
     * 敏感数据扫描：要求模型只输出 JSON（与 AI 理解使用相同的模型实例语义）。
     */
    public SemanticGenerationResult generateSensitiveScan(String userPrompt, String modelInstanceId) {
        String modelToUse = resolveModelInstanceId(modelInstanceId);
        ModelChatRequest.ModelChatRequestBuilder b = ModelChatRequest.builder()
                .modelInstanceId(modelToUse)
                .messages(List.of(
                        ModelChatRequest.ChatMessage.builder().role("system").content(SENSITIVE_SYSTEM_PROMPT).build(),
                        ModelChatRequest.ChatMessage.builder().role("user").content(userPrompt).build()
                ));
        ModelChatRequest request = b.build();
        ModelChatResult result;
        try {
            result = modelServiceClient.chat(request);
        } catch (Exception ex) {
            log.error("[SemanticLlmClient] 敏感扫描调用 model-service 失败", ex);
            throw new IllegalStateException("LLM 调用失败: " + ex.getMessage(), ex);
        }
        if (result == null || result.getData() == null || result.getData().getContent() == null) {
            throw new IllegalStateException("LLM 返回内容为空: code=" + (result == null ? "null" : result.getCode()));
        }
        ModelChatData data = result.getData();
        ModelUsage usage = data.getUsage();
        int total = usage == null ? 0 : usage.getTotalTokens();
        String modelName = data.getModel() == null ? modelToUse : data.getModel();
        return new SemanticGenerationResult(data.getContent().trim(), modelName, total);
    }

    public record SemanticGenerationResult(String content, String modelName, int tokenUsage) {
    }

    private String resolveModelInstanceId(String modelInstanceId) {
        if (modelInstanceId == null || modelInstanceId.isBlank()) {
            throw new IllegalStateException("modelInstanceId is required for semantic LLM generation");
        }
        return modelInstanceId.trim();
    }
}

package com.enterprise.ai.agent.semantic.llm;

import com.enterprise.ai.agent.client.ModelServiceClient;
import com.enterprise.ai.agent.client.ModelServiceClient.ModelChatData;
import com.enterprise.ai.agent.client.ModelServiceClient.ModelChatRequest;
import com.enterprise.ai.agent.client.ModelServiceClient.ModelChatResult;
import com.enterprise.ai.agent.client.ModelServiceClient.ModelUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 封装 ai-model-service 调用，返回语义生成需要的 (content, model, tokenUsage) 三元组。
 */
@Slf4j
@Component
public class SemanticLlmClient {

    private static final String SYSTEM_PROMPT = "你是一名企业 AI Agent 架构文档助手，严格按照用户指定的 Markdown 结构输出。";

    private final ModelServiceClient modelServiceClient;

    @Value("${agentscope.model.name:qwen-max}")
    private String defaultModel;

    public SemanticLlmClient(ModelServiceClient modelServiceClient) {
        this.modelServiceClient = modelServiceClient;
    }

    /**
     * @param provider 非空时传给模型网关；为空则走网关默认 Provider
     * @param model    非空时使用该模型；为空则使用 {@code agentscope.model.name}
     */
    public SemanticGenerationResult generate(String userPrompt, String provider, String model) {
        String modelToUse = (model == null || model.isBlank()) ? defaultModel : model.trim();
        ModelChatRequest.ModelChatRequestBuilder b = ModelChatRequest.builder()
                .model(modelToUse)
                .messages(List.of(
                        ModelChatRequest.ChatMessage.builder().role("system").content(SYSTEM_PROMPT).build(),
                        ModelChatRequest.ChatMessage.builder().role("user").content(userPrompt).build()
                ));
        if (provider != null && !provider.isBlank()) {
            b.provider(provider.trim());
        }
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

    public record SemanticGenerationResult(String content, String modelName, int tokenUsage) {
    }
}

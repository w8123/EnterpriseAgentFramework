package com.enterprise.ai.runtime.chat;

import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest.ChatMessage;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultRuntimeLightweightChatService implements RuntimeLightweightChatService {

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是企业级智能助手。请用专业且友好的语气回答用户问题。";

    private final RuntimeModelServiceClient modelServiceClient;
    private final RuntimeChatMemoryStore memoryStore;

    @Override
    public RuntimeChatResponse chat(RuntimeChatRequest request) {
        if (request == null || request.getMessage() == null || request.getMessage().isBlank()) {
            return RuntimeChatResponse.error("消息内容不能为空");
        }
        String sessionId = resolveSessionId(request);
        if (request.getModelInstanceId() == null || request.getModelInstanceId().isBlank()) {
            return RuntimeChatResponse.error("modelInstanceId is required");
        }
        try {
            ModelChatResult result = modelServiceClient.chat(buildModelRequest(request, sessionId));
            ModelChatData data = result == null ? null : result.getData();
            String answer = data == null ? null : data.getContent();
            if (answer == null || answer.isBlank()) {
                log.warn("[RuntimeChat] model-service returned empty content: code={}, message={}",
                        result == null ? null : result.getCode(),
                        result == null ? null : result.getMessage());
                answer = "模型服务返回为空";
            }
            memoryStore.append(sessionId, request.getMessage(), answer);
            return RuntimeChatResponse.builder()
                    .answer(answer)
                    .sessionId(sessionId)
                    .metadata(buildMetadata(data))
                    .build();
        } catch (Exception e) {
            log.error("[RuntimeChat] chat failed", e);
            return RuntimeChatResponse.error(e.getMessage());
        }
    }

    @Override
    public void clearSession(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            memoryStore.clear(sessionId);
        }
    }

    private ModelChatRequest buildModelRequest(RuntimeChatRequest request, String sessionId) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.builder().role("system").content(DEFAULT_SYSTEM_PROMPT).build());
        for (RuntimeChatMemoryMessage message : memoryStore.getHistory(sessionId)) {
            messages.add(ChatMessage.builder()
                    .role(message.getRole())
                    .content(message.getContent())
                    .build());
        }
        messages.add(ChatMessage.builder().role("user").content(request.getMessage()).build());
        return ModelChatRequest.builder()
                .modelInstanceId(requireModelInstanceId(request.getModelInstanceId()))
                .messages(messages)
                .build();
    }

    private Map<String, Object> buildMetadata(ModelChatData data) {
        if (data == null) {
            return null;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "model", data.getModel());
        putIfPresent(metadata, "provider", data.getProvider());
        putIfPresent(metadata, "usage", data.getUsage());
        putIfPresent(metadata, "reasoningContent", data.getReasoningContent());
        putIfPresent(metadata, "toolCalls", data.getToolCalls());
        putIfPresent(metadata, "finishReason", data.getFinishReason());
        return metadata.isEmpty() ? null : metadata;
    }

    private void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private String resolveSessionId(RuntimeChatRequest request) {
        if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
            return request.getSessionId();
        }
        String generated = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        request.setSessionId(generated);
        return generated;
    }

    private String requireModelInstanceId(String modelInstanceId) {
        if (modelInstanceId == null || modelInstanceId.isBlank()) {
            throw new IllegalStateException("modelInstanceId is required");
        }
        return modelInstanceId.trim();
    }
}

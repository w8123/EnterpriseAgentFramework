package com.enterprise.ai.model.provider.tongyi;

import com.enterprise.ai.model.provider.ModelProvider;
import com.enterprise.ai.model.service.ChatRequest;
import com.enterprise.ai.model.service.ChatResponse;
import com.enterprise.ai.model.service.EmbeddingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 通义千问 / DashScope Provider 实现。
 * 底层使用 Spring AI Alibaba DashScope Starter 自动配置的 ChatModel。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TongyiProvider implements ModelProvider {

    private final ChatClient.Builder chatClientBuilder;

    @Override
    public String getName() {
        return "tongyi";
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        int msgCount = request.getMessages() == null ? 0 : request.getMessages().size();
        log.info("[Tongyi Chat] 开始调用 DashScope，model={}, messages数量={}", request.getModel(), msgCount);

        List<Message> messages = convertMessages(request.getMessages());
        Prompt prompt = new Prompt(messages);

        long start = System.currentTimeMillis();
        var result = chatClientBuilder.build()
                .prompt(prompt)
                .call()
                .chatResponse();
        long elapsed = System.currentTimeMillis() - start;

        String content = result.getResult().getOutput().getText();

        ChatResponse.Usage usage = ChatResponse.Usage.builder().build();
        if (result.getMetadata() != null && result.getMetadata().getUsage() != null) {
            var u = result.getMetadata().getUsage();
            usage = ChatResponse.Usage.builder()
                    .promptTokens((int) u.getPromptTokens())
                    .completionTokens((int) u.getCompletionTokens())
                    .totalTokens((int) u.getTotalTokens())
                    .build();
        }

        log.info("[Tongyi Chat] 调用完成 耗时={}ms, promptTokens={}, completionTokens={}, totalTokens={}",
                elapsed, usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
        log.debug("[Tongyi Chat] 响应内容: {}", content);

        return ChatResponse.builder()
                .content(content)
                .model(request.getModel())
                .provider(getName())
                .usage(usage)
                .build();
    }

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        int msgCount = request.getMessages() == null ? 0 : request.getMessages().size();
        log.info("[Tongyi ChatStream] 开始流式调用 DashScope，model={}, messages数量={}", request.getModel(), msgCount);

        List<Message> messages = convertMessages(request.getMessages());
        Prompt prompt = new Prompt(messages);

        return chatClientBuilder.build()
                .prompt(prompt)
                .stream()
                .content();
    }

    @Override
    public EmbeddingResponse embed(List<String> texts, String model) {
        // Spring AI DashScope 目前需通过 EmbeddingModel Bean 调用
        // 此处暂用 REST 方式兜底，后续可替换为 Spring AI EmbeddingModel
        throw new UnsupportedOperationException(
                "Tongyi embedding 请通过 TongyiEmbeddingProvider 调用，或等待 Spring AI DashScope Embedding 集成完善");
    }

    @Override
    public List<String> listModels() {
        return List.of("qwen-max", "qwen-plus", "qwen-turbo", "qwen-long",
                "deepseek-r1", "deepseek-v3");
    }

    @Override
    public boolean test() {
        try {
            ChatRequest ping = ChatRequest.builder()
                    .messages(List.of(
                            ChatRequest.ChatMessage.builder()
                                    .role("user").content("hi").build()))
                    .build();
            ChatResponse resp = chat(ping);
            return resp.getContent() != null && !resp.getContent().isBlank();
        } catch (Exception e) {
            log.warn("Tongyi provider test failed", e);
            return false;
        }
    }

    private List<Message> convertMessages(List<ChatRequest.ChatMessage> messages) {
        List<Message> result = new ArrayList<>();
        if (messages == null) return result;
        for (ChatRequest.ChatMessage msg : messages) {
            switch (msg.getRole()) {
                case "system" -> result.add(new SystemMessage(msg.getContent()));
                case "assistant" -> result.add(new AssistantMessage(msg.getContent()));
                default -> result.add(new UserMessage(msg.getContent()));
            }
        }
        return result;
    }
}

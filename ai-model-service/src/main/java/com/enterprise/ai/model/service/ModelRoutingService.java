package com.enterprise.ai.model.service;

import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.provider.ModelProvider;
import com.enterprise.ai.model.provider.tongyi.TongyiEmbeddingProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型路由服务 — 根据请求中的 provider 参数分发到对应实现，
 * 未指定时使用默认 Provider。
 */
@Slf4j
@Service
public class ModelRoutingService {

    private final Map<String, ModelProvider> providerMap = new HashMap<>();
    private final TongyiEmbeddingProvider tongyiEmbeddingProvider;

    @Value("${model.default-provider:tongyi}")
    private String defaultProvider;

    public ModelRoutingService(List<ModelProvider> providers,
                               TongyiEmbeddingProvider tongyiEmbeddingProvider) {
        this.tongyiEmbeddingProvider = tongyiEmbeddingProvider;
        for (ModelProvider p : providers) {
            providerMap.put(p.getName(), p);
            log.info("注册模型 Provider: {}", p.getName());
        }
    }

    public ChatResponse chat(ChatRequest request) {
        ModelProvider provider = resolveProvider(request.getProvider());
        log.debug("Chat 路由到 Provider: {}", provider.getName());
        return provider.chat(request);
    }

    public Flux<String> chatStream(ChatRequest request) {
        ModelProvider provider = resolveProvider(request.getProvider());
        log.debug("ChatStream 路由到 Provider: {}", provider.getName());
        return provider.chatStream(request);
    }

    public EmbeddingResponse embed(EmbeddingRequest request) {
        String providerName = request.getProvider() != null ? request.getProvider() : defaultProvider;
        if ("tongyi".equals(providerName)) {
            return tongyiEmbeddingProvider.embed(request.getTexts(), request.getModel());
        }
        ModelProvider provider = resolveProvider(providerName);
        return provider.embed(request.getTexts(), request.getModel());
    }

    public List<ProviderInfo> listProviders() {
        return providerMap.values().stream()
                .map(p -> new ProviderInfo(p.getName(), p.listModels()))
                .toList();
    }

    public boolean testProvider(String providerName) {
        ModelProvider provider = resolveProvider(providerName);
        return provider.test();
    }

    private ModelProvider resolveProvider(String name) {
        String key = (name != null && !name.isBlank()) ? name : defaultProvider;
        ModelProvider provider = providerMap.get(key);
        if (provider == null) {
            throw new BizException(400, "未知的模型 Provider: " + key +
                    "，可用: " + providerMap.keySet());
        }
        return provider;
    }

    public record ProviderInfo(String name, List<String> models) {}
}

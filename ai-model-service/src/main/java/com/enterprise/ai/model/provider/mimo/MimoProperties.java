package com.enterprise.ai.model.provider.mimo;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 小米 MiMo OpenAI 兼容 API 配置（鉴权为 {@code api-key} 请求头）。
 */
@Data
@ConfigurationProperties(prefix = "model.mimo")
public class MimoProperties {

    /**
     * API Key，对应 curl 中的 {@code api-key} 头；建议通过环境变量 {@code MIMO_API_KEY} 注入。
     */
    private String apiKey;

    /**
     * 服务根地址，不含路径；实际请求 {@code baseUrl + /v1/chat/completions}。
     */
    private String baseUrl = "https://api.xiaomimimo.com";

    /** 未指定 model 时的默认模型 */
    private String defaultModel = "mimo-v2.5-pro";

    /** 管理端展示的可用模型列表 */
    private List<String> models = new ArrayList<>(List.of("mimo-v2.5-pro"));

    /** 默认 max_completion_tokens */
    private int maxCompletionTokens = 1024;

    private double temperature = 1.0;

    private double topP = 0.95;

    private double frequencyPenalty = 0;

    private double presencePenalty = 0;
}

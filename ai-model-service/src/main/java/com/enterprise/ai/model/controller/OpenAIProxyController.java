package com.enterprise.ai.model.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

/**
 * OpenAI 兼容格式代理端点。
 * <p>
 * 将 OpenAI 格式的请求透传到 DashScope 的 OpenAI 兼容 API，
 * 使得 AgentScope 的 OpenAIChatModel 可以通过 model-service 间接调用 DashScope。
 * <p>
 * 架构收益：
 * - AgentScope Agent 路径的所有 LLM 调用统一经过 model-service
 * - API Key 集中管理在 model-service，agent-service 无需持有
 * - 后续可在此层增加限流、日志、路由等能力
 */
@Slf4j
@RestController
@RequestMapping("/model/openai-proxy")
public class OpenAIProxyController {

    private static final String DASHSCOPE_COMPATIBLE_BASE = "https://dashscope.aliyuncs.com/compatible-mode";

    @Value("${model.tongyi.api-key:${spring.ai.dashscope.api-key:}}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 透传 OpenAI 格式请求到 DashScope 兼容端点（同步）。
     * <p>
     * AgentScope OpenAIChatModel 会调用 baseUrl + /chat/completions，
     * 因此这里匹配 /v1/chat/completions 路径。
     */
    @PostMapping("/v1/chat/completions")
    public ResponseEntity<String> proxyChatCompletions(
            @RequestBody String body,
            HttpServletRequest servletRequest) {

        log.debug("[OpenAI Proxy] 收到代理请求, bodyLength={}", body.length());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        String targetUrl = DASHSCOPE_COMPATIBLE_BASE + "/v1/chat/completions";

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    targetUrl,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class
            );

            log.debug("[OpenAI Proxy] DashScope 响应 status={}", response.getStatusCode());
            return ResponseEntity.status(response.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.getBody());

        } catch (Exception e) {
            log.error("[OpenAI Proxy] DashScope 调用失败", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":{\"message\":\"Model service proxy error: " + e.getMessage() + "\"}}");
        }
    }
}

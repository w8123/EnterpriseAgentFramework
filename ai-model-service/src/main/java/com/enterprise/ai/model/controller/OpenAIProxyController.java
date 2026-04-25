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

    /** DEBUG 下请求/响应 JSON 预览最大字符数 */
    private static final int DEBUG_BODY_PREVIEW_MAX = 1200;

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
        if (log.isDebugEnabled()) {
            log.debug("[OpenAI Proxy] 请求体预览: {}", previewJson(body));
        }

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
            if (log.isDebugEnabled()) {
                String respBody = response.getBody();
                int len = respBody == null ? 0 : respBody.length();
                log.debug("[OpenAI Proxy] DashScope 响应体长度={}", len);
                log.debug("[OpenAI Proxy] DashScope 响应体预览: {}", previewJson(respBody));
            }
            return ResponseEntity.status(response.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.getBody());

        } catch (Exception e) {
            log.error("[OpenAI Proxy] DashScope 调用失败", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":{\"message\":\"Model service proxy error: " + e.getMessage() + "\"}}");
        }
    }

    private static String previewJson(String raw) {
        if (raw == null) {
            return "null";
        }
        if (raw.length() <= DEBUG_BODY_PREVIEW_MAX) {
            return raw;
        }
        return raw.substring(0, DEBUG_BODY_PREVIEW_MAX)
                + "... (已截断预览，总长=" + raw.length() + ")";
    }
}

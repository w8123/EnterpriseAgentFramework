package com.enterprise.ai.model.controller;

import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.instance.EndpointType;
import com.enterprise.ai.model.instance.ModelInstanceEntity;
import com.enterprise.ai.model.instance.ModelInstanceRuntime;
import com.enterprise.ai.model.instance.ModelInstanceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@Slf4j
@RestController
@RequestMapping("/model/openai-proxy")
@RequiredArgsConstructor
public class OpenAIProxyController {

    private static final int DEBUG_BODY_PREVIEW_MAX = 1200;

    private final ModelInstanceService modelInstanceService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<String> proxyChatCompletions(@RequestBody String body,
                                                       HttpServletRequest servletRequest) {
        log.debug("[OpenAI Proxy] request bodyLength={}", body.length());
        if (log.isDebugEnabled()) {
            log.debug("[OpenAI Proxy] request preview: {}", previewJson(body));
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            String modelInstanceId = root.path("model").asText("");
            if (modelInstanceId.isBlank()) {
                throw new BizException(400, "model field must be modelInstanceId for openai-proxy");
            }

            ModelInstanceEntity entity = modelInstanceService.getActiveEntity(modelInstanceId);
            ModelInstanceRuntime runtime = modelInstanceService.toRuntime(entity);
            if (!EndpointType.OPENAI_COMPATIBLE.name().equals(runtime.getEndpointType())) {
                throw new BizException(400, "openai-proxy only supports OPENAI_COMPATIBLE model instances");
            }

            ObjectNode rewrittenBody = root.deepCopy();
            rewrittenBody.put("model", runtime.getModelName());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String apiKey = stringCredential(runtime, "apiKey", "token");
            String authHeader = stringCredential(runtime, "authHeader");
            String authPrefix = stringCredential(runtime, "authPrefix");
            if (apiKey != null && !apiKey.isBlank()) {
                headers.set(authHeader == null || authHeader.isBlank() ? "Authorization" : authHeader,
                        authPrefix == null ? "Bearer " + apiKey : authPrefix + apiKey);
            }

            ResponseEntity<String> response = restTemplate.exchange(
                    resolveUrl(stringCredential(runtime, "baseUrl", "apiBase", "endpoint"), "/v1/chat/completions"),
                    HttpMethod.POST,
                    new HttpEntity<>(objectMapper.writeValueAsString(rewrittenBody), headers),
                    String.class
            );
            return ResponseEntity.status(response.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.getBody());
        } catch (BizException e) {
            return ResponseEntity.status(e.getCode()).body(errorBody(e.getMessage()));
        } catch (Exception e) {
            log.error("[OpenAI Proxy] model call failed", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(errorBody("Model service proxy error: " + e.getMessage()));
        }
    }

    private String stringCredential(ModelInstanceRuntime runtime, String... keys) {
        if (runtime.getCredential() == null) return null;
        for (String key : keys) {
            Object value = runtime.getCredential().get(key);
            if (value != null) return String.valueOf(value);
        }
        return null;
    }

    private String resolveUrl(String baseUrl, String defaultPath) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BizException(400, "OpenAI-compatible model requires credential.baseUrl");
        }
        String value = baseUrl.trim();
        if (value.endsWith("/chat/completions")) return value;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        // DashScope 等常见配置为 .../compatible-mode/v1，避免与 defaultPath 的 /v1/... 叠成 /v1/v1/
        if (value.endsWith("/v1") && defaultPath.startsWith("/v1/")) {
            return value + defaultPath.substring("/v1".length());
        }
        return value + defaultPath;
    }

    private String errorBody(String message) {
        String escaped = message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"error\":{\"message\":\"" + escaped + "\"}}";
    }

    private static String previewJson(String raw) {
        if (raw == null) {
            return "null";
        }
        if (raw.length() <= DEBUG_BODY_PREVIEW_MAX) {
            return raw;
        }
        return raw.substring(0, DEBUG_BODY_PREVIEW_MAX) + "...";
    }
}

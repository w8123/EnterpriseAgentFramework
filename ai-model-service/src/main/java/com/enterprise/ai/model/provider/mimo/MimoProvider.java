package com.enterprise.ai.model.provider.mimo;

import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.provider.ModelProvider;
import com.enterprise.ai.model.service.ChatRequest;
import com.enterprise.ai.model.service.ChatResponse;
import com.enterprise.ai.model.service.EmbeddingResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 小米 MiMo OpenAI 兼容 Chat Completions（{@code api-key} 鉴权，非 Bearer）。
 */
@Slf4j
@RequiredArgsConstructor
public class MimoProvider implements ModelProvider {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(8);

    private final MimoProperties properties;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    @Override
    public String getName() {
        return "mimo";
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        int msgCount = request.getMessages() == null ? 0 : request.getMessages().size();
        log.info("[MiMo Chat] model={}, messages={}", resolveModel(request), msgCount);

        String uri = trimTrailingSlash(properties.getBaseUrl()) + "/v1/chat/completions";
        String json;
        try {
            json = objectMapper.writeValueAsString(buildBody(request, false));
        } catch (Exception e) {
            throw new BizException(400, "构建 MiMo 请求失败: " + e.getMessage());
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(REQUEST_TIMEOUT)
                .header("api-key", properties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsed = System.currentTimeMillis() - start;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[MiMo Chat] HTTP {} body={}", response.statusCode(), truncate(response.body(), 500));
                throw new BizException(response.statusCode() >= 400 && response.statusCode() < 600
                        ? response.statusCode() : 502, "MiMo API 错误: " + response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choices = root.path("choices");
            JsonNode choice0 = choices.isArray() && !choices.isEmpty() ? choices.get(0) : null;
            JsonNode message = choice0 != null ? choice0.path("message") : objectMapper.missingNode();
            String content = message.hasNonNull("content") ? message.get("content").asText("") : "";
            String reasoning = message.hasNonNull("reasoning_content")
                    ? message.get("reasoning_content").asText(null) : null;
            JsonNode toolCalls = message.get("tool_calls");
            if (toolCalls != null && toolCalls.isNull()) {
                toolCalls = null;
            }
            String finishReason = choice0 != null && choice0.hasNonNull("finish_reason")
                    ? choice0.get("finish_reason").asText(null) : null;
            ChatResponse.Usage usage = parseUsage(root);
            log.info("[MiMo Chat] 完成 耗时={}ms, totalTokens={}, finishReason={}", elapsed,
                    usage != null ? usage.getTotalTokens() : "N/A", finishReason);
            return ChatResponse.builder()
                    .content(content)
                    .model(resolveModel(request))
                    .provider(getName())
                    .usage(usage != null ? usage : ChatResponse.Usage.builder().build())
                    .reasoningContent(reasoning)
                    .toolCalls(toolCalls)
                    .finishReason(finishReason)
                    .build();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[MiMo Chat] 调用失败", e);
            throw new BizException(502, "MiMo 调用失败: " + e.getMessage());
        }
    }

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        int msgCount = request.getMessages() == null ? 0 : request.getMessages().size();
        log.info("[MiMo ChatStream] model={}, messages={}", resolveModel(request), msgCount);

        String uri = trimTrailingSlash(properties.getBaseUrl()) + "/v1/chat/completions";
        final String json;
        try {
            json = objectMapper.writeValueAsString(buildBody(request, true));
        } catch (Exception e) {
            return Flux.error(new BizException(400, "构建 MiMo 请求失败: " + e.getMessage()));
        }

        return Flux.<String>create(sink -> Schedulers.boundedElastic().schedule(() -> {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .timeout(REQUEST_TIMEOUT)
                    .header("api-key", properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<java.io.InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    log.warn("[MiMo ChatStream] HTTP {} body={}", response.statusCode(), truncate(errBody, 500));
                    sink.error(new BizException(
                            response.statusCode() >= 400 && response.statusCode() < 600 ? response.statusCode() : 502,
                            "MiMo API 错误: " + errBody));
                    return;
                }
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            continue;
                        }
                        if (!line.startsWith("data:")) {
                            continue;
                        }
                        String data = line.substring(line.indexOf(':') + 1).trim();
                        if ("[DONE]".equals(data)) {
                            break;
                        }
                        JsonNode chunk = objectMapper.readTree(data);
                        String delta = extractDeltaContent(chunk);
                        if (delta != null && !delta.isEmpty()) {
                            sink.next(delta);
                        }
                    }
                }
                sink.complete();
            } catch (Exception e) {
                log.error("[MiMo ChatStream] 失败", e);
                sink.error(e);
            }
        }), FluxSink.OverflowStrategy.BUFFER);
    }

    @Override
    public EmbeddingResponse embed(List<String> texts, String model) {
        throw new BizException(400, "MiMo 当前集成仅支持 Chat，不支持 Embedding");
    }

    @Override
    public List<String> listModels() {
        List<String> m = properties.getModels();
        return m == null || m.isEmpty() ? List.of(properties.getDefaultModel()) : List.copyOf(m);
    }

    @Override
    public boolean test() {
        try {
            ChatRequest ping = ChatRequest.builder()
                    .provider(getName())
                    .model(properties.getDefaultModel())
                    .messages(List.of(
                            ChatRequest.ChatMessage.builder().role("user").content("hi").build()))
                    .build();
            ChatResponse resp = chat(ping);
            return resp.getContent() != null && !resp.getContent().isBlank();
        } catch (Exception e) {
            log.warn("MiMo provider test failed", e);
            return false;
        }
    }

    private String resolveModel(ChatRequest request) {
        if (request.getModel() != null && !request.getModel().isBlank()) {
            return request.getModel();
        }
        return properties.getDefaultModel();
    }

    private ObjectNode buildBody(ChatRequest request, boolean stream) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", resolveModel(request));
        root.put("stream", stream);

        ArrayNode arr = root.putArray("messages");
        if (request.getMessages() != null) {
            for (ChatRequest.ChatMessage msg : request.getMessages()) {
                ObjectNode m = arr.addObject();
                String role = msg.getRole() != null ? msg.getRole().toLowerCase() : "user";
                if (!"system".equals(role) && !"user".equals(role) && !"assistant".equals(role) && !"tool".equals(role)) {
                    role = "user";
                }
                m.put("role", role);
                m.put("content", msg.getContent() == null ? "" : msg.getContent());
                if (msg.getReasoningContent() != null && !msg.getReasoningContent().isBlank()) {
                    m.put("reasoning_content", msg.getReasoningContent());
                }
                if (msg.getToolCalls() != null && !msg.getToolCalls().isNull()) {
                    m.set("tool_calls", msg.getToolCalls());
                }
                if (msg.getToolCallId() != null && !msg.getToolCallId().isBlank()) {
                    m.put("tool_call_id", msg.getToolCallId());
                }
                if (msg.getName() != null && !msg.getName().isBlank()) {
                    m.put("name", msg.getName());
                }
            }
        }

        if (request.getTools() != null && request.getTools().isArray() && !request.getTools().isEmpty()) {
            root.set("tools", request.getTools());
        }
        if (request.getToolChoice() != null && !request.getToolChoice().isNull()) {
            root.set("tool_choice", request.getToolChoice());
        }

        int maxCt = properties.getMaxCompletionTokens();
        double temp = properties.getTemperature();
        double topP = properties.getTopP();
        double fp = properties.getFrequencyPenalty();
        double pp = properties.getPresencePenalty();

        Map<String, Object> opt = request.getOptions();
        if (opt != null) {
            if (opt.get("max_completion_tokens") instanceof Number n) {
                maxCt = n.intValue();
            } else if (opt.get("maxTokens") instanceof Number n) {
                maxCt = n.intValue();
            }
            if (opt.get("temperature") instanceof Number n) {
                temp = n.doubleValue();
            }
            if (opt.get("top_p") instanceof Number n) {
                topP = n.doubleValue();
            }
            if (opt.get("frequency_penalty") instanceof Number n) {
                fp = n.doubleValue();
            }
            if (opt.get("presence_penalty") instanceof Number n) {
                pp = n.doubleValue();
            }
            if (opt.containsKey("stop")) {
                Object stop = opt.get("stop");
                if (stop == null) {
                    root.putNull("stop");
                } else {
                    root.set("stop", objectMapper.valueToTree(stop));
                }
            } else {
                root.putNull("stop");
            }
        } else {
            root.putNull("stop");
        }

        root.put("max_completion_tokens", maxCt);
        root.put("temperature", temp);
        root.put("top_p", topP);
        root.put("frequency_penalty", fp);
        root.put("presence_penalty", pp);
        return root;
    }

    private static String extractDeltaContent(JsonNode chunk) {
        JsonNode choices = chunk.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return "";
        }
        JsonNode delta = choices.get(0).path("delta");
        StringBuilder out = new StringBuilder();
        if (delta.hasNonNull("reasoning_content")) {
            out.append(delta.get("reasoning_content").asText(""));
        }
        if (delta.hasNonNull("content")) {
            out.append(delta.get("content").asText(""));
        }
        return out.toString();
    }

    private static ChatResponse.Usage parseUsage(JsonNode root) {
        JsonNode u = root.path("usage");
        if (u.isMissingNode() || u.isNull()) {
            return null;
        }
        return ChatResponse.Usage.builder()
                .promptTokens(u.path("prompt_tokens").asInt(0))
                .completionTokens(u.path("completion_tokens").asInt(0))
                .totalTokens(u.path("total_tokens").asInt(0))
                .build();
    }

    private static String trimTrailingSlash(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        String s = baseUrl.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

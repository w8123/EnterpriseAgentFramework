package com.enterprise.ai.model.runtime;

import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.instance.ModelInstanceRuntime;
import com.enterprise.ai.model.service.ChatRequest;
import com.enterprise.ai.model.service.ChatResponse;
import com.enterprise.ai.model.service.EmbeddingResponse;
import com.enterprise.ai.model.service.RerankRequest;
import com.enterprise.ai.model.service.RerankResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleRuntimeClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(8);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public ChatResponse chat(ModelInstanceRuntime runtime, ChatRequest request) {
        ObjectNode body = buildChatBody(runtime, request, false);
        HttpRequest httpRequest = requestBuilder(runtime, "/v1/chat/completions")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(response.statusCode(), response.body(), runtime);
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choice = firstChoice(root);
            JsonNode message = choice.path("message");
            return ChatResponse.builder()
                    .content(message.path("content").asText(""))
                    .provider(runtime.getProvider())
                    .model(runtime.getModelName())
                    .usage(parseUsage(root))
                    .reasoningContent(message.hasNonNull("reasoning_content") ? message.get("reasoning_content").asText() : null)
                    .toolCalls(message.get("tool_calls"))
                    .finishReason(choice.hasNonNull("finish_reason") ? choice.get("finish_reason").asText() : null)
                    .build();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(502, "OpenAI-compatible model call failed: " + e.getMessage());
        }
    }

    public Flux<String> chatStream(ModelInstanceRuntime runtime, ChatRequest request) {
        String json = toJson(buildChatBody(runtime, request, true));
        return Flux.<String>create(sink -> Schedulers.boundedElastic().schedule(() -> {
            HttpRequest httpRequest = requestBuilder(runtime, "/v1/chat/completions")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<java.io.InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    log.warn("Model instance {} stream call failed: HTTP {} body={}",
                            runtime.getId(), response.statusCode(), truncate(errorBody));
                    sink.error(new BizException(response.statusCode(), providerErrorMessage(response.statusCode(), errorBody)));
                    return;
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) {
                            continue;
                        }
                        String data = line.substring(line.indexOf(':') + 1).trim();
                        if ("[DONE]".equals(data)) {
                            break;
                        }
                        String delta = extractDelta(objectMapper.readTree(data));
                        if (!delta.isEmpty()) {
                            sink.next(delta);
                        }
                    }
                }
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        }), FluxSink.OverflowStrategy.BUFFER);
    }

    public EmbeddingResponse embed(ModelInstanceRuntime runtime, List<String> texts) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", runtime.getModelName());
        ArrayNode input = body.putArray("input");
        for (String text : texts) {
            input.add(text == null ? "" : text);
        }
        if (runtime.getDefaultOptions() != null) {
            runtime.getDefaultOptions().forEach((key, value) -> body.set(key, objectMapper.valueToTree(value)));
        }
        HttpRequest httpRequest = requestBuilder(runtime, "/v1/embeddings")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(response.statusCode(), response.body(), runtime);
            JsonNode root = objectMapper.readTree(response.body());
            List<List<Float>> embeddings = new ArrayList<>();
            for (JsonNode item : root.path("data")) {
                List<Float> vector = new ArrayList<>();
                for (JsonNode value : item.path("embedding")) {
                    vector.add(value.floatValue());
                }
                embeddings.add(vector);
            }
            int dimension = embeddings.isEmpty() ? 0 : embeddings.get(0).size();
            return EmbeddingResponse.builder()
                    .provider(runtime.getProvider())
                    .model(runtime.getModelName())
                    .dimension(dimension)
                    .embeddings(embeddings)
                    .build();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(502, "OpenAI-compatible embedding call failed: " + e.getMessage());
        }
    }

    public RerankResponse rerank(ModelInstanceRuntime runtime, RerankRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", runtime.getModelName());
        body.put("query", request.getQuery() == null ? "" : request.getQuery());
        ArrayNode documents = body.putArray("documents");
        if (request.getDocuments() != null) {
            for (String document : request.getDocuments()) {
                documents.add(document == null ? "" : document);
            }
        }
        if (request.getTopN() != null) {
            body.put("top_n", request.getTopN());
        }
        Map<String, Object> options = mergedRerankOptions(runtime, request);
        options.forEach((key, value) -> body.set(key, objectMapper.valueToTree(value)));
        String path = String.valueOf(options.getOrDefault("path", "/v1/rerank"));
        body.remove("path");
        HttpRequest httpRequest = requestBuilder(runtime, path)
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(response.statusCode(), response.body(), runtime);
            JsonNode root = objectMapper.readTree(response.body());
            List<RerankResponse.RerankResult> results = new ArrayList<>();
            JsonNode resultNodes = root.has("results") ? root.path("results") : root.path("data");
            if (resultNodes.isArray()) {
                for (JsonNode item : resultNodes) {
                    int index = item.has("index") ? item.path("index").asInt()
                            : item.path("document").path("index").asInt(results.size());
                    float score = item.has("relevance_score") ? (float) item.path("relevance_score").asDouble()
                            : (float) item.path("score").asDouble(0);
                    String document = index >= 0 && request.getDocuments() != null && index < request.getDocuments().size()
                            ? request.getDocuments().get(index)
                            : item.path("document").asText(null);
                    results.add(RerankResponse.RerankResult.builder()
                            .index(index)
                            .score(score)
                            .document(document)
                            .build());
                }
            }
            return RerankResponse.builder()
                    .provider(runtime.getProvider())
                    .model(runtime.getModelName())
                    .results(results)
                    .build();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(502, "OpenAI-compatible rerank call failed: " + e.getMessage());
        }
    }

    private ObjectNode buildChatBody(ModelInstanceRuntime runtime, ChatRequest request, boolean stream) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", runtime.getModelName());
        root.put("stream", stream);
        ArrayNode messages = root.putArray("messages");
        if (request.getMessages() != null) {
            for (ChatRequest.ChatMessage msg : request.getMessages()) {
                ObjectNode m = messages.addObject();
                m.put("role", normalizeRole(msg.getRole()));
                m.put("content", msg.getContent() == null ? "" : msg.getContent());
                if (msg.getName() != null) m.put("name", msg.getName());
                if (msg.getToolCallId() != null) m.put("tool_call_id", msg.getToolCallId());
                if (msg.getReasoningContent() != null) m.put("reasoning_content", msg.getReasoningContent());
                if (msg.getToolCalls() != null) m.set("tool_calls", msg.getToolCalls());
            }
        }
        mergedOptions(runtime, request).forEach((key, value) -> root.set(key, objectMapper.valueToTree(value)));
        if (request.getTools() != null) root.set("tools", request.getTools());
        if (request.getToolChoice() != null) root.set("tool_choice", request.getToolChoice());
        return root;
    }

    private Map<String, Object> mergedOptions(ModelInstanceRuntime runtime, ChatRequest request) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (runtime.getDefaultOptions() != null) merged.putAll(runtime.getDefaultOptions());
        if (request.getOptions() != null) merged.putAll(request.getOptions());
        return merged;
    }

    private Map<String, Object> mergedRerankOptions(ModelInstanceRuntime runtime, RerankRequest request) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (runtime.getDefaultOptions() != null) merged.putAll(runtime.getDefaultOptions());
        if (request.getOptions() != null) merged.putAll(request.getOptions());
        return merged;
    }

    private HttpRequest.Builder requestBuilder(ModelInstanceRuntime runtime, String defaultPath) {
        String baseUrl = stringCredential(runtime, "baseUrl", "apiBase", "endpoint");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BizException(400, "OpenAI-compatible model requires credential.baseUrl");
        }
        String apiKey = stringCredential(runtime, "apiKey", "token");
        String authHeader = stringCredential(runtime, "authHeader");
        String authPrefix = stringCredential(runtime, "authPrefix");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(resolveUrl(baseUrl, defaultPath)))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header(authHeader == null || authHeader.isBlank() ? "Authorization" : authHeader,
                    authPrefix == null ? "Bearer " + apiKey : authPrefix + apiKey);
        }
        return builder;
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
        String value = baseUrl.trim();
        if (value.endsWith("/chat/completions") || value.endsWith("/embeddings") || value.endsWith("/rerank")) return value;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.endsWith("/v1") && defaultPath.startsWith("/v1/")) {
            return value + defaultPath.substring("/v1".length());
        }
        return value + defaultPath;
    }

    private String normalizeRole(String role) {
        if (role == null) return "user";
        return switch (role.toLowerCase()) {
            case "system", "assistant", "tool" -> role.toLowerCase();
            default -> "user";
        };
    }

    private JsonNode firstChoice(JsonNode root) {
        JsonNode choices = root.path("choices");
        return choices.isArray() && !choices.isEmpty() ? choices.get(0) : objectMapper.createObjectNode();
    }

    private ChatResponse.Usage parseUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        return ChatResponse.Usage.builder()
                .promptTokens(usage.path("prompt_tokens").asInt(0))
                .completionTokens(usage.path("completion_tokens").asInt(0))
                .totalTokens(usage.path("total_tokens").asInt(0))
                .build();
    }

    private String extractDelta(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) return "";
        JsonNode delta = choices.get(0).path("delta");
        StringBuilder out = new StringBuilder();
        if (delta.hasNonNull("reasoning_content")) out.append(delta.get("reasoning_content").asText(""));
        if (delta.hasNonNull("content")) out.append(delta.get("content").asText(""));
        return out.toString();
    }

    private void ensureSuccess(int status, String body, ModelInstanceRuntime runtime) {
        if (status < 200 || status >= 300) {
            log.warn("Model instance {} call failed: HTTP {} body={}", runtime.getId(), status, truncate(body));
            throw new BizException(status, providerErrorMessage(status, body));
        }
    }

    private String providerErrorMessage(int status, String body) {
        String detail = truncate(body);
        if (detail == null || detail.isBlank()) {
            return "Model provider API error: HTTP " + status;
        }
        return "Model provider API error: HTTP " + status + " " + detail;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(400, "Serialize request body failed: " + e.getMessage());
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 800) return value;
        return value.substring(0, 800) + "...";
    }
}

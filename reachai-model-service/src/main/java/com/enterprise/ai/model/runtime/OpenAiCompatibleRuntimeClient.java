package com.enterprise.ai.model.runtime;

import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.instance.ModelInstanceRuntime;
import com.enterprise.ai.model.instance.ModelProtectedOptions;
import com.enterprise.ai.model.instance.ModelType;
import com.enterprise.ai.model.service.ChatRequest;
import com.enterprise.ai.model.service.ChatResponse;
import com.enterprise.ai.model.service.EmbeddingResponse;
import com.enterprise.ai.model.service.ModelStreamEvent;
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
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleRuntimeClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(8);
    private static final Duration MODEL_LIST_TIMEOUT = Duration.ofSeconds(20);
    /** finish_reason 之后等待流末 usage 块 / [DONE] 的上限，超时即关闭连接按正常结束处理。 */
    private static final Duration USAGE_TAIL_GRACE = Duration.ofMillis(1500);
    private static final int MODEL_LIST_MAX_BYTES = 4 * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public ChatResponse chat(ModelInstanceRuntime runtime, ChatRequest request) {
        return chat(runtime, request, REQUEST_TIMEOUT);
    }

    /** Complete non-streaming response budget, including body consumption on JDK 17. */
    public ChatResponse chat(ModelInstanceRuntime runtime, ChatRequest request, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(REQUEST_TIMEOUT) > 0) {
            throw new IllegalArgumentException("Chat timeout must be positive and at most eight minutes");
        }
        ObjectNode body = buildChatBody(runtime, request, false);
        HttpRequest httpRequest = requestBuilder(runtime, resolvePath(runtime, "chatPath", ModelApiUrl.DEFAULT_CHAT_PATH))
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<String>> responseFuture = null;
        try {
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            responseFuture = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResponse<String> response;
            try {
                // Keep the original cancellable exchange: orTimeout would complete it before cancel can act.
                response = responseFuture.get(Math.max(1L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (ExecutionException e) {
                if (!isStaleConnectionFailure(e.getCause())) {
                    throw e;
                }
                log.warn("Model instance {} chat call hit a reset pooled connection; retrying once", runtime.getId());
                responseFuture = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                response = responseFuture.get(Math.max(1L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
            ensureSuccess(response.statusCode(), response.body(), runtime);
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choice = firstChoice(root);
            JsonNode message = choice.path("message");
            return ChatResponse.builder()
                    .content(message.path("content").asText(""))
                    .provider(runtime.getProvider())
                    .model(runtime.getModelName())
                    .usage(OpenAiCompatibleStreamParser.parseUsage(root))
                    .reasoningContent(message.hasNonNull("reasoning_content") ? message.get("reasoning_content").asText() : null)
                    .toolCalls(message.get("tool_calls"))
                    .finishReason(choice.hasNonNull("finish_reason") ? choice.get("finish_reason").asText() : null)
                    .build();
        } catch (TimeoutException e) {
            responseFuture.cancel(true);
            throw chatTimeout(timeout);
        } catch (InterruptedException e) {
            responseFuture.cancel(true);
            Thread.currentThread().interrupt();
            throw new BizException(502, "OpenAI-compatible model call interrupted");
        } catch (ExecutionException e) {
            responseFuture.cancel(true);
            Throwable cause = e.getCause();
            for (int depth = 0; depth < 8 && (cause instanceof CompletionException || cause instanceof ExecutionException)
                    && cause.getCause() != null; depth++) {
                cause = cause.getCause();
            }
            if (cause instanceof HttpTimeoutException) {
                throw chatTimeout(timeout);
            }
            if (cause instanceof BizException failure) {
                throw failure;
            }
            throw new BizException(502, "OpenAI-compatible model call failed: "
                    + (cause == null ? "provider transport failure" : cause.getClass().getSimpleName()));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            if (responseFuture != null) {
                responseFuture.cancel(true);
            }
            throw new BizException(502, "OpenAI-compatible model call failed: " + e.getMessage());
        }
    }

    /**
     * Idle keep-alive connections are often closed by the provider or a proxy; the first request after a
     * long idle period then fails with "Connection reset" before any response arrives. Such a failure carries
     * no provider answer, so one retry on a fresh connection is safe. Timeouts are never retried.
     */
    private HttpResponse<java.io.InputStream> sendStreamRequest(ModelInstanceRuntime runtime, HttpRequest httpRequest)
            throws java.io.IOException, InterruptedException {
        try {
            return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.io.IOException e) {
            if (!isStaleConnectionFailure(e)) {
                throw e;
            }
            log.warn("Model instance {} stream call hit a reset pooled connection; retrying once", runtime.getId());
            return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        }
    }

    static boolean isStaleConnectionFailure(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; depth < 8 && cause != null; depth++) {
            if (cause instanceof HttpTimeoutException) {
                return false;
            }
            if (cause instanceof java.io.IOException io) {
                String message = io.getMessage();
                if (cause instanceof java.net.SocketException
                        || cause instanceof java.io.EOFException
                        || (message != null && (message.contains("Connection reset")
                        || message.contains("closed") || message.contains("EOF reached")))) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    private BizException chatTimeout(Duration timeout) {
        return new BizException(504, "OpenAI-compatible model call timed out after " + timeout.toMillis() + " ms");
    }

    /**
     * 结构化流式事件：content/reasoning/tool_call deltas、usage、completed、error。
     * 唯一正式模型流路径；不把 reasoning 合并进 content。
     */
    public Flux<ModelStreamEvent> chatStreamEvents(ModelInstanceRuntime runtime, ChatRequest request) {
        String json = toJson(buildChatBody(runtime, request, true));
        return Flux.<ModelStreamEvent>create(sink -> Schedulers.boundedElastic().schedule(() -> {
            HttpRequest httpRequest = requestBuilder(runtime, resolvePath(runtime, "chatPath", ModelApiUrl.DEFAULT_CHAT_PATH))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<java.io.InputStream> response = sendStreamRequest(runtime, httpRequest);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    log.warn("Model instance {} stream call failed: HTTP {} body={}",
                            runtime.getId(), response.statusCode(), truncate(errorBody));
                    sink.next(ModelStreamEvent.error(providerErrorMessage(response.statusCode(), errorBody)));
                    sink.error(new BizException(response.statusCode(), providerErrorMessage(response.statusCode(), errorBody)));
                    return;
                }
                java.io.InputStream body = response.body();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    SseTerminal terminal = drainOpenAiSse(reader, objectMapper, sink::next,
                            () -> Schedulers.parallel().schedule(() -> closeQuietly(body),
                                    USAGE_TAIL_GRACE.toMillis(), TimeUnit.MILLISECONDS));
                    emitSseTerminal(sink, terminal);
                }
            } catch (Exception e) {
                sink.next(ModelStreamEvent.error(e.getMessage()));
                sink.error(e);
            }
        }), FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * 读取供应商 OpenAI 兼容 SSE，按 [DONE] / finish_reason / 提前 EOF 决定唯一终态。
     * package-visible 供单测覆盖真实读循环（含 EOF）。
     */
    static SseTerminal drainOpenAiSse(BufferedReader reader,
                                      ObjectMapper objectMapper,
                                      java.util.function.Consumer<ModelStreamEvent> onEvent) throws Exception {
        return drainOpenAiSse(reader, objectMapper, onEvent, () -> { });
    }

    /**
     * stream_options.include_usage 时供应商在 finish_reason 块之后再发一个 choices 为空的 usage 块，
     * 然后才是 [DONE]。所以 finish_reason 之后继续读到 usage 或 [DONE]；同时调用 onAwaitingUsage
     * 让调用方限时关闭连接，避免不发 [DONE] 又不断开的供应商把调用方挂住。等待期间的读失败按正常结束处理。
     */
    static SseTerminal drainOpenAiSse(BufferedReader reader,
                                      ObjectMapper objectMapper,
                                      java.util.function.Consumer<ModelStreamEvent> onEvent,
                                      Runnable onAwaitingUsage) throws Exception {
        OpenAiCompatibleStreamParser parser = new OpenAiCompatibleStreamParser(objectMapper);
        boolean receivedDone = false;
        boolean usageSeen = false;
        boolean awaitingUsage = false;
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(line.indexOf(':') + 1).trim();
                if ("[DONE]".equals(data)) {
                    receivedDone = true;
                    break;
                }
                JsonNode root = objectMapper.readTree(data);
                usageSeen |= root.hasNonNull("usage");
                parser.acceptChunk(root, onEvent);
                if (parser.finishReason() != null && !parser.finishReason().isBlank()) {
                    if (usageSeen) {
                        break;
                    }
                    if (!awaitingUsage) {
                        awaitingUsage = true;
                        onAwaitingUsage.run();
                    }
                }
            }
        } catch (java.io.IOException e) {
            if (!awaitingUsage) {
                throw e;
            }
            log.debug("Model stream tail closed after finish_reason without usage: {}", e.getMessage());
        }
        return SseTerminal.of(receivedDone, parser.finishReason());
    }

    private static void closeQuietly(java.io.InputStream body) {
        try {
            body.close();
        } catch (java.io.IOException ignored) {
            // already closed
        }
    }

    static void emitSseTerminal(FluxSink<ModelStreamEvent> sink, SseTerminal terminal) {
        if (terminal.normalComplete()) {
            sink.next(ModelStreamEvent.completed(terminal.finishReason()));
            sink.complete();
            return;
        }
        // 提前 EOF：只发 interrupted error，不发 completed
        sink.next(ModelStreamEvent.streamInterrupted());
        sink.complete();
    }

    /** SSE 终态判定结果：正常 completed 或中断。 */
    static final class SseTerminal {
        private final boolean receivedDone;
        private final String finishReason;

        private SseTerminal(boolean receivedDone, String finishReason) {
            this.receivedDone = receivedDone;
            this.finishReason = finishReason;
        }

        static SseTerminal of(boolean receivedDone, String finishReason) {
            String normalized = finishReason == null || finishReason.isBlank() ? null : finishReason.trim();
            return new SseTerminal(receivedDone, normalized);
        }

        boolean receivedDone() {
            return receivedDone;
        }

        String finishReason() {
            return finishReason;
        }

        boolean hasFinishReason() {
            return finishReason != null && !finishReason.isBlank();
        }

        /** [DONE] 或非空 finish_reason 视为兼容正常终态。 */
        boolean normalComplete() {
            return receivedDone || hasFinishReason();
        }
    }

    public EmbeddingResponse embed(ModelInstanceRuntime runtime, List<String> texts) {
        ObjectNode body = buildEmbeddingBody(runtime, texts);
        HttpRequest httpRequest = requestBuilder(runtime, resolvePath(runtime, "embeddingPath", ModelApiUrl.DEFAULT_EMBEDDING_PATH))
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
        ObjectNode body = buildRerankBody(runtime, request);
        String path = resolvePath(runtime, "rerankPath", ModelApiUrl.DEFAULT_RERANK_PATH);
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

    /**
     * package-visible for unit tests：先写 model/stream/messages，再合并并校验 options，最后写 tools。
     */
    ObjectNode buildChatBody(ModelInstanceRuntime runtime, ChatRequest request, boolean stream) {
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
        Map<String, Object> opts = resolveChatOptions(runtime, request);
        opts.forEach((key, value) -> root.set(key, objectMapper.valueToTree(value)));
        if (stream && !root.has("stream_options")) {
            // 请求流末 usage，便于空正文诊断（供应商不支持时会忽略）
            ObjectNode streamOptions = root.putObject("stream_options");
            streamOptions.put("include_usage", true);
        }
        if (request.getTools() != null) root.set("tools", request.getTools());
        if (request.getToolChoice() != null) root.set("tool_choice", request.getToolChoice());
        return root;
    }

    /** package-visible：合并 default + request options 并 assertAllowed。 */
    Map<String, Object> resolveChatOptions(ModelInstanceRuntime runtime, ChatRequest request) {
        Map<String, Object> opts = ModelProtectedOptions.mergeOptions(
                runtime == null ? null : runtime.getDefaultOptions(),
                request == null ? null : request.getOptions());
        ModelProtectedOptions.assertAllowed(ModelType.LLM, opts);
        return opts;
    }

    /** package-visible for unit tests. */
    ObjectNode buildEmbeddingBody(ModelInstanceRuntime runtime, List<String> texts) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", runtime.getModelName());
        ArrayNode input = body.putArray("input");
        if (texts != null) {
            for (String text : texts) {
                input.add(text == null ? "" : text);
            }
        }
        Map<String, Object> opts = runtime.getDefaultOptions() == null
                ? Map.of()
                : new LinkedHashMap<>(runtime.getDefaultOptions());
        ModelProtectedOptions.assertAllowed(ModelType.EMBEDDING, opts);
        opts.forEach((key, value) -> body.set(key, objectMapper.valueToTree(value)));
        return body;
    }

    /** package-visible for unit tests. */
    ObjectNode buildRerankBody(ModelInstanceRuntime runtime, RerankRequest request) {
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
        Map<String, Object> options = ModelProtectedOptions.mergeOptions(
                runtime.getDefaultOptions(),
                request.getOptions());
        ModelProtectedOptions.assertAllowed(ModelType.RERANKER, options);
        options.forEach((key, value) -> body.set(key, objectMapper.valueToTree(value)));
        return body;
    }

    /**
     * 用实例连接配置调用供应商 {@code GET {baseUrl}/models}，列出当前凭证可用的模型。
     */
    public List<UpstreamModel> listModels(ModelInstanceRuntime runtime) {
        HttpRequest httpRequest = requestBuilder(runtime, ModelApiUrl.DEFAULT_MODELS_PATH)
                .timeout(MODEL_LIST_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = response.body() == null ? new byte[0] : response.body();
            if (bytes.length > MODEL_LIST_MAX_BYTES) {
                throw new BizException(502, "Model list response is too large");
            }
            String body = new String(bytes, StandardCharsets.UTF_8);
            ensureSuccess(response.statusCode(), body, runtime);
            return UpstreamModelListParser.parse(objectMapper.readTree(body));
        } catch (BizException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw new BizException(504, "Model list request timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(502, "Model list request interrupted");
        } catch (IllegalArgumentException e) {
            throw new BizException(502, "供应商未返回可识别的模型列表：" + e.getMessage());
        } catch (Exception e) {
            throw new BizException(502, "Model list request failed: " + e.getClass().getSimpleName());
        }
    }

    static String joinApiUrl(String apiRoot, String relativePath) {
        return ModelApiUrl.join(apiRoot, relativePath);
    }

    private HttpRequest.Builder requestBuilder(ModelInstanceRuntime runtime, String path) {
        String baseUrl = stringConnection(runtime, "baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BizException(400, "OpenAI-compatible model requires connectionConfig.baseUrl");
        }
        String apiKey = stringConnection(runtime, "apiKey");
        String authHeader = stringConnection(runtime, "authHeader");
        String authPrefix = stringConnection(runtime, "authPrefix");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(ModelApiUrl.join(baseUrl, path)))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header(authHeader == null || authHeader.isBlank() ? "Authorization" : authHeader,
                    authPrefix == null ? "Bearer " + apiKey : authPrefix + apiKey);
        }
        return builder;
    }

    private String resolvePath(ModelInstanceRuntime runtime, String key, String defaultPath) {
        String path = stringConnection(runtime, key);
        if (path == null || path.isBlank()) {
            return defaultPath;
        }
        return path.trim();
    }

    private String stringConnection(ModelInstanceRuntime runtime, String key) {
        if (runtime.getConnectionConfig() == null) {
            return null;
        }
        Object value = runtime.getConnectionConfig().get(key);
        return value == null ? null : String.valueOf(value);
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

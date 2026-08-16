package com.enterprise.ai.runtime.client.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * 真正流式读取 Model Service /model/chat/stream/events，避免 Feign 缓冲整包。
 * 使用 sendAsync，使响应头返回前即可通过 Future.cancel 取消。
 */
@Component
public class RuntimeModelStreamHttpClient {

    private final ObjectMapper objectMapper;
    private final String modelServiceBaseUrl;
    private final HttpClient httpClient;

    @Autowired
    public RuntimeModelStreamHttpClient(
            ObjectMapper objectMapper,
            @Value("${services.model-service.url:http://localhost:18601}") String modelServiceBaseUrl) {
        this(objectMapper, modelServiceBaseUrl, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build());
    }

    /** 包级可见：单测注入 fake HttpClient。 */
    RuntimeModelStreamHttpClient(ObjectMapper objectMapper, String modelServiceBaseUrl, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.modelServiceBaseUrl = modelServiceBaseUrl.endsWith("/")
                ? modelServiceBaseUrl.substring(0, modelServiceBaseUrl.length() - 1)
                : modelServiceBaseUrl;
        this.httpClient = httpClient;
    }

    /**
     * 兼容入口：单测常 override 本方法。生产路径走三参数版本并传入取消句柄。
     */
    public void streamChatEvents(RuntimeModelServiceClient.ModelChatRequest request,
                                 Consumer<ModelStreamEventDto> onEvent) {
        doStreamChatEvents(request, onEvent, null);
    }

    public void streamChatEvents(RuntimeModelServiceClient.ModelChatRequest request,
                                 Consumer<ModelStreamEventDto> onEvent,
                                 ModelStreamSubscription subscription) {
        // 若子类只 override 了两参数版本（历史单测），仍能接到事件；取消句柄在包装层生效
        Consumer<ModelStreamEventDto> guarded = event -> {
            if (subscription != null && (subscription.isCancelled() || subscription.isTransportClosed())) {
                return;
            }
            onEvent.accept(event);
        };
        if (getClass() != RuntimeModelStreamHttpClient.class) {
            // 匿名/子类测试覆盖两参数时：先挂上可关闭资源再委托
            if (subscription != null) {
                subscription.attach(() -> {
                });
            }
            streamChatEvents(request, guarded);
            return;
        }
        doStreamChatEvents(request, guarded, subscription);
    }

    private void doStreamChatEvents(RuntimeModelServiceClient.ModelChatRequest request,
                                    Consumer<ModelStreamEventDto> onEvent,
                                    ModelStreamSubscription subscription) {
        InputStream bodyStream = null;
        try {
            if (subscription != null && (subscription.isCancelled() || subscription.isTransportClosed())) {
                return;
            }
            String body = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(modelServiceBaseUrl + "/model/chat/stream/events"))
                    .timeout(Duration.ofMinutes(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            CompletableFuture<HttpResponse<InputStream>> future =
                    httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (subscription != null) {
                subscription.attach(() -> future.cancel(true));
                if (subscription.isCancelled() || subscription.isTransportClosed()) {
                    future.cancel(true);
                    return;
                }
            }

            HttpResponse<InputStream> response;
            try {
                response = future.join();
            } catch (CompletionException | CancellationException ex) {
                if (subscription != null && (subscription.isCancelled() || subscription.isTransportClosed())) {
                    return;
                }
                Throwable cause = ex instanceof CompletionException && ex.getCause() != null
                        ? ex.getCause() : ex;
                if (cause instanceof CancellationException
                        || Thread.currentThread().isInterrupted()) {
                    if (subscription != null) {
                        subscription.cancel();
                    }
                    return;
                }
                throw new IllegalStateException("Model stream client failed: " + cause.getMessage(), cause);
            }

            if (subscription != null && (subscription.isCancelled() || subscription.isTransportClosed())) {
                closeQuietly(response.body());
                return;
            }

            bodyStream = response.body();
            if (subscription != null) {
                InputStream attached = bodyStream;
                subscription.attach(() -> closeQuietly(attached));
                if (subscription.isCancelled() || subscription.isTransportClosed()) {
                    return;
                }
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody = new String(bodyStream.readAllBytes(), StandardCharsets.UTF_8);
                bodyStream = null;
                throw new IllegalStateException("Model stream failed: HTTP " + response.statusCode() + " " + errorBody);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(bodyStream, StandardCharsets.UTF_8))) {
                bodyStream = null; // owned by reader
                String line;
                StringBuilder data = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    if (subscription != null && (subscription.isCancelled() || subscription.isTransportClosed())) {
                        return;
                    }
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            if (subscription != null && (subscription.isCancelled() || subscription.isTransportClosed())) {
                                return;
                            }
                            onEvent.accept(parseEvent(data.toString()));
                            data.setLength(0);
                        }
                        continue;
                    }
                    // SSE comment / heartbeat：忽略，不进入事件消费与 fallback 判定
                    if (line.startsWith(":")) {
                        continue;
                    }
                    if (line.startsWith("data:")) {
                        if (data.length() > 0) data.append('\n');
                        data.append(line.substring(5).replaceFirst("^ ", ""));
                    }
                }
                if (data.length() > 0) {
                    if (subscription != null && (subscription.isCancelled() || subscription.isTransportClosed())) {
                        return;
                    }
                    onEvent.accept(parseEvent(data.toString()));
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (subscription != null && (subscription.isCancelled() || subscription.isTransportClosed())) {
                return;
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                if (subscription != null) {
                    subscription.cancel();
                }
                return;
            }
            throw new IllegalStateException("Model stream client failed: " + e.getMessage(), e);
        } finally {
            if (bodyStream != null) {
                closeQuietly(bodyStream);
            }
        }
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Exception ignored) {
        }
    }

    private ModelStreamEventDto parseEvent(String raw) throws Exception {
        JsonNode node = objectMapper.readTree(raw);
        ModelStreamEventDto dto = new ModelStreamEventDto();
        dto.type = text(node, "type");
        dto.text = text(node, "text");
        dto.message = text(node, "message");
        dto.code = text(node, "code");
        dto.finishReason = text(node, "finishReason");
        if (node.has("toolCall") && !node.get("toolCall").isNull()) {
            JsonNode tc = node.get("toolCall");
            dto.toolCall = new ToolCallDeltaDto();
            dto.toolCall.index = tc.has("index") && !tc.get("index").isNull() ? tc.get("index").asInt() : null;
            dto.toolCall.id = text(tc, "id");
            dto.toolCall.type = text(tc, "type");
            dto.toolCall.name = text(tc, "name");
            dto.toolCall.arguments = text(tc, "arguments");
        }
        if (node.has("usage") && !node.get("usage").isNull()) {
            JsonNode usage = node.get("usage");
            java.util.LinkedHashMap<String, Integer> usageMap = new java.util.LinkedHashMap<>();
            usageMap.put("promptTokens", usage.path("promptTokens").asInt(0));
            usageMap.put("completionTokens", usage.path("completionTokens").asInt(0));
            usageMap.put("totalTokens", usage.path("totalTokens").asInt(0));
            if (usage.has("reasoningTokens") && !usage.get("reasoningTokens").isNull()) {
                usageMap.put("reasoningTokens", usage.get("reasoningTokens").asInt());
            }
            dto.usage = usageMap;
        }
        dto.raw = node;
        return dto;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    public static final class ModelStreamEventDto {
        public String type;
        public String text;
        public String message;
        public String code;
        public String finishReason;
        public ToolCallDeltaDto toolCall;
        public Map<String, Integer> usage;
        public JsonNode raw;
    }

    public static final class ToolCallDeltaDto {
        public Integer index;
        public String id;
        public String type;
        public String name;
        public String arguments;
    }

    /** 测试辅助：收集全部事件。 */
    public List<ModelStreamEventDto> collect(RuntimeModelServiceClient.ModelChatRequest request) {
        List<ModelStreamEventDto> events = new ArrayList<>();
        streamChatEvents(request, events::add);
        return events;
    }
}

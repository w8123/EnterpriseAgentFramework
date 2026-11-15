package com.enterprise.ai.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Outbound JSON calls to external knowledge bases. Self-hosted RAGFlow / Dify usually live on the same intranet,
 * so private addresses are allowed by default ({@code reachai.knowledge.external.allow-private-network}); link-local
 * and cloud metadata addresses never are. Redirects are not followed and responses are capped.
 */
@Component
public class ExternalKnowledgeHttp {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client;
    private final Duration requestTimeout;
    private final boolean allowPrivateNetwork;

    public ExternalKnowledgeHttp(
            @Value("${reachai.knowledge.external.request-timeout-seconds:20}") long timeoutSeconds,
            @Value("${reachai.knowledge.external.allow-private-network:true}") boolean allowPrivateNetwork) {
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.requestTimeout = Duration.ofSeconds(Math.max(3, Math.min(120, timeoutSeconds)));
        this.allowPrivateNetwork = allowPrivateNetwork;
    }

    public JsonNode get(String url, Map<String, String> headers) {
        return send(url, "GET", null, headers);
    }

    public JsonNode post(String url, Object body, Map<String, String> headers) {
        return send(url, "POST", body, headers);
    }

    private JsonNode send(String url, String method, Object body, Map<String, String> headers) {
        URI uri = checkedUri(url);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                .header("Accept", "application/json");
        headers.forEach(builder::header);
        try {
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
            } else {
                builder.GET();
            }
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream in = response.body()) {
                bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (bytes.length > MAX_RESPONSE_BYTES) throw new ExternalKnowledgeException("外部知识库返回内容过大");
            int status = response.statusCode();
            if (status == 401 || status == 403) throw new ExternalKnowledgeException("外部知识库拒绝访问（HTTP " + status + "），请检查密钥");
            if (status == 404) throw new ExternalKnowledgeException("外部知识库地址不对（HTTP 404），请检查服务地址");
            if (status >= 300 && status < 400) throw new ExternalKnowledgeException("外部知识库地址发生了跳转（HTTP " + status + "），请填写最终地址");
            if (status >= 400) throw new ExternalKnowledgeException("外部知识库返回错误（HTTP " + status + "）" + brief(bytes));
            if (bytes.length == 0) return mapper.createObjectNode();
            try {
                return mapper.readTree(bytes);
            } catch (IOException notJson) {
                throw new ExternalKnowledgeException("外部知识库返回的不是 JSON，请检查服务地址");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ExternalKnowledgeException("调用外部知识库被中断");
        } catch (java.net.http.HttpTimeoutException timeout) {
            throw new ExternalKnowledgeException("外部知识库响应超时");
        } catch (IOException io) {
            throw new ExternalKnowledgeException("连不上外部知识库：" + uri.getHost());
        }
    }

    /** Validates scheme and host, and refuses link-local / metadata (and private, when configured) targets. */
    URI checkedUri(String url) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException invalid) {
            throw new ExternalKnowledgeException("服务地址格式不对");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw new ExternalKnowledgeException("服务地址必须是 http:// 或 https:// 开头的完整地址");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException unknown) {
            throw new ExternalKnowledgeException("找不到服务地址：" + uri.getHost());
        }
        for (InetAddress address : addresses) {
            if (address.isLinkLocalAddress() || address.isAnyLocalAddress() || address.isMulticastAddress()) {
                throw new ExternalKnowledgeException("不允许访问这个地址：" + uri.getHost());
            }
            if (!allowPrivateNetwork && (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || isUniqueLocal(address))) {
                throw new ExternalKnowledgeException("当前配置不允许访问内网地址：" + uri.getHost());
            }
        }
        return uri;
    }

    private static boolean isUniqueLocal(InetAddress address) {
        byte[] raw = address.getAddress();
        return raw.length == 16 && (raw[0] & 0xfe) == 0xfc;
    }

    private static String brief(byte[] body) {
        if (body == null || body.length == 0) return "";
        String text = new String(body, 0, Math.min(body.length, 200), java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? "" : "：" + text;
    }

    /** Joins a configured base address and an API path without doubling or dropping slashes. */
    public static String join(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + (path.startsWith("/") ? path : "/" + path);
    }
}

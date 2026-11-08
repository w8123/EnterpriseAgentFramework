package com.enterprise.ai.control.platform.sso;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** The two server-to-server calls of the authorization code flow. Separate so tests can stub them. */
@Component
public class BusinessSsoHttp {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper objectMapper;

    public BusinessSsoHttp(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> postForm(String url, Map<String, String> form, Map<String, String> headers) {
        String body = form.entrySet().stream().filter(entry -> entry.getValue() != null)
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return send(request.build());
    }

    public Map<String, Object> getJson(String url, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Accept", "application/json").GET();
        headers.forEach(request::header);
        return send(request.build());
    }

    private Map<String, Object> send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new BusinessSsoException("连不上业务系统（" + request.uri().getHost() + "）");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BusinessSsoException("登录被中断，请重试");
        }
        if (response.statusCode() >= 400) {
            throw new BusinessSsoException("业务系统拒绝了登录请求（HTTP " + response.statusCode() + "）");
        }
        try {
            return objectMapper.readValue(response.body(), new TypeReference<>() { });
        } catch (IOException failure) {
            throw new BusinessSsoException("业务系统返回的不是 JSON，请检查认证源里的地址");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

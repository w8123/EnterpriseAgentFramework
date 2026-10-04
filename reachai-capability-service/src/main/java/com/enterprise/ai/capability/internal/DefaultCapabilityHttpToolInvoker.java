package com.enterprise.ai.capability.internal;

import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class DefaultCapabilityHttpToolInvoker implements CapabilityHttpToolInvoker {

    private static final Pattern SAFE_IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,200}");
    private static final Pattern TRACEPARENT = Pattern.compile(
            "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    private static final long MAX_OUTBOUND_TIMEOUT_MS = 600_000L;

    private final RestTemplateBuilder restTemplateBuilder;
    private final CapabilityOutboundTransportPolicy transportPolicy;
    private final ObjectMapper responseMapper;

    @Override
    public Map<String, Object> invoke(CapabilityHttpToolInvocation invocation) {
        transportPolicy.requireAllowed(invocation.url());
        RestTemplateBuilder requestBuilder = restTemplateBuilder;
        Long remainingMs = remainingMillis(invocation.metadata(), System.currentTimeMillis());
        if (remainingMs != null) {
            Duration timeout = Duration.ofMillis(remainingMs);
            requestBuilder = requestBuilder
                    .connectTimeout(timeout)
                    .readTimeout(timeout);
        }
        RestTemplate restTemplate = requestBuilder.build();
        HttpMethod method = HttpMethod.valueOf(invocation.method());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Object configuredHeaders = invocation.metadata() == null ? null : invocation.metadata().get("headers");
        if (configuredHeaders instanceof Map<?, ?> headerMap) {
            headerMap.forEach((key, value) -> {
                if (key != null && value != null) headers.set(String.valueOf(key), String.valueOf(value));
            });
        }
        String idempotencyKey = safeText(
                invocation.metadata() == null ? null : invocation.metadata().get("idempotencyKey"));
        if (idempotencyKey != null && SAFE_IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        Object rawTraceContext = invocation.metadata() == null
                ? null : invocation.metadata().get("traceContext");
        if (rawTraceContext instanceof Map<?, ?> traceContext) {
            String traceparent = safeText(traceContext.get("traceparent"));
            if (traceparent != null && TRACEPARENT.matcher(traceparent).matches()) {
                headers.set("traceparent", traceparent);
            }
        }
        HttpEntity<?> entity = method == HttpMethod.GET
                ? new HttpEntity<>(headers)
                : new HttpEntity<>(invocation.body(), headers);
        // Some valid SDK handlers return a scalar value. Depending on Spring MVC's selected
        // message converter, that value can arrive as unquoted text despite an application/json
        // content type. Read bytes first so a scalar response remains callable instead of being
        // rejected before Capability can apply its normal result classification.
        ResponseEntity<byte[]> response = restTemplate.exchange(invocation.url(), method, entity, byte[].class);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("statusCode", response.getStatusCode().value());
        body.put("body", decodeResponse(response.getBody(), response.getHeaders().getContentType()));
        return body;
    }

    private Object decodeResponse(byte[] response, MediaType contentType) {
        if (response == null || response.length == 0) {
            return null;
        }
        if (isJson(contentType)) {
            try {
                return responseMapper.readValue(response, Object.class);
            } catch (Exception malformedJson) {
                // Some valid SDK handlers emit an unquoted scalar while MVC labels it JSON.
                // Preserve that legacy endpoint behavior without treating text/* as JSON.
                return decodeText(response, contentType);
            }
        }
        if (isText(contentType)) {
            return decodeText(response, contentType);
        }
        return response;
    }

    private static boolean isJson(MediaType contentType) {
        if (contentType == null || !"application".equalsIgnoreCase(contentType.getType())) {
            return false;
        }
        String subtype = contentType.getSubtype();
        return "json".equalsIgnoreCase(subtype)
                || subtype.toLowerCase(Locale.ROOT).endsWith("+json");
    }

    private static boolean isText(MediaType contentType) {
        return contentType != null && "text".equalsIgnoreCase(contentType.getType());
    }

    private static String decodeText(byte[] response, MediaType contentType) {
        Charset charset = contentType == null ? null : contentType.getCharset();
        return new String(response, charset == null ? StandardCharsets.UTF_8 : charset);
    }

    static Long remainingMillis(Map<String, Object> metadata, long nowEpochMs) {
        Object raw = metadata == null ? null : metadata.get("deadlineEpochMs");
        if (raw == null) return null;
        long deadline;
        try {
            deadline = raw instanceof Number number
                    ? number.longValue()
                    : Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Capability invocation deadline is invalid");
        }
        long remaining = deadline - nowEpochMs;
        if (remaining <= 0L) {
            throw new IllegalStateException("Capability invocation deadline has expired");
        }
        return Math.max(1L, Math.min(MAX_OUTBOUND_TIMEOUT_MS, remaining));
    }

    private static String safeText(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) return null;
        return text;
    }
}

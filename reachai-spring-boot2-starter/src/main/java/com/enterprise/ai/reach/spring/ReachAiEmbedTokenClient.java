package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.auth.ReachAiSignatureHeaders;
import com.enterprise.ai.reach.sdk.auth.ReachAiSigner;
import com.enterprise.ai.reach.sdk.client.ReachAiClientConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-side client for exchanging a business identity for a short-lived
 * ReachAI Embed Token.
 *
 * <p>This client deliberately does not resolve the current business user. That
 * domain-specific authorization boundary belongs to the host application.</p>
 */
public class ReachAiEmbedTokenClient {

    private static final String TOKEN_EXCHANGE_PATH = "/api/embed/token/exchange";

    private final ReachAiRegistryProperties properties;
    private final ReachAiRegistryTransport transport;
    private final ObjectMapper objectMapper;

    public ReachAiEmbedTokenClient(
            ReachAiRegistryProperties properties,
            ReachAiRegistryTransport transport) {
        this(properties, transport, new ObjectMapper());
    }

    ReachAiEmbedTokenClient(
            ReachAiRegistryProperties properties,
            ReachAiRegistryTransport transport,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    public ReachAiEmbedTokenResult exchange(ReachAiEmbedTokenRequest request) {
        validateConfiguration();
        validateRequest(request);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("projectCode", properties.getProject().getCode().trim());
        body.put("agentId", request.getAgentId().trim());
        body.put("pageKey", trimToNull(request.getPageKey()));
        body.put("pageInstanceId", request.getPageInstanceId().trim());
        body.put("route", trimToNull(request.getRoute()));
        body.put("origin", request.getOrigin().trim());
        body.put("principal", request.getPrincipal());

        String response;
        try {
            response = transport.exchange(
                    "POST",
                    trimTrailingSlash(properties.getRegistry().getUrl())
                            + TOKEN_EXCHANGE_PATH,
                    signedHeaders(),
                    body);
        } catch (IOException ex) {
            // Do not propagate a transport body: it may contain credentials or
            // a short-lived token supplied by a non-conforming upstream.
            throw new IllegalStateException(
                    "ReachAI Embed Token exchange request failed");
        }
        return parseResponse(response);
    }

    private ReachAiEmbedTokenResult parseResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException(
                        "ReachAI Embed Token exchange returned invalid JSON");
            }
            JsonNode code = root.get("code");
            if (code != null && !isSuccessCode(code)) {
                throw new IllegalStateException(
                        "ReachAI Embed Token exchange was rejected");
            }
            JsonNode payload = root.path("data");
            if (!payload.isObject()) {
                payload = root;
            }
            String token = text(payload.get("token"));
            if (token == null) {
                throw new IllegalStateException(
                        "ReachAI Embed Token exchange returned no token");
            }
            long expiresIn = payload.path("expiresIn").asLong(0L);
            if (expiresIn <= 0L) {
                throw new IllegalStateException(
                        "ReachAI Embed Token exchange returned invalid expiry");
            }
            return new ReachAiEmbedTokenResult(
                    token,
                    expiresIn,
                    readSessionHint(payload.path("sessionHint")));
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "ReachAI Embed Token exchange returned invalid JSON");
        }
    }

    private Map<String, String> readSessionHint(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (node == null || !node.isObject()) {
            return result;
        }
        node.fields().forEachRemaining(entry -> {
            String value = text(entry.getValue());
            if (value != null) {
                result.put(entry.getKey(), value);
            }
        });
        return result;
    }

    private Map<String, String> signedHeaders() {
        ReachAiSignatureHeaders headers = ReachAiSigner.sign(
                ReachAiClientConfig.builder()
                        .endpoint(properties.getRegistry().getUrl())
                        .projectCode(properties.getProject().getCode())
                        .projectName(properties.getProject().getName())
                        .appKey(properties.getRegistry().getAppKey())
                        .appSecret(properties.getRegistry().getAppSecret())
                        .build());
        return headers.toHttpHeaders();
    }

    private void validateConfiguration() {
        if (properties == null
                || properties.getRegistry() == null
                || properties.getProject() == null
                || !hasText(properties.getRegistry().getUrl())
                || !hasText(properties.getRegistry().getAppKey())
                || !hasText(properties.getRegistry().getAppSecret())
                || !hasText(properties.getProject().getCode())) {
            throw new IllegalStateException(
                    "ReachAI registry configuration is incomplete");
        }
    }

    private void validateRequest(ReachAiEmbedTokenRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Embed Token request is required");
        }
        requireText(request.getAgentId(), "agentId");
        requireText(request.getPageInstanceId(), "pageInstanceId");
        requireText(request.getOrigin(), "origin");
        if (request.getPrincipal() == null) {
            throw new IllegalArgumentException("principal is required");
        }
        requireText(
                request.getPrincipal().getExternalUserId(),
                "principal.externalUserId");
    }

    private static boolean isSuccessCode(JsonNode code) {
        return (code.isNumber() && code.asInt() == 200)
                || (code.isTextual() && "200".equals(code.asText().trim()));
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return hasText(value) ? value.trim() : null;
    }

    private static String trimTrailingSlash(String value) {
        String result = requireText(value, "reachai.registry.url");
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static String requireText(String value, String field) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

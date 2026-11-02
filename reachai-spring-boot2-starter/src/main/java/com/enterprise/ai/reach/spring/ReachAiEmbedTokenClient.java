package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.auth.ReachAiSignatureHeaders;
import com.enterprise.ai.reach.sdk.auth.ReachAiProjectRequestHeaders;
import com.enterprise.ai.reach.sdk.auth.ReachAiProjectRequestSigner;
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
    private static final String SESSION_REVOKE_PATH = "/api/embed/token/business-session/revoke";

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
        body.put("businessSessionId", request.getPrincipal().getBusinessSessionId());
        body.put("businessSessionExpiresAt", request.getPrincipal().getBusinessSessionExpiresAt());

        String response;
        try {
            byte[] payload = objectMapper.writeValueAsBytes(body);
            response = transport.exchange(
                    "POST",
                    trimTrailingSlash(properties.getRegistry().getUrl())
                            + TOKEN_EXCHANGE_PATH,
                    signedHeaders(payload, TOKEN_EXCHANGE_PATH),
                    payload);
        } catch (ReachAiRegistryHttpException ex) {
            if (ex.getStatus() == 401 || ex.getStatus() == 403) {
                throw new ReachAiEmbedAccessDeniedException();
            }
            throw new IllegalStateException("ReachAI Embed Token exchange request failed");
        } catch (IOException ex) {
            // Do not propagate a transport body: it may contain credentials or
            // a short-lived token supplied by a non-conforming upstream.
            throw new IllegalStateException(
                    "ReachAI Embed Token exchange request failed");
        }
        return parseResponse(response, request.getPrincipal());
    }

    /** Notify ReachAI after a host login session is invalidated. Retry failures until acknowledged. */
    public void revokeBusinessSession(String businessSessionId) {
        validateConfiguration();
        requireText(businessSessionId, "businessSessionId");
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("projectCode", properties.getProject().getCode().trim());
        body.put("businessSessionId", businessSessionId);
        try {
            byte[] payload = objectMapper.writeValueAsBytes(body);
            String response = transport.exchange("POST",
                    trimTrailingSlash(properties.getRegistry().getUrl()) + SESSION_REVOKE_PATH,
                    signedHeaders(payload, SESSION_REVOKE_PATH), payload);
            JsonNode root = objectMapper.readTree(response);
            if (root == null || !root.isObject() || !isSuccessCode(root.get("code"))) {
                throw new IllegalStateException("ReachAI business session revocation was rejected");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("ReachAI business session revocation request failed");
        }
    }

    private ReachAiEmbedTokenResult parseResponse(String response, ReachAiEmbedPrincipal principal) {
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
            if (principal.getBusinessSessionId() != null
                    && !payload.path("businessSessionBound").asBoolean(false)) {
                throw new IllegalStateException("ReachAI did not confirm business session binding");
            }
            if (principal.getBusinessSessionExpiresAt() != null
                    && !payload.path("businessSessionExpiryApplied").asBoolean(false)) {
                throw new IllegalStateException("ReachAI did not confirm business session expiry");
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

    private Map<String, String> signedHeaders(byte[] payload, String path) {
        ReachAiSignatureHeaders headers = ReachAiSigner.sign(
                ReachAiClientConfig.builder()
                        .endpoint(properties.getRegistry().getUrl())
                        .projectCode(properties.getProject().getCode())
                        .projectName(properties.getProject().getName())
                        .appKey(properties.getRegistry().getAppKey())
                        .appSecret(properties.getRegistry().getAppSecret())
                        .build());
        ReachAiProjectRequestHeaders bodySignature = ReachAiProjectRequestSigner.sign(
                properties.getRegistry().getAppKey(), properties.getRegistry().getAppSecret(),
                properties.getProject().getCode(), "POST", path, payload,
                headers.getTimestamp(), headers.getNonce());
        Map<String, String> result = headers.toHttpHeaders();
        result.put(ReachAiProjectRequestHeaders.BODY_SHA256, bodySignature.getBodySha256());
        result.put("X-ReachAI-Body-Signature", bodySignature.getSignature());
        return result;
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
        return code != null && ((code.isNumber() && code.asInt() == 200)
                || (code.isTextual() && "200".equals(code.asText().trim())));
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

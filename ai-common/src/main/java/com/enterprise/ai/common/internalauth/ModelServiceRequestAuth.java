package com.enterprise.ai.common.internalauth;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Exact-byte service authentication for the Model gateway; includes the raw query string. */
public final class ModelServiceRequestAuth {
    public static final String CALLER_KNOWLEDGE = "reachai-knowledge-service";
    private static final String SOURCE = "MODEL_SERVICE";

    private ModelServiceRequestAuth() { }

    public static Map<String, String> sign(String secret, String caller, String method, String target, byte[] body) {
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String digest = InternalServiceHmac.bodySha256Hex(body);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(InternalServiceAuthHeaders.CALLER, caller);
        headers.put(InternalServiceAuthHeaders.TIMESTAMP, timestamp);
        headers.put(InternalServiceAuthHeaders.NONCE, nonce);
        headers.put(InternalServiceAuthHeaders.BODY_SHA256, digest);
        headers.put(InternalServiceAuthHeaders.SIGNATURE,
                InternalServiceHmac.sign(secret == null ? "" : secret.trim(),
                        canonical(method, target, caller, timestamp, nonce, digest)));
        return headers;
    }

    public static String canonical(String method, String target, String caller,
                                   String timestamp, String nonce, String digest) {
        return InternalServiceHmac.canonical(method, requestTarget(target), caller, SOURCE, caller,
                timestamp, nonce, digest);
    }

    public static String requestTarget(String target) {
        URI uri = URI.create(target);
        return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    /** Feign's target may carry a configured path prefix that is absent from template.url(). */
    public static String outboundTarget(String baseUrl, String target) {
        if (target.startsWith("http://") || target.startsWith("https://") || baseUrl == null) return target;
        return baseUrl.replaceAll("/+$", "") + (target.startsWith("/") ? "" : "/") + target;
    }
}

package com.enterprise.ai.reach.sdk.auth;

import java.util.LinkedHashMap;
import java.util.Map;

/** Headers for the body-bound, project-scoped ReachAI public request protocol. */
public final class ReachAiProjectRequestHeaders {

    public static final String APP_KEY = "X-ReachAI-App-Key";
    public static final String TIMESTAMP = "X-ReachAI-Timestamp";
    public static final String NONCE = "X-ReachAI-Nonce";
    public static final String BODY_SHA256 = "X-ReachAI-Body-SHA256";
    public static final String SIGNATURE = "X-ReachAI-Signature";

    private final String appKey;
    private final String timestamp;
    private final String nonce;
    private final String bodySha256;
    private final String signature;

    public ReachAiProjectRequestHeaders(String appKey,
                                        String timestamp,
                                        String nonce,
                                        String bodySha256,
                                        String signature) {
        this.appKey = appKey;
        this.timestamp = timestamp;
        this.nonce = nonce;
        this.bodySha256 = bodySha256;
        this.signature = signature;
    }

    public String getAppKey() {
        return appKey;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getNonce() {
        return nonce;
    }

    public String getBodySha256() {
        return bodySha256;
    }

    public String getSignature() {
        return signature;
    }

    public Map<String, String> toHttpHeaders() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put(APP_KEY, appKey);
        headers.put(TIMESTAMP, timestamp);
        headers.put(NONCE, nonce);
        headers.put(BODY_SHA256, bodySha256);
        headers.put(SIGNATURE, signature);
        return headers;
    }
}

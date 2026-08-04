package com.enterprise.ai.reach.spring;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Browser-safe subset of a successful ReachAI Embed Token exchange.
 */
public class ReachAiEmbedTokenResult {

    private final String token;
    private final long expiresIn;
    private final Map<String, String> sessionHint;

    public ReachAiEmbedTokenResult(
            String token,
            long expiresIn,
            Map<String, String> sessionHint) {
        this.token = token;
        this.expiresIn = expiresIn;
        this.sessionHint = sessionHint == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(sessionHint));
    }

    public String getToken() {
        return token;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public Map<String, String> getSessionHint() {
        return sessionHint;
    }
}

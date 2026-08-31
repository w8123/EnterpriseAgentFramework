package com.enterprise.ai.reach.sdk.client;

/**
 * Immutable SDK client configuration built once and used read-only.
 * Required fields and all non-blank text are normalized (trimmed, with the
 * endpoint's trailing slashes removed) at build time, so getters return
 * already-normalized values.
 */
public class ReachAiClientConfig {

    private final String endpoint;
    private final String projectCode;
    private final String projectName;
    private final String appKey;
    private final String appSecret;

    private ReachAiClientConfig(Builder builder) {
        this.endpoint = normalizeEndpoint(builder.endpoint);
        this.projectCode = requireText(builder.projectCode, "projectCode");
        this.projectName = trimToNull(builder.projectName);
        this.appKey = requireText(builder.appKey, "appKey");
        this.appSecret = requireText(builder.appSecret, "appSecret");
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getProjectCode() {
        return projectCode;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getAppKey() {
        return appKey;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public static class Builder {
        private String endpoint;
        private String projectCode;
        private String projectName;
        private String appKey;
        private String appSecret;

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder projectCode(String projectCode) {
            this.projectCode = projectCode;
            return this;
        }

        public Builder projectName(String projectName) {
            this.projectName = projectName;
            return this;
        }

        public Builder appKey(String appKey) {
            this.appKey = appKey;
            return this;
        }

        public Builder appSecret(String appSecret) {
            this.appSecret = appSecret;
            return this;
        }

        public ReachAiClientConfig build() {
            return new ReachAiClientConfig(this);
        }
    }

    /**
     * Removes trailing slashes so callers can append a fixed API path without
     * producing a repeated separator. It does not validate URL reachability.
     */
    private static String normalizeEndpoint(String endpoint) {
        String text = requireText(endpoint, "endpoint");
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}

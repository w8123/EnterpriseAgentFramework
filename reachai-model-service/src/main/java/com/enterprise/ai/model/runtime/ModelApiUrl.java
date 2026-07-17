package com.enterprise.ai.model.runtime;

import com.enterprise.ai.model.instance.ConnectionConfigSupport;

/**
 * OpenAI 兼容 API root 与相对 path 的拼接（无 /v1 特殊分支）。
 */
public final class ModelApiUrl {

    public static final String DEFAULT_CHAT_PATH = "/chat/completions";
    public static final String DEFAULT_EMBEDDING_PATH = "/embeddings";
    public static final String DEFAULT_RERANK_PATH = "/rerank";

    private ModelApiUrl() {
    }

    /**
     * Join OpenAI-SDK-style API root with relative path. No /v1 special-case.
     */
    public static String join(String apiRoot, String relativePath) {
        if (apiRoot == null || apiRoot.isBlank()) {
            throw new IllegalArgumentException("apiRoot is required");
        }
        String root = apiRoot.trim();
        ConnectionConfigSupport.validateBaseUrl(root);
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        String path = relativePath == null ? "" : relativePath.trim();
        if (path.isEmpty()) {
            return root;
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            throw new IllegalArgumentException("relativePath must be a path, not an absolute URL");
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return root + path;
    }
}

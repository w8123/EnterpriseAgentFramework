package com.enterprise.ai.model.catalog;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

final class ModelCatalogSourcePolicy {

    private static final Set<String> APPROVED_HOSTS = Set.of(
            "developers.openai.com",
            "platform.openai.com",
            "api.openai.com",
            "platform.claude.com",
            "docs.anthropic.com",
            "api.anthropic.com",
            "ai.google.dev",
            "generativelanguage.googleapis.com",
            "api-docs.deepseek.com",
            "api.deepseek.com",
            "help.aliyun.com",
            "dashscope.aliyuncs.com"
    );

    private ModelCatalogSourcePolicy() {
    }

    static URI validate(ModelCatalogSourceEntity source) {
        if (source == null || source.getSourceUrl() == null || source.getSourceUrl().isBlank()) {
            throw new ModelCatalogSyncException(
                    "MODEL_CATALOG_SOURCE_URL_INVALID", "Official source URL is missing", false);
        }
        final URI uri;
        try {
            uri = URI.create(source.getSourceUrl().trim());
        } catch (IllegalArgumentException ex) {
            throw new ModelCatalogSyncException(
                    "MODEL_CATALOG_SOURCE_URL_INVALID", "Official source URL is invalid", false, ex);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String configuredHost = source.getAllowedHost() == null
                ? "" : source.getAllowedHost().trim().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host.isBlank()
                || !host.equals(configuredHost)
                || !APPROVED_HOSTS.contains(host)
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new ModelCatalogSyncException(
                    "MODEL_CATALOG_SOURCE_NOT_ALLOWED",
                    "Official source must use an approved HTTPS host",
                    false);
        }
        return uri;
    }
}

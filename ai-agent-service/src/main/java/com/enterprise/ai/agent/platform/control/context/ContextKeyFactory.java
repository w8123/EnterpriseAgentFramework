package com.enterprise.ai.agent.platform.control.context;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class ContextKeyFactory {

    private ContextKeyFactory() {
    }

    static String newItemKey() {
        return "ctx-item-" + UUID.randomUUID().toString().replace("-", "");
    }

    static String newCandidateKey() {
        return "ctx-candidate-" + UUID.randomUUID().toString().replace("-", "");
    }

    static String buildNamespaceKey(ContextNamespaceRequest request) {
        if (StringUtils.hasText(request.getNamespaceKey())) {
            return request.getNamespaceKey().trim();
        }
        return Stream.of(
                        safe(request.getTenantId()),
                        safe(ContextProjectIdentity.namespaceKeyToken(
                                request.getProjectCode(), request.getProjectId())),
                        safe(request.getNamespaceType()),
                        safe(request.getOwnerType()),
                        safe(request.getOwnerId()))
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(":"));
    }

    private static String safe(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}

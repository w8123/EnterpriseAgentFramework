package com.enterprise.ai.control.platform;

import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Finds the enabled Runtime Agent an embed request names, by id or key slug, within the project. */
final class PlatformEmbedAgentLookup {

    private PlatformEmbedAgentLookup() {
    }

    static Optional<Map<?, ?>> find(Object listAgentsBody, String projectCode, String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return Optional.empty();
        }
        for (Object item : items(listAgentsBody)) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object id = map.get("id") == null ? map.get("keySlug") : map.get("id");
            Object keySlug = map.get("keySlug");
            if ((Objects.equals(String.valueOf(id), agentId) || Objects.equals(String.valueOf(keySlug), agentId))
                    && !Boolean.FALSE.equals(map.get("enabled"))) {
                Object agentProjectCode = map.get("projectCode");
                if (agentProjectCode == null || Objects.equals(projectCode, String.valueOf(agentProjectCode))) {
                    return Optional.of(map);
                }
            }
        }
        return Optional.empty();
    }

    private static Collection<?> items(Object body) {
        if (body instanceof Collection<?> collection) {
            return collection;
        }
        if (body instanceof Map<?, ?> map) {
            for (String key : List.of("data", "records", "items", "agents")) {
                Object value = map.get(key);
                if (value instanceof Collection<?> collection) {
                    return collection;
                }
            }
        }
        return List.of();
    }
}

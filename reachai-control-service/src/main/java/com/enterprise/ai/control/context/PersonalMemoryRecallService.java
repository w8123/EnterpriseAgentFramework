package com.enterprise.ai.control.context;

import com.enterprise.ai.control.context.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.context.PersonalMemoryService.QueryCommand;
import com.enterprise.ai.control.context.PersonalMemoryService.QueryHit;
import com.enterprise.ai.control.context.PersonalMemoryService.QueryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds a bounded private-memory payload for the HMAC-signed Runtime envelope. */
@Service
@Slf4j
public class PersonalMemoryRecallService {

    private static final String SCHEMA = "reachai-personal-memory-context-v1";
    private final PersonalMemoryService memoryService;
    private final int topK;
    private final int maxChars;

    public PersonalMemoryRecallService(
            PersonalMemoryService memoryService,
            @Value("${reachai.context.personal-memory.recall-top-k:8}") int topK,
            @Value("${reachai.context.personal-memory.recall-max-chars:6000}") int maxChars) {
        this.memoryService = memoryService;
        this.topK = Math.max(1, Math.min(topK, 20));
        this.maxChars = Math.max(256, Math.min(maxChars, 32_000));
    }

    public Map<String, Object> recall(String identitySource,
                                      String tenantId,
                                      String runtimeUserId,
                                      Map<String, Object> body) {
        if (!StringUtils.hasText(runtimeUserId)
                || !("AGENT".equalsIgnoreCase(identitySource)
                || "EMBED_SESSION".equalsIgnoreCase(identitySource))
                || explicitlyDisabled(body)) {
            return Map.of();
        }
        String tenant = StringUtils.hasText(tenantId) ? tenantId.trim().toLowerCase(Locale.ROOT) : "default";
        String user = runtimeUserId.trim();
        String query = firstText(stringValue(body, "message"), stringValue(body, "userInput"),
                stringValue(body, "input"), "");
        try {
            QueryResult result = memoryService.query(
                    new PersonalMemoryPrincipal(tenant, user, null, null, null),
                    new QueryCommand(query, null, topK, maxChars,
                            stringValue(body, "sessionId"), stringValue(body, "traceId"),
                            stringValue(body, "agentId")));
            if (result.hits().isEmpty()) {
                return Map.of();
            }
            List<Map<String, Object>> memories = result.hits().stream().map(this::snippet).toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schema", SCHEMA);
            payload.put("memories", memories);
            payload.put("usedChars", result.usedChars());
            payload.put("truncated", result.truncated());
            return Map.copyOf(payload);
        } catch (RuntimeException failure) {
            log.warn("Personal memory recall degraded: source={}, tenant={}, failureType={}",
                    identitySource, tenant, failure.getClass().getSimpleName());
            return Map.of();
        }
    }

    private Map<String, Object> snippet(QueryHit hit) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", hit.id());
        value.put("type", hit.type());
        if (StringUtils.hasText(hit.title())) value.put("title", hit.title());
        value.put("content", hit.content());
        if (StringUtils.hasText(hit.summary())) value.put("summary", hit.summary());
        value.put("trustLevel", hit.trustLevel());
        value.put("score", hit.score());
        return Map.copyOf(value);
    }

    private static boolean explicitlyDisabled(Map<String, Object> body) {
        Object value = body == null ? null : body.get("useMemory");
        return Boolean.FALSE.equals(value) || "false".equalsIgnoreCase(String.valueOf(value));
    }

    private static String stringValue(Map<String, Object> body, String key) {
        Object raw = body == null ? null : body.get(key);
        return raw instanceof String value && StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String firstText(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return "";
    }
}

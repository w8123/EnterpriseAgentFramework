package com.enterprise.ai.control.memory;

import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryService.QueryCommand;
import com.enterprise.ai.control.memory.PersonalMemoryService.QueryHit;
import com.enterprise.ai.control.memory.PersonalMemoryService.QueryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final PersonalMemorySharedQueryService sharedQuery;
    private final int topK;
    private final int maxChars;

    public PersonalMemoryRecallService(
            PersonalMemoryService memoryService,
            @Value("${reachai.context.personal-memory.recall-top-k:8}") int topK,
            @Value("${reachai.context.personal-memory.recall-max-chars:6000}") int maxChars) {
        this(memoryService, null, topK, maxChars);
    }

    @Autowired
    public PersonalMemoryRecallService(PersonalMemoryService memoryService,
            PersonalMemorySharedQueryService sharedQuery,
            @Value("${reachai.context.personal-memory.recall-top-k:8}") int topK,
            @Value("${reachai.context.personal-memory.recall-max-chars:6000}") int maxChars) {
        this.memoryService = memoryService;
        this.sharedQuery = sharedQuery;
        this.topK = Math.max(1, Math.min(topK, 20));
        this.maxChars = Math.max(256, Math.min(maxChars, 32_000));
    }

    public Map<String, Object> recall(String identitySource,
                                      String tenantId,
                                      String runtimeUserId,
                                      Map<String, Object> body) {
        // An embed caller must supply a separately attested business owner, not a bare user ID.
        if (!StringUtils.hasText(runtimeUserId) || !"AGENT".equalsIgnoreCase(identitySource)) return Map.of();
        String tenant = StringUtils.hasText(tenantId) ? tenantId.trim().toLowerCase(Locale.ROOT) : "default";
        return recall(new PersonalMemoryPrincipal(tenant, runtimeUserId.trim(), null, null, null), body);
    }

    public Map<String, Object> recall(PersonalMemoryPrincipal owner, Map<String, Object> body) {
        if (owner == null) return Map.of();
        String tenant = owner.tenantId();
        String query = firstText(stringValue(body, "message"), stringValue(body, "userInput"),
                stringValue(body, "input"), "");
        try {
            QueryCommand command = new QueryCommand(query, null, topK, maxChars,
                    stringValue(body, "sessionId"), stringValue(body, "traceId"),
                    stringValue(body, "agentId"), stringValue(body, "projectCode"));
            List<QueryHit> hits;
            int usedChars;
            boolean truncated;
            if (sharedQuery != null && !PersonalMemoryPrincipal.LEGACY_DOMAIN.equals(owner.ownerDomain())) {
                try {
                    var result = sharedQuery.query(owner, command);
                    if (result.sharingUnavailable()) log.warn("Linked personal memory recall unavailable; using verified results only");
                    hits = result.hits().stream().map(PersonalMemorySharedQueryService.SharedHit::hit).toList();
                    usedChars = result.usedChars();
                    truncated = result.truncated();
                } catch (RuntimeException failure) {
                    log.warn("Linked personal memory recall failed; falling back to the original owner, failureType={}",
                            failure.getClass().getSimpleName());
                    QueryResult own = memoryService.query(owner, command);
                    hits = own.hits();
                    usedChars = own.usedChars();
                    truncated = own.truncated();
                }
            } else {
                QueryResult result = memoryService.query(owner, command);
                hits = result.hits();
                usedChars = result.usedChars();
                truncated = result.truncated();
            }
            if (hits.isEmpty()) {
                return Map.of();
            }
            List<Map<String, Object>> memories = hits.stream().map(this::snippet).toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schema", SCHEMA);
            payload.put("memories", memories);
            payload.put("usedChars", usedChars);
            payload.put("truncated", truncated);
            return Map.copyOf(payload);
        } catch (RuntimeException failure) {
            log.warn("Personal memory recall degraded: source={}, tenant={}, failureType={}",
                    "ATTESTED_OWNER", tenant, failure.getClass().getSimpleName());
            return Map.of();
        }
    }

    private Map<String, Object> snippet(QueryHit hit) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", hit.id());
        value.put("type", hit.type());
        if (StringUtils.hasText(hit.title())) value.put("title", hit.title());
        value.put("content", hit.content());
        // Every stored memory was confirmed by its owner.
        value.put("trustLevel", "VERIFIED");
        value.put("score", hit.score());
        return Map.copyOf(value);
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

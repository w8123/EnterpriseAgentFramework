package com.enterprise.ai.runtime.agent;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** A keyed revision of the persisted editable Agent configuration, not a digest of exposed secrets. */
@Component
final class RuntimeAgentDraftRevision {
    private final ObjectMapper canonical;
    private final String secret;

    RuntimeAgentDraftRevision(ObjectMapper mapper,
            @Value("${reachai.internal.service-secret:${REACHAI_INTERNAL_SERVICE_SECRET:}}") String secret) {
        this.canonical = mapper.copy().findAndRegisterModules()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.secret = secret == null ? "" : secret.trim();
    }

    String of(Object material) {
        if (secret.isBlank()) throw new IllegalStateException("Agent draft revision secret is unavailable");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update("reachai-agent-draft-revision-v1\0".getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(canonical.writeValueAsBytes(material)));
        } catch (Exception failure) {
            throw new IllegalStateException("Agent draft revision could not be computed", failure);
        }
    }
}

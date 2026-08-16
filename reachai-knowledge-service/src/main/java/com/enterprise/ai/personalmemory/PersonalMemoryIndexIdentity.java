package com.enterprise.ai.personalmemory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Hides raw runtime user identifiers from the Knowledge search projection. */
@Component
public class PersonalMemoryIndexIdentity {

    private final String hmacSecret;

    @Autowired
    public PersonalMemoryIndexIdentity(
            @Value("${reachai.personal-memory.index-identity-secret:${REACHAI_PERSONAL_MEMORY_INDEX_IDENTITY_SECRET:}}")
            String hmacSecret,
            @Value("${reachai.internal.service-secret:${REACHAI_INTERNAL_SERVICE_SECRET:}}")
            String internalServiceSecret,
            Environment environment) {
        this.hmacSecret = StringUtils.hasText(hmacSecret) ? hmacSecret.trim() : "";
        if (isProduction(environment)) {
            String internal = StringUtils.hasText(internalServiceSecret) ? internalServiceSecret.trim() : "";
            if (this.hmacSecret.length() < 32) {
                throw new IllegalStateException(
                        "personal memory index identity secret must contain at least 32 characters in production");
            }
            if (internal.length() < 32) {
                throw new IllegalStateException(
                        "internal service secret must contain at least 32 characters in production");
            }
            if (this.hmacSecret.equals(internal)) {
                throw new IllegalStateException(
                        "personal memory index identity secret must differ from the internal service secret");
            }
        }
    }

    public PersonalMemoryIndexIdentity(String hmacSecret) {
        this(hmacSecret, "", null);
    }

    public String hash(String tenantId, String runtimeUserId) {
        if (!StringUtils.hasText(hmacSecret)) {
            throw new IllegalStateException("REACHAI_PERSONAL_MEMORY_INDEX_IDENTITY_SECRET is required");
        }
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(runtimeUserId)) {
            throw new IllegalArgumentException("tenantId and runtimeUserId are required");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal((tenantId.trim().toLowerCase() + "\n" + runtimeUserId.trim())
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("personal memory index identity hashing failed", ex);
        }
    }

    private static boolean isProduction(Environment environment) {
        return environment != null && Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.trim().toLowerCase(Locale.ROOT))
                .anyMatch(profile -> "prod".equals(profile) || "production".equals(profile));
    }
}

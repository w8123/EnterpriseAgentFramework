package com.enterprise.ai.capability.catalog.scan;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public enum CapabilitySensitiveDataType {
    PHONE,
    EMAIL,
    ID_CARD,
    BANK_CARD,
    REAL_NAME,
    USER_CODE,
    USER_ID,
    PASSWORD_SECRET,
    ADDRESS,
    IP_ADDRESS,
    DEVICE_ID,
    SSO_TOKEN,
    API_KEY,
    COOKIE_SESSION,
    MEDICAL,
    BIOMETRIC,
    LOCATION,
    EDUCATIONAL_BACKGROUND,
    CREDIT,
    OTHER_PII;

    public String code() {
        return name();
    }

    public static List<String> allCodesSorted() {
        return Arrays.stream(values())
                .map(CapabilitySensitiveDataType::code)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    public static Set<String> normalizeTypes(Iterable<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String item : raw) {
            if (item == null || item.isBlank()) {
                continue;
            }
            try {
                out.add(CapabilitySensitiveDataType.valueOf(item.trim().toUpperCase(Locale.ROOT)).code());
            } catch (IllegalArgumentException ignored) {
                // Ignore hallucinated labels from the model.
            }
        }
        return out;
    }
}

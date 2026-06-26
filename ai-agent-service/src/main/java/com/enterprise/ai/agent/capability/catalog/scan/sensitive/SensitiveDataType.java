package com.enterprise.ai.agent.capability.catalog.scan.sensitive;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 敏感数据类型代码（与 LLM 输出及前端展示映射一致）。
 */
public enum SensitiveDataType {
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
                .map(SensitiveDataType::code)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    public static Set<String> normalizeTypes(Iterable<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String s : raw) {
            if (s == null || s.isBlank()) {
                continue;
            }
            String u = s.trim().toUpperCase(Locale.ROOT);
            try {
                out.add(SensitiveDataType.valueOf(u).code());
            } catch (IllegalArgumentException ignored) {
                // 丢弃模型幻觉代码
            }
        }
        return out;
    }
}

package com.enterprise.ai.runtime.execution;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Control-owned observability timings. Never accepted from public Runtime body/metadata.
 * Only {@link com.enterprise.ai.runtime.internal.RuntimeAgentExecutionInternalController}
 * may construct this after HMAC/nonce/identity verification succeeds.
 */
public record TrustedControlTiming(
        Long sessionLookupMs,
        Long userMessageAuditMs,
        Long preRuntimeMs
) {

    public static TrustedControlTiming empty() {
        return new TrustedControlTiming(null, null, null);
    }

    public boolean isEmpty() {
        return sessionLookupMs == null && userMessageAuditMs == null && preRuntimeMs == null;
    }

    /**
     * Removes {@code controlTiming} from {@code body} and returns allowlisted numeric timings.
     * Call only after internal auth has already succeeded.
     */
    public static TrustedControlTiming extractAndRemoveFromBody(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return empty();
        }
        Object raw = body.remove("controlTiming");
        return fromPayload(raw);
    }

    public static TrustedControlTiming fromPayload(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return empty();
        }
        return new TrustedControlTiming(
                asNonNegativeMillis(map.get("control.sessionLookupMs")),
                asNonNegativeMillis(map.get("control.userMessageAuditMs")),
                asNonNegativeMillis(map.get("control.preRuntimeMs")));
    }

    public Map<String, Object> toMetadataEntries() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (sessionLookupMs != null) {
            out.put("control.sessionLookupMs", sessionLookupMs);
        }
        if (userMessageAuditMs != null) {
            out.put("control.userMessageAuditMs", userMessageAuditMs);
        }
        if (preRuntimeMs != null) {
            out.put("control.preRuntimeMs", preRuntimeMs);
        }
        return out;
    }

    private static Long asNonNegativeMillis(Object value) {
        if (value instanceof Number number) {
            long millis = number.longValue();
            return millis < 0L ? 0L : millis;
        }
        if (value == null) {
            return null;
        }
        try {
            long millis = Long.parseLong(String.valueOf(value).trim());
            return millis < 0L ? 0L : millis;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

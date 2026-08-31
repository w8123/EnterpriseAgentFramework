package com.enterprise.ai.runtime.eval;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-owned execution policy for Eval runs.
 *
 * <p>This object is passed through typed Runtime APIs and is never reconstructed from a public
 * request body. Until a dedicated sandbox adapter exists, every Eval execution fails closed for
 * raw HTTP, Page Bridge, A2A, and non-read-only Capability calls.</p>
 */
public record RuntimeEvalExecutionContext(Mode mode,
                                          String experimentId,
                                          String itemId,
                                          String targetFingerprint) {

    private static final RuntimeEvalExecutionContext NONE =
            new RuntimeEvalExecutionContext(Mode.NONE, null, null, null);

    public RuntimeEvalExecutionContext {
        mode = mode == null ? Mode.NONE : mode;
        experimentId = normalized(experimentId);
        itemId = normalized(itemId);
        targetFingerprint = normalized(targetFingerprint);
    }

    public static RuntimeEvalExecutionContext none() {
        return NONE;
    }

    public static RuntimeEvalExecutionContext readOnly(String experimentId,
                                                       String itemId,
                                                       String targetFingerprint) {
        return new RuntimeEvalExecutionContext(
                Mode.READ_ONLY_EXECUTION, experimentId, itemId, targetFingerprint);
    }

    public boolean isEvaluation() {
        return mode != Mode.NONE;
    }

    public boolean requiresReadOnlyCapability() {
        return isEvaluation();
    }

    public boolean blocksRawExternalCalls() {
        return isEvaluation();
    }

    /**
     * Signed Runtime -> Capability payload. The Runtime gateway strips any caller-provided value
     * with the same key before inserting this map into the exact HMAC-signed body.
     */
    public Map<String, Object> toSignedPolicy() {
        if (!isEvaluation()) {
            return Map.of();
        }
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("mode", mode.name());
        policy.put("sideEffectPolicy", "READ_ONLY_ONLY");
        putIfPresent(policy, "experimentId", experimentId);
        putIfPresent(policy, "itemId", itemId);
        putIfPresent(policy, "targetFingerprint", targetFingerprint);
        return Map.copyOf(policy);
    }

    public enum Mode {
        NONE,
        TRACE_SCORE,
        READ_ONLY_EXECUTION,
        SANDBOX_INTEGRATION
    }

    private static String normalized(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}

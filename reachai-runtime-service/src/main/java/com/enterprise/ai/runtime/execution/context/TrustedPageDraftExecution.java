package com.enterprise.ai.runtime.execution.context;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** Runtime-owned marker. User JSON cannot construct this type or grant a page effect. */
public final class TrustedPageDraftExecution {
    public static final String CONTEXT_KEY = "__trustedPageDraftExecution";
    public static final String CHECKPOINT_KEY = "__trustedPageDraftCheckpoint";
    private final Map<String, Object> pin;
    private final Map<String, Object> args;
    private final List<String> unhandledItemLabels;

    private TrustedPageDraftExecution(Map<String, Object> pin, Map<String, Object> args, List<String> unhandledItemLabels) {
        Map<String, Object> frozenPin = new LinkedHashMap<>(pin);
        if (pin.get("declaration") instanceof Map<?, ?> declaration) {
            frozenPin.put("declaration", java.util.Collections.unmodifiableMap(new LinkedHashMap<>(declaration)));
        }
        this.pin = java.util.Collections.unmodifiableMap(frozenPin);
        this.args = Map.copyOf(args);
        this.unhandledItemLabels = List.copyOf(unhandledItemLabels);
    }

    public static TrustedPageDraftExecution fromRuntime(Map<String, Object> pin, Map<String, Object> args) {
        return fromRuntime(pin, args, List.of());
    }

    public static TrustedPageDraftExecution fromRuntime(Map<String, Object> pin, Map<String, Object> args, List<String> unhandledItemLabels) {
        if (pin == null || args == null || !(pin.get("declaration") instanceof Map<?, ?> declaration)
                || !(declaration.get("approvalId") instanceof Number id) || id.longValue() <= 0
                || !(declaration.get("revision") instanceof Number revision) || revision.longValue() <= 0
                || !(declaration.get("catalogHash") instanceof String hash) || !hash.matches("[0-9a-f]{64}")
                || unhandledItemLabels == null || unhandledItemLabels.size() > 8
                || unhandledItemLabels.stream().anyMatch(label -> label == null
                        || !label.matches("[\\p{IsHan}]{2,8}|[A-Za-z][A-Za-z_]{0,31}"))) {
            return null;
        }
        try { return new TrustedPageDraftExecution(pin, args, unhandledItemLabels); }
        catch (RuntimeException invalid) { return null; }
    }

    public Map<String, Object> pin() { return pin; }
    public Map<String, Object> args() { return args; }
    public List<String> unhandledItemLabels() { return unhandledItemLabels; }

    public boolean matchesGraph(String graph) {
        if (graph == null || !(pin.get("graphHash") instanceof String expected) || !expected.matches("[0-9a-f]{64}")) return false;
        try {
            String actual = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(graph.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return java.security.MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public Map<String, Object> checkpoint() { return Map.of("pin", pin, "args", args, "unhandledItemLabels", unhandledItemLabels); }

    /** Only the authenticated, server-persisted Workflow resume path may call this. */
    public static TrustedPageDraftExecution fromCheckpoint(Object raw) {
        if (!(raw instanceof Map<?, ?> state) || !(state.get("pin") instanceof Map<?, ?> p)
                || !(state.get("args") instanceof Map<?, ?> a)) return null;
        Map<String, Object> pin = new LinkedHashMap<>(), args = new LinkedHashMap<>();
        for (var entry : p.entrySet()) if (entry.getKey() instanceof String key) pin.put(key, entry.getValue()); else return null;
        for (var entry : a.entrySet()) if (entry.getKey() instanceof String key) args.put(key, entry.getValue()); else return null;
        Object rawLabels = state.get("unhandledItemLabels");
        if (rawLabels == null) return fromRuntime(pin, args);
        if (!(rawLabels instanceof List<?> list) || list.stream().anyMatch(label -> !(label instanceof String))) return null;
        return fromRuntime(pin, args, list.stream().map(String.class::cast).toList());
    }
}

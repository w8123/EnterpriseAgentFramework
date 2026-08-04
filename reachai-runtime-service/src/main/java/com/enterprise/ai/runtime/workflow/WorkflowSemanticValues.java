package com.enterprise.ai.runtime.workflow;

import java.util.Locale;

/**
 * Canonical value domain for Workflow classification and execution identity.
 */
public final class WorkflowSemanticValues {

    public static final String KIND_GENERAL = "GENERAL";
    public static final String KIND_PAGE_ASSISTANT = "PAGE_ASSISTANT";

    public static final String AUTHORITY_USER = "USER";
    public static final String AUTHORITY_SDK = "SDK";
    public static final String AUTHORITY_SYSTEM = "SYSTEM";

    public static final String CHANNEL_STUDIO = "STUDIO";
    public static final String CHANNEL_AI_CODING = "AI_CODING";
    public static final String CHANNEL_SDK_SYNC = "SDK_SYNC";
    public static final String CHANNEL_AI_QUICK_ACCESS = "AI_QUICK_ACCESS";
    public static final String CHANNEL_SYSTEM_SEED = "SYSTEM_SEED";

    public static final String ENGINE_GRAPH_SPEC = "GRAPH_SPEC";

    private WorkflowSemanticValues() {
    }

    public static Resolved resolve(String workflowKind,
                                   String executionEngine,
                                   String definitionAuthority,
                                   String creationChannel,
                                   Resolved fallback) {
        String kindSource = firstText(workflowKind,
                fallback == null ? null : fallback.workflowKind(), KIND_GENERAL);
        String engineSource = firstText(executionEngine,
                fallback == null ? null : fallback.executionEngine(), ENGINE_GRAPH_SPEC);
        String authoritySource = firstText(definitionAuthority,
                fallback == null ? null : fallback.definitionAuthority(), AUTHORITY_USER);
        String channelSource = firstText(creationChannel,
                fallback == null ? null : fallback.creationChannel(), CHANNEL_STUDIO);

        return new Resolved(
                normalizeWorkflowKind(kindSource),
                normalizeExecutionEngine(engineSource),
                normalizeDefinitionAuthority(authoritySource),
                normalizeCreationChannel(channelSource));
    }

    public static String normalizeWorkflowKind(String value) {
        return switch (normalized(value)) {
            case KIND_GENERAL -> KIND_GENERAL;
            case KIND_PAGE_ASSISTANT -> KIND_PAGE_ASSISTANT;
            default -> throw invalid("workflowKind", value);
        };
    }

    public static String normalizeExecutionEngine(String value) {
        return switch (normalized(value)) {
            case ENGINE_GRAPH_SPEC -> ENGINE_GRAPH_SPEC;
            default -> throw invalid("executionEngine", value);
        };
    }

    public static String normalizeDefinitionAuthority(String value) {
        return switch (normalized(value)) {
            case AUTHORITY_USER -> AUTHORITY_USER;
            case AUTHORITY_SDK -> AUTHORITY_SDK;
            case AUTHORITY_SYSTEM -> AUTHORITY_SYSTEM;
            default -> throw invalid("definitionAuthority", value);
        };
    }

    public static String normalizeCreationChannel(String value) {
        return switch (normalized(value)) {
            case CHANNEL_STUDIO -> CHANNEL_STUDIO;
            case CHANNEL_AI_CODING -> CHANNEL_AI_CODING;
            case CHANNEL_SDK_SYNC -> CHANNEL_SDK_SYNC;
            case CHANNEL_AI_QUICK_ACCESS -> CHANNEL_AI_QUICK_ACCESS;
            case CHANNEL_SYSTEM_SEED -> CHANNEL_SYSTEM_SEED;
            default -> throw invalid("creationChannel", value);
        };
    }

    private static String normalized(String value) {
        if (!hasText(value)) throw invalid("value", value);
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String firstText(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (hasText(value)) return value;
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static IllegalArgumentException invalid(String field, String value) {
        return new IllegalArgumentException("unsupported " + field + ": " + value);
    }

    public record Resolved(String workflowKind,
                           String executionEngine,
                           String definitionAuthority,
                           String creationChannel) {
    }
}

package com.enterprise.ai.runtime.agent;

/**
 * Which execution tools an Agent configuration may use.
 *
 * <p>{@code ALLOW_LIST}: only the Workflows bound to the configuration. {@code PROJECT_CAPABILITIES}:
 * those Workflows, plus every capability the Agent's project publishes to employees, found by need and
 * called as the employee who is chatting (the same permission, write approval and audit as that
 * employee's own AI tool).
 */
public final class RuntimeAgentToolCatalogMode {

    public static final String ALLOW_LIST = "ALLOW_LIST";
    public static final String PROJECT_CAPABILITIES = "PROJECT_CAPABILITIES";

    private RuntimeAgentToolCatalogMode() {
    }

    public static boolean supported(String mode) {
        return ALLOW_LIST.equalsIgnoreCase(mode) || PROJECT_CAPABILITIES.equalsIgnoreCase(mode);
    }

    public static boolean projectCapabilities(String mode) {
        return PROJECT_CAPABILITIES.equalsIgnoreCase(mode);
    }
}

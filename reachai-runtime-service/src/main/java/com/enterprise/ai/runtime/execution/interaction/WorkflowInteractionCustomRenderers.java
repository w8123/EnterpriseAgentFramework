package com.enterprise.ai.runtime.execution.interaction;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * Single whitelist source for CUSTOM INTERACTION rendererKey (Runtime handler + ReleaseValidation).
 */
public final class WorkflowInteractionCustomRenderers {

    public static final Set<String> SUPPORTED = Set.of(
            "form",
            "choice",
            "confirm",
            "table",
            "detail",
            "summary_card",
            "output_card");

    private WorkflowInteractionCustomRenderers() {
    }

    public static boolean isSupported(String rendererKey) {
        if (!StringUtils.hasText(rendererKey)) {
            return false;
        }
        return SUPPORTED.contains(rendererKey.trim().toLowerCase(Locale.ROOT));
    }
}

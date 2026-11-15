package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ActionView;
import java.util.Locale;

/** Null means the catalog risk is missing or unknown and must not execute. */
final class PageActionRiskFloor {
    private PageActionRiskFloor() { }

    static Boolean requiresConfirmation(String risk) {
        if (risk == null) return null;
        return switch (risk.trim().toUpperCase(Locale.ROOT)) {
            case "READ", "READ_ONLY" -> false;
            case "WRITE", "IRREVERSIBLE" -> true;
            default -> null;
        };
    }

    static Boolean requiresConfirmation(ActionView action) {
        if (action == null) return null;
        Boolean floor = requiresConfirmation(action.riskLevel());
        if (floor == null || floor) return floor;
        // SDK/scan registrations do not prove a handler is read-only. A manual
        // catalog declaration retains the existing read-only behavior.
        return !"MANUAL".equalsIgnoreCase(action.sourceType());
    }
}
